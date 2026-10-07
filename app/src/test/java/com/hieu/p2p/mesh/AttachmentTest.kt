package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.dm.DmStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AttachmentTest {

    private fun bytes(n: Int, seed: Long = 5) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun chain(net: FakeNetwork, vararg names: String) {
        names.forEach { net.node(it) }
        names.toList().zipWithNext().forEach { (a, b) -> net.link(a, b) }
        net.advance(15_000)
    }

    @Test
    fun voiceNoteArrivesWithWaveformAndDuration() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")
        val audio = bytes(95_000)
        val waveform = ByteArray(48) { (it * 5).toByte() }

        a.sendAttachment(c.id, DmKind.VOICE, audio, "", 31_500, waveform)
        net.drain()

        val got = c.conversation(a.id).single()
        assertEquals(DmKind.VOICE, got.kind)
        assertEquals(DmStatus.RECEIVED, got.status)
        assertEquals(31_500, got.durationMs)
        assertArrayEquals(waveform, got.preview)
        assertArrayEquals(audio, net.attachments.getValue("C").load(got.id))
        assertEquals(DmStatus.DELIVERED, a.conversation(c.id).single().status)
    }

    @Test
    fun twoMegabyteVideoSurvivesLossyMultiHop() {
        val net = FakeNetwork()
        chain(net, "A", "B", "C", "D")
        val a = net.nodes.getValue("A")
        val d = net.nodes.getValue("D")
        val clip = bytes(DmKind.VIDEO.maxBytes)
        val thumb = bytes(9_000, seed = 9)
        val random = Random(4)
        net.interceptor = { _, _, frame ->
            val p = com.hieu.p2p.mesh.core.Packet.decode(frame)
            if (p?.type == com.hieu.p2p.mesh.core.PacketType.FILE_CHUNK && random.nextInt(100) < 25) null else frame
        }

        a.sendAttachment(d.id, DmKind.VIDEO, clip, "Đường vào làng", 28_000, thumb)
        net.drain()
        // Thumbnail and length are known before the clip finishes.
        val early = d.conversation(a.id).single()
        assertEquals(DmStatus.RECEIVING, early.status)
        assertArrayEquals(thumb, early.preview)
        assertEquals(128, early.chunks)

        net.advance(120_000)
        val done = d.conversation(a.id).single()
        assertEquals(DmStatus.RECEIVED, done.status)
        assertArrayEquals(clip, net.attachments.getValue("D").load(done.id))
        assertEquals(DmStatus.DELIVERED, a.conversation(d.id).single().status)
    }

    @Test
    fun oversizeAttachmentsAreRefused() {
        val net = FakeNetwork()
        chain(net, "A", "B")
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        a.sendAttachment(b.id, DmKind.VOICE, bytes(DmKind.VOICE.maxBytes + 1), "", 1_000, null)
        net.drain()
        assertTrue(a.conversation(b.id).isEmpty())
        assertTrue(a.log.value.any { "Không gửi được tệp" in it.message })
    }
}
