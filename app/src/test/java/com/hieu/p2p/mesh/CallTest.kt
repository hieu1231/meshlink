package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.call.CallManager
import com.hieu.p2p.mesh.call.CallPhase
import com.hieu.p2p.mesh.call.EndReason
import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTest {

    private fun chain(net: FakeNetwork, vararg names: String) {
        names.forEach { net.node(it) }
        names.toList().zipWithNext().forEach { (a, b) -> net.link(a, b) }
        net.advance(15_000)
    }

    @Test
    fun videoCallAcrossTwoHopsCarriesEncryptedMediaBothWays() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")
        val atA = mutableListOf<ByteArray>()
        val atC = mutableListOf<ByteArray>()
        a.onCallMedia = { atA += it }
        c.onCallMedia = { atC += it }
        val relayed = mutableListOf<ByteArray>()
        net.interceptor = { from, _, bytes -> if (from == "B") relayed += bytes; bytes }

        a.startCall(c.id, video = true)
        net.drain()
        assertEquals(CallPhase.OUTGOING, a.call.value.phase)
        assertTrue("caller hears ringing", a.call.value.ringing)
        assertEquals(CallPhase.INCOMING, c.call.value.phase)
        assertTrue(c.call.value.video)

        c.acceptCall(video = true)
        net.drain()
        assertEquals(CallPhase.ACTIVE, a.call.value.phase)
        assertEquals(CallPhase.ACTIVE, c.call.value.phase)

        val frame = byteArrayOf(1) + "khung-hình-bí-mật".toByteArray()
        repeat(30) { a.sendCallMedia(frame) }
        c.sendCallMedia(byteArrayOf(2, 9, 9))
        net.drain()
        assertEquals(30, atC.size)
        assertArrayEquals(frame, atC.first())
        assertArrayEquals(byteArrayOf(2, 9, 9), atA.single())
        // The relay forwarded media it could not read, and none of it was flooded or signed.
        val mediaThroughB = relayed.mapNotNull { Packet.decode(it) }.filter { it.type == PacketType.CALL_MEDIA }
        assertTrue(mediaThroughB.size >= 31)
        assertTrue(mediaThroughB.all { it.signature.isEmpty() })
        assertTrue(relayed.none { String(it, Charsets.ISO_8859_1).contains("khung") })

        a.hangup()
        net.drain()
        assertEquals(CallPhase.ENDED, c.call.value.phase)
        assertEquals(EndReason.HANGUP, c.call.value.endReason)
    }

    @Test
    fun calleeCanAnswerWithVoiceOnly() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        a.startCall(b.id, video = true)
        net.drain()
        b.acceptCall(video = false)
        net.drain()
        assertEquals(CallPhase.ACTIVE, a.call.value.phase)
        assertFalse(a.call.value.video)
    }

    @Test
    fun declineBusyAndNoAnswer() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        val c = net.nodes.getValue("C")

        a.startCall(c.id, video = false)
        net.drain()
        c.declineCall()
        net.drain()
        assertEquals(EndReason.DECLINED, a.call.value.endReason)
        a.dismissCall(); c.dismissCall()

        // B is on a call with C; A gets "busy".
        b.startCall(c.id, video = false)
        net.drain()
        c.acceptCall(false)
        net.drain()
        a.startCall(b.id, video = false)
        net.drain()
        assertEquals(EndReason.BUSY, a.call.value.endReason)
        assertEquals(CallPhase.ACTIVE, b.call.value.phase)
        b.hangup(); net.drain(); a.dismissCall(); b.dismissCall(); c.dismissCall()

        // Nobody picks up.
        a.startCall(c.id, video = false)
        net.drain()
        net.advance(CallManager.RING_TIMEOUT_MS + 3_000)
        assertEquals(EndReason.NO_ANSWER, a.call.value.endReason)
        assertEquals(CallPhase.ENDED, c.call.value.phase)
    }

    @Test
    fun lostAcceptIsRepeatedAndBrokenLinkEndsTheCall() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        a.startCall(b.id, video = false)
        net.drain()

        // Drop B's first ACCEPT: A must still connect from the retry.
        var dropped = false
        net.interceptor = { from, _, bytes ->
            val p = Packet.decode(bytes)
            if (from == "B" && p?.type == PacketType.CALL_SIGNAL && !dropped && b.call.value.phase == CallPhase.ACTIVE) { dropped = true; null } else bytes
        }
        b.acceptCall(false)
        net.drain()
        assertTrue(dropped)
        net.advance(5_000)
        assertEquals(CallPhase.ACTIVE, a.call.value.phase)

        // Radio link breaks: both sides notice silence and end with LOST.
        net.interceptor = null
        net.unlink("A", "B")
        net.advance(CallManager.LOST_TIMEOUT_MS + 3_000)
        assertEquals(EndReason.LOST, a.call.value.endReason)
        assertEquals(EndReason.LOST, b.call.value.endReason)
    }

    @Test
    fun callingSomeoneUnreachableFailsFast() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        val a = net.nodes.getValue("A")
        val stranger = com.hieu.p2p.mesh.core.NodeId(0x1234)
        a.startCall(stranger, video = false)
        net.drain()
        assertEquals(EndReason.NO_ROUTE, a.call.value.endReason)
    }
}
