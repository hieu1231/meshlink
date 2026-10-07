package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.dm.DmStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ImageTransferTest {

    private fun image(size: Int) = ByteArray(size).also { Random(7).nextBytes(it) }

    private fun chain(net: FakeNetwork, vararg names: String) {
        names.forEach { net.node(it) }
        names.toList().zipWithNext().forEach { (a, b) -> net.link(a, b) }
        net.advance(15_000)
    }

    @Test
    fun imageCrossesThreeHopsEncryptedAndIntact() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C", "D")
        val a = net.nodes.getValue("A")
        val d = net.nodes.getValue("D")
        val photo = image(150_000)
        val relayed = mutableListOf<ByteArray>()
        net.interceptor = { from, _, bytes -> if (from == "B" || from == "C") relayed += bytes; bytes }

        a.sendImage(d.id, photo, "Cầu sập ở km 12")
        net.drain()

        val got = d.conversation(a.id).single()
        assertEquals(DmKind.IMAGE, got.kind)
        assertEquals(DmStatus.RECEIVED, got.status)
        assertEquals("Cầu sập ở km 12", got.text)
        assertArrayEquals(photo, net.attachments.getValue("D").load(got.id))
        assertEquals(DmStatus.DELIVERED, a.conversation(d.id).single().status)
        // Relays never see a recognisable piece of the image.
        val probe = photo.copyOfRange(50_000, 50_032)
        assertTrue(relayed.none { it.contains(probe) })
        assertTrue(net.nodes.getValue("B").conversations().isEmpty())
    }

    @Test
    fun lostSlicesAreRecoveredWithNack() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")
        val photo = image(200_000)
        val random = Random(1)
        var dropped = 0
        net.interceptor = { _, _, bytes ->
            val p = Packet.decode(bytes)
            if (p?.type == PacketType.FILE_CHUNK && random.nextInt(100) < 40) { dropped++; null } else bytes
        }

        a.sendImage(c.id, photo, "")
        net.drain()
        assertTrue(dropped > 0)
        assertEquals(DmStatus.RECEIVING, c.conversation(a.id).single().status)
        assertTrue(c.conversation(a.id).single().progress < c.conversation(a.id).single().chunks)

        net.advance(60_000)
        assertEquals(DmStatus.RECEIVED, c.conversation(a.id).single().status)
        assertArrayEquals(photo, net.attachments.getValue("C").load(c.conversation(a.id).single().id))
        assertEquals(DmStatus.DELIVERED, a.conversation(c.id).single().status)
    }

    @Test
    fun imageWaitsForRouteThenDelivers() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        net.node("C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")
        net.link("B", "C")
        net.advance(15_000)
        net.unlink("B", "C")
        net.advance(60_000)

        val photo = image(40_000)
        a.sendImage(c.id, photo, "ảnh chờ")
        net.drain()
        assertEquals(DmStatus.QUEUED, a.conversation(c.id).single().status)

        net.link("B", "C")
        net.advance(20_000)
        assertArrayEquals(photo, net.attachments.getValue("C").load(c.conversation(a.id).single().id))
        assertEquals(DmStatus.DELIVERED, a.conversation(c.id).single().status)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
