package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.dm.DmStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshNetworkTest {

    /** A - B - C - D chain: nobody but neighbours can hear each other. */
    private fun chain(net: FakeNetwork, vararg names: String) {
        names.forEach { net.node(it) }
        names.toList().zipWithNext().forEach { (a, b) -> net.link(a, b) }
        net.advance(15_000) // one announce round so far ends learn about each other
    }

    @Test
    fun multiHopRoutesConverge() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C", "D")
        val a = net.nodes.getValue("A")
        val d = net.nodes.getValue("D")

        val routeToD = a.status.value.routes.single { it.dest == d.id }
        assertEquals(3, routeToD.hops)
        assertEquals(net.nodes.getValue("B").id, routeToD.viaNode)
        assertEquals(3, a.status.value.routes.size)
        assertEquals("D", a.peerName(d.id))
    }

    @Test
    fun directMessageIsRelayedEncryptedAndAcknowledged() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        val c = net.nodes.getValue("C")

        val secret = "Cần nước uống ở khu B, 12 người"
        val relayedFrames = mutableListOf<ByteArray>()
        net.interceptor = { from, _, bytes -> if (from == "B") relayedFrames += bytes; bytes }

        a.sendDirect(c.id, secret)
        net.drain()

        val received = c.conversation(a.id).single()
        assertEquals(secret, received.text)
        assertEquals(DmStatus.RECEIVED, received.status)
        assertEquals(DmStatus.DELIVERED, a.conversation(c.id).single().status)
        // The relay saw the packets but neither stored nor could read the plaintext.
        assertTrue(b.conversations().isEmpty())
        val needle = secret.toByteArray()
        assertTrue(relayedFrames.isNotEmpty())
        assertTrue(relayedFrames.none { it.containsSubsequence(needle) })
    }

    @Test
    fun messageToUnreachableNodeIsStoredThenForwardedWhenRouteAppears() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")

        net.unlink("B", "C")
        net.advance(60_000) // route to C expires
        assertNull(a.status.value.routes.firstOrNull { it.dest == c.id })

        a.sendDirect(c.id, "hello later")
        net.drain()
        assertEquals(DmStatus.QUEUED, a.conversation(c.id).single().status)

        net.link("B", "C")
        net.advance(15_000)
        assertEquals("hello later", c.conversation(a.id).single().text)
        assertEquals(DmStatus.DELIVERED, a.conversation(c.id).single().status)
    }

    @Test
    fun routingHealsAroundBrokenLink() {
        // Ring A-B-C-D-A: when A-B breaks, A must reach B the long way round.
        val net = FakeNetwork()
        listOf("A", "B", "C", "D").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C"); net.link("C", "D"); net.link("D", "A")
        net.advance(15_000)
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        assertEquals(1, a.status.value.routes.single { it.dest == b.id }.hops)

        net.unlink("A", "B")
        net.advance(15_000)
        assertEquals(3, a.status.value.routes.single { it.dest == b.id }.hops)

        a.sendDirect(b.id, "vòng qua D và C")
        net.drain()
        assertEquals("vòng qua D và C", b.conversation(a.id).single().text)
    }

    @Test
    fun crdtOpsFloodAcrossTheMesh() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C", "D")
        net.nodes.getValue("A").put("board", "sos-1", "Kẹt ở tầng 3")
        net.drain()
        net.nodes.values.forEach { assertEquals("Kẹt ở tầng 3", it.data.get("board", "sos-1")) }
    }

    @Test
    fun partitionedNodesConvergeViaAntiEntropy() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        net.node("C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")

        // Both sides write while partitioned, including a conflicting write to the same key.
        a.put("board", "shelter", "Trường học: còn 20 chỗ")
        repeat(50) { a.put("chat", "a-$it", "msg $it") }
        net.drain()
        net.now += 5
        c.put("board", "shelter", "Trường học: đã đầy")
        c.put("board", "water", "Có nước sạch ở nhà văn hoá")

        net.link("B", "C") // C joins: digest exchange fills every gap, then cascades on to A
        net.advance(3_000)

        val expected = net.nodes.getValue("A").data.versionVector()
        net.nodes.values.forEach { node ->
            assertEquals(expected, node.data.versionVector())
            assertEquals("Trường học: đã đầy", node.data.get("board", "shelter")) // later HLC wins everywhere
            assertEquals("Có nước sạch ở nhà văn hoá", node.data.get("board", "water"))
            assertEquals(50, node.data.entries("chat").size)
        }
        assertEquals(c.data.entries("board").toSet(), a.data.entries("board").toSet())
    }

    @Test
    fun deletesReplicateAsTombstones() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        a.put("board", "x", "temp")
        net.drain()
        net.nodes.getValue("C").put("board", "x", null)
        net.drain()
        net.nodes.values.forEach { assertNull(it.data.get("board", "x")) }
    }

    @Test
    fun forgedAndTamperedPacketsAreDroppedAtFirstHop() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val c = net.nodes.getValue("C")
        val b = net.nodes.getValue("B")
        val rejectedBefore = b.status.value.stats.rejected

        // Flip one payload byte of every CRDT packet A sends.
        net.interceptor = { from, _, bytes ->
            val p = Packet.decode(bytes)
            if (from == "A" && p?.type == PacketType.CRDT_OPS) {
                bytes.copyOf().also { it[it.size - p.signature.size - 2] = (it[it.size - p.signature.size - 2] + 1).toByte() }
            } else bytes
        }
        net.nodes.getValue("A").put("board", "fake", "tampered")
        net.drain()

        assertNull(c.data.get("board", "fake"))
        assertNull(b.data.get("board", "fake"))
        net.advance(1_000) // publish fresh stats
        assertTrue(b.status.value.stats.rejected > rejectedBefore)
    }

    @Test
    fun largerRandomMeshFullyConverges() {
        val net = FakeNetwork()
        val random = java.util.Random(42)
        val names = (0 until 30).map { "N$it" }
        names.forEach { net.node(it) }
        // Random connected graph: a spanning chain plus a few shortcuts, max ~4 links each like BLE.
        names.zipWithNext().forEach { (x, y) -> net.link(x, y) }
        repeat(10) {
            val x = names[random.nextInt(names.size)]
            val y = names[random.nextInt(names.size)]
            if (x != y) net.link(x, y)
        }
        net.advance(20_000)
        names.forEachIndexed { i, n -> net.nodes.getValue(n).put("chat", "k$i", "from $n") }
        net.drain()
        net.advance(25_000)

        val first = net.nodes.getValue("N0")
        val last = net.nodes.getValue("N29")
        assertEquals(29, first.status.value.routes.size)
        assertNotNull(first.status.value.routes.firstOrNull { it.dest == last.id })
        net.nodes.values.forEach { assertEquals(30, it.data.entries("chat").size) }

        first.sendDirect(last.id, "end to end")
        net.drain()
        assertEquals(DmStatus.DELIVERED, first.conversation(last.id).single().status)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun oldProtocolVersionIsReportedNotSilentlyDropped() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        val b = net.nodes.getValue("B")
        // Pretend A runs an older app: rewrite the version byte of everything it sends.
        net.interceptor = { from, _, bytes -> if (from == "A") bytes.copyOf().also { it[1] = 1 } else bytes }
        net.nodes.getValue("A").put("board", "x", "y")
        net.drain()
        net.advance(1_000)
        assertEquals(1, b.status.value.outdatedNeighbors)
        assertTrue(b.log.value.any { "v1" in it.message })
    }
}
