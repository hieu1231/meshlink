package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.crdt.CrdtOp
import com.hieu.p2p.mesh.crdt.CrdtStore
import com.hieu.p2p.mesh.crdt.Hlc
import com.hieu.p2p.mesh.crdt.HlcClock
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.crypto.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolUnitTest {

    @Test
    fun packetRoundTrip() {
        val p = Packet(PacketType.UNICAST, 42L, NodeId(7), NodeId(9), 5, 2, 1234L, byteArrayOf(1, 2, 3), byteArrayOf(9, 9))
        val q = Packet.decode(p.encode())!!
        assertEquals(p.type, q.type)
        assertEquals(p.id, q.id)
        assertEquals(p.src, q.src)
        assertEquals(p.dst, q.dst)
        assertEquals(p.ttl, q.ttl)
        assertEquals(p.hops, q.hops)
        assertArrayEquals(p.payload, q.payload)
        assertArrayEquals(p.signature, q.signature)
    }

    @Test
    fun malformedPacketsDecodeToNull() {
        assertNull(Packet.decode(ByteArray(0)))
        assertNull(Packet.decode(ByteArray(10) { 0x4D }))
        val good = Packet(PacketType.ACK, 1, NodeId(1), NodeId(2), 3, 0, 0, ByteArray(4)).encode()
        assertNull(Packet.decode(good.copyOf(good.size - 1)))
        assertNull(Packet.decode(good + 0))
    }

    @Test
    fun nodeIdIsDerivedFromKey() {
        val id = Identity.generate()
        assertEquals(NodeId.fromPublicKey(id.signPublic), id.nodeId)
        assertEquals(id.nodeId, NodeId.fromHex(id.nodeId.hex))
        assertEquals(id.nodeId, Identity.restore(id.export()).nodeId)
    }

    @Test
    fun signAndVerify() {
        val id = Identity.generate()
        val data = "hello".toByteArray()
        val sig = id.sign(data)
        val key = Crypto.publicKey(id.signPublic)
        assertTrue(Crypto.verify(key, data, sig))
        assertFalse(Crypto.verify(key, "hellO".toByteArray(), sig))
        assertFalse(Crypto.verify(key, data, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun ecdhIsSymmetricAndAeadDetectsTampering() {
        val a = Identity.generate()
        val b = Identity.generate()
        val s1 = a.agree(Crypto.publicKey(b.dhPublic))
        val s2 = b.agree(Crypto.publicKey(a.dhPublic))
        assertArrayEquals(s1, s2)

        val key = Crypto.hkdf(s1, byteArrayOf(1), "t".toByteArray())
        val aad = byteArrayOf(5, 6)
        val sealed = Crypto.seal(key, "bí mật".toByteArray(), aad)
        assertEquals("bí mật", String(Crypto.open(key, sealed, aad)!!))
        assertNull(Crypto.open(key, sealed, byteArrayOf(5, 7)))
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertNull(Crypto.open(key, sealed, aad))
    }

    @Test
    fun crdtConvergesRegardlessOfOrderAndDuplicates() {
        val n1 = NodeId(1)
        val n2 = NodeId(2)
        val ops = listOf(
            CrdtOp(n1, 1, "c", "k", "a", Hlc(100, 0, n1)),
            CrdtOp(n2, 1, "c", "k", "b", Hlc(100, 0, n2)), // same time: higher node id wins
            CrdtOp(n1, 2, "c", "j", "x", Hlc(101, 0, n1)),
            CrdtOp(n2, 2, "c", "j", null, Hlc(102, 0, n2)), // later delete wins
        )
        val results = (0 until 20).map {
            val store = CrdtStore()
            (ops + ops).shuffled(java.util.Random(it.toLong())).forEach { op -> store.apply(op) }
            store.entries("c").toSet()
        }
        assertTrue(results.all { it == results.first() })
        assertEquals(setOf("b"), results.first().map { it.value }.toSet())
    }

    @Test
    fun versionVectorTracksGapsAndMissingOps() {
        val n = NodeId(1)
        val store = CrdtStore()
        listOf(1L, 2L, 4L).forEach { store.apply(CrdtOp(n, it, "c", "k$it", "v", Hlc(it, 0, n))) }
        assertEquals(2L, store.versionVector()[n])
        assertEquals(listOf(4L), store.missingFor(mapOf(n to 2L)).map { it.seq })
        assertTrue(store.isBehind(mapOf(n to 3L)))
        assertFalse(store.isBehind(mapOf(n to 2L)))
    }

    @Test
    fun hlcIsMonotonicAndCausal() {
        var now = 1000L
        val clock = HlcClock(NodeId(1)) { now }
        val t1 = clock.now()
        val t2 = clock.now()
        assertTrue(t2 > t1)
        clock.observe(Hlc(5000, 3, NodeId(2)))
        val t3 = clock.now()
        assertTrue(t3 > Hlc(5000, 3, NodeId(2)))
        now = 999_999_999_999L // far future remote is ignored
        clock.observe(Hlc(now + 10 * HlcClock.MAX_DRIFT_MS, 0, NodeId(3)))
        assertTrue(clock.now().wall == now)
    }

    @Test
    fun opsChunkWithinLimit() {
        val n = NodeId(1)
        val ops = (1L..500L).map { CrdtOp(n, it, "chat", "key-$it", "x".repeat(200), Hlc(it, 0, n)) }
        val chunks = CrdtOp.encodeChunks(ops, 24 * 1024)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= 24 * 1024 })
        assertEquals(ops, chunks.flatMap { CrdtOp.decodeList(it)!! })
    }
}
