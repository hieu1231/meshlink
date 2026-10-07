package com.hieu.p2p.mesh.call

import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.crypto.Identity
import com.hieu.p2p.mesh.dm.SessionKeys
import com.hieu.p2p.mesh.routing.MeshRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallPhase { IDLE, OUTGOING, INCOMING, ACTIVE, ENDED }

enum class EndReason {
    /** Someone hung up normally. */
    HANGUP,
    DECLINED,
    BUSY,
    NO_ANSWER,
    /** No media for too long: radio link gone. */
    LOST,
    /** There is no route to the person at all. */
    NO_ROUTE,
}

data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: Long = 0,
    val peer: NodeId? = null,
    val outgoing: Boolean = false,
    /** Video was requested for this call. */
    val video: Boolean = false,
    /** The other side has answered and is reachable (we heard RINGING). */
    val ringing: Boolean = false,
    val activeSince: Long = 0,
    val endReason: EndReason? = null,
    val hops: Int = 0,
)

/**
 * One call at a time, set up over the mesh.
 *
 * Signalling (CALL_SIGNAL) is signed by the router and sealed with the pairwise session key:
 * INVITE carries a fresh random 256-bit call key, so only the two parties can read the media.
 * INVITE and ACCEPT are repeated until acknowledged, since a single packet can be lost.
 *
 * Media (CALL_MEDIA) is sealed with the call key (AES-GCM, sequence number as AAD) and sent on the
 * router's unsigned, route-only fast path. Media is opaque bytes here: the app's call engine
 * decides what audio/video frames look like.
 *
 * Not thread-safe; MeshNode drives it from its single protocol thread.
 */
class CallManager(
    private val identity: Identity,
    private val router: MeshRouter,
    private val keys: SessionKeys,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** Decrypted media frames from the other side. Called on the protocol thread. */
    var onMedia: (ByteArray) -> Unit = {}
    var onLog: (String) -> Unit = {}

    private var callKey: ByteArray? = null
    private var sendSeq = 0
    private var lastSignalAt = 0L
    private var phaseSince = 0L
    private var lastMediaIn = 0L
    private var lastMediaOut = 0L
    private var heardMedia = false
    /** Preview call: our own media comes straight back, no peer, no timers. */
    private var echo = false

    private val s get() = _state.value

    // ---- user actions ----------------------------------------------------------------------------

    /** Starts a call. @return false if busy or there is no route (state says why). */
    fun call(peer: NodeId, video: Boolean): Boolean {
        if (s.phase == CallPhase.OUTGOING || s.phase == CallPhase.INCOMING || s.phase == CallPhase.ACTIVE) return false
        val id = Crypto.randomLong()
        reset()
        _state.value = CallState(CallPhase.OUTGOING, id, peer, outgoing = true, video = video, hops = router.hopsTo(peer) ?: 0)
        phaseSince = clock()
        if (router.peer(peer) == null || router.hopsTo(peer) == null) {
            end(EndReason.NO_ROUTE, notify = false)
            return false
        }
        callKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        sendInvite()
        onLog("Gọi ${if (video) "video" else "thoại"} tới ${peer.short}")
        return true
    }

    fun accept(video: Boolean) {
        if (s.phase != CallPhase.INCOMING) return
        _state.value = s.copy(phase = CallPhase.ACTIVE, video = video, activeSince = clock())
        phaseSince = clock()
        lastMediaIn = clock()
        signal(ACCEPT, byteArrayOf(if (video) 1 else 0))
        onLog("Đã nhận cuộc gọi")
    }

    fun decline() {
        if (s.phase != CallPhase.INCOMING) return
        signal(REJECT, byteArrayOf(REJECT_DECLINED))
        end(EndReason.DECLINED, notify = false)
    }

    fun hangup() {
        if (s.phase == CallPhase.IDLE || s.phase == CallPhase.ENDED) return
        end(EndReason.HANGUP, notify = true)
    }

    /** Back to idle once the UI has shown how the call ended. */
    fun dismiss() {
        if (s.phase == CallPhase.ENDED) {
            reset()
            _state.value = CallState()
        }
    }

    /**
     * Debug builds: puts the call screen in [phase] with [peer] without anyone on the other end,
     * for design review. Media we send is played back to us as if the peer sent it.
     */
    fun preview(peer: NodeId, phase: CallPhase, video: Boolean, ringing: Boolean = false) {
        reset()
        echo = true
        val now = clock()
        _state.value = CallState(phase, Crypto.randomLong(), peer, outgoing = phase != CallPhase.INCOMING, video = video,
            ringing = ringing, activeSince = if (phase == CallPhase.ACTIVE) now - 83_000 else 0, hops = 1)
    }

    // ---- media -----------------------------------------------------------------------------------

    fun sendMedia(frame: ByteArray): Boolean {
        val st = s
        if (echo) {
            if (st.phase == CallPhase.ACTIVE && frame.isNotEmpty() && frame[0] != KEEPALIVE) onMedia(frame)
            return true
        }
        val key = callKey ?: return false
        val peer = st.peer ?: return false
        if (st.phase != CallPhase.ACTIVE) return false
        val seq = ++sendSeq
        val sealed = Crypto.seal(key, frame, mediaAad(st.callId, seq, identity.nodeId))
        lastMediaOut = clock()
        return router.sendRouted(peer, PacketType.CALL_MEDIA, ByteWriter(sealed.size + 16).long(st.callId).int(seq).raw(sealed).toByteArray())
    }

    // ---- inbound ---------------------------------------------------------------------------------

    fun onPacket(p: Packet) {
        when (p.type) {
            PacketType.CALL_SIGNAL -> onSignal(p)
            PacketType.CALL_MEDIA -> onMediaPacket(p)
            else -> Unit
        }
    }

    private fun onMediaPacket(p: Packet) {
        val st = s
        val key = callKey ?: return
        if (p.src != st.peer || (st.phase != CallPhase.ACTIVE && st.phase != CallPhase.OUTGOING)) return
        val r = ByteReader(p.payload)
        val (callId, seq) = try { r.long() to r.int() } catch (e: RuntimeException) { return }
        if (callId != st.callId) return
        val sealed = try { r.raw(r.remaining) } catch (e: RuntimeException) { return }
        val frame = Crypto.open(key, sealed, mediaAad(callId, seq, p.src)) ?: return
        lastMediaIn = clock()
        heardMedia = true
        // Media from the callee means our ACCEPT reached them... or theirs reached us: either way, go live.
        if (st.phase == CallPhase.OUTGOING) goActive()
        if (frame.isNotEmpty() && frame[0] != KEEPALIVE) onMedia(frame)
    }

    private fun onSignal(p: Packet) {
        val peerInfo = router.peer(p.src) ?: return
        val r = ByteReader(p.payload)
        val callId = try { r.long() } catch (e: RuntimeException) { return }
        val sealed = try { r.bytes(4096) } catch (e: RuntimeException) { return }
        val body = Crypto.open(keys.of(peerInfo), sealed, signalAad(p.src, identity.nodeId, callId)) ?: return
        if (body.isEmpty()) return
        val kind = body[0]
        val st = s
        when (kind) {
            INVITE -> {
                if (body.size < 34) return
                val video = body[1] == 1.toByte()
                when {
                    // A repeated invite: our RINGING or ACCEPT was lost, say it again.
                    st.callId == callId -> when (st.phase) {
                        CallPhase.INCOMING -> signal(RINGING, ByteArray(0), to = p.src, id = callId)
                        CallPhase.ACTIVE -> signal(ACCEPT, byteArrayOf(if (st.video) 1 else 0), to = p.src, id = callId)
                        else -> Unit
                    }
                    st.phase == CallPhase.OUTGOING || st.phase == CallPhase.INCOMING || st.phase == CallPhase.ACTIVE ->
                        signal(REJECT, byteArrayOf(REJECT_BUSY), to = p.src, id = callId)
                    else -> {
                        reset()
                        callKey = body.copyOfRange(2, 34)
                        _state.value = CallState(CallPhase.INCOMING, callId, p.src, outgoing = false, video = video, hops = router.hopsTo(p.src) ?: 0)
                        phaseSince = clock()
                        signal(RINGING, ByteArray(0))
                        onLog("Cuộc gọi ${if (video) "video" else "thoại"} đến từ ${p.src.short}")
                    }
                }
            }
            RINGING -> if (st.callId == callId && st.phase == CallPhase.OUTGOING && !st.ringing) {
                _state.value = st.copy(ringing = true)
            }
            ACCEPT -> if (st.callId == callId && st.phase == CallPhase.OUTGOING) {
                // The callee may decline video and answer with voice only.
                val video = st.video && body.size > 1 && body[1] == 1.toByte()
                _state.value = st.copy(video = video)
                goActive()
            }
            REJECT -> if (st.callId == callId && st.phase == CallPhase.OUTGOING) {
                end(if (body.size > 1 && body[1] == REJECT_BUSY) EndReason.BUSY else EndReason.DECLINED, notify = false)
            }
            END -> if (st.callId == callId && st.phase != CallPhase.ENDED && st.phase != CallPhase.IDLE) {
                end(EndReason.HANGUP, notify = false)
            }
        }
    }

    // ---- timers ----------------------------------------------------------------------------------

    fun tick() {
        if (echo) return
        val now = clock()
        val st = s
        when (st.phase) {
            CallPhase.OUTGOING -> when {
                now - phaseSince > RING_TIMEOUT_MS -> end(EndReason.NO_ANSWER, notify = true)
                // Nobody even acknowledged the invite: the person is unreachable.
                !st.ringing && now - phaseSince > NO_ROUTE_TIMEOUT_MS -> end(EndReason.NO_ROUTE, notify = true)
                now - lastSignalAt >= RESEND_MS -> sendInvite()
            }
            CallPhase.INCOMING -> if (now - phaseSince > RING_TIMEOUT_MS) end(EndReason.NO_ANSWER, notify = false)
            CallPhase.ACTIVE -> {
                // Our ACCEPT may have been lost: repeat it until the caller's media shows up.
                if (!st.outgoing && !heardMedia && now - lastSignalAt >= RESEND_MS) signal(ACCEPT, byteArrayOf(if (st.video) 1 else 0))
                // Keep the line alive when both sides are muted with video off.
                if (now - lastMediaOut >= KEEPALIVE_MS) sendMedia(byteArrayOf(KEEPALIVE))
                if (now - lastMediaIn > LOST_TIMEOUT_MS) end(EndReason.LOST, notify = true)
                val hops = router.hopsTo(st.peer!!) ?: 0
                if (hops != st.hops) _state.value = st.copy(hops = hops)
            }
            else -> Unit
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun goActive() {
        val st = s
        if (st.phase == CallPhase.ACTIVE) return
        _state.value = st.copy(phase = CallPhase.ACTIVE, activeSince = clock())
        phaseSince = clock()
        lastMediaIn = clock()
        onLog("Cuộc gọi đã kết nối")
    }

    private fun sendInvite() {
        val key = callKey ?: return
        signal(INVITE, byteArrayOf(if (s.video) 1 else 0) + key)
    }

    private fun end(reason: EndReason, notify: Boolean) {
        val st = s
        if (notify) repeat(2) { signal(END, ByteArray(0)) }
        _state.value = st.copy(phase = CallPhase.ENDED, endReason = reason)
        callKey = null
        onLog("Cuộc gọi kết thúc: $reason")
    }

    private fun reset() {
        callKey = null
        sendSeq = 0
        lastSignalAt = 0
        lastMediaIn = 0
        lastMediaOut = 0
        heardMedia = false
        echo = false
    }

    private fun signal(kind: Byte, extra: ByteArray, to: NodeId? = null, id: Long? = null) {
        val peer = to ?: s.peer ?: return
        val callId = id ?: s.callId
        val info = router.peer(peer) ?: return
        val sealed = Crypto.seal(keys.of(info), byteArrayOf(kind) + extra, signalAad(identity.nodeId, peer, callId))
        router.sendUnicast(peer, PacketType.CALL_SIGNAL, ByteWriter(sealed.size + 12).long(callId).bytes(sealed).toByteArray())
        if (to == null) lastSignalAt = clock()
    }

    private fun signalAad(from: NodeId, to: NodeId, callId: Long) =
        ByteWriter(24).long(from.raw).long(to.raw).long(callId).toByteArray()

    private fun mediaAad(callId: Long, seq: Int, sender: NodeId) =
        ByteWriter(20).long(callId).int(seq).long(sender.raw).toByteArray()

    companion object {
        private const val INVITE: Byte = 1
        private const val RINGING: Byte = 2
        private const val ACCEPT: Byte = 3
        private const val REJECT: Byte = 4
        private const val END: Byte = 5
        private const val REJECT_DECLINED: Byte = 1
        private const val REJECT_BUSY: Byte = 2

        /** First byte of a media frame reserved for keep-alives; the app uses 1 and up. */
        const val KEEPALIVE: Byte = 0

        const val RESEND_MS = 2_000L
        const val RING_TIMEOUT_MS = 45_000L
        const val NO_ROUTE_TIMEOUT_MS = 12_000L
        const val KEEPALIVE_MS = 2_000L
        const val LOST_TIMEOUT_MS = 12_000L
    }
}
