package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.NodeStatus
import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.core.Transport
import com.hieu.p2p.mesh.crdt.CrdtOp
import com.hieu.p2p.mesh.crdt.CrdtReplica
import com.hieu.p2p.mesh.crdt.CrdtStore
import com.hieu.p2p.mesh.crdt.OpLog
import com.hieu.p2p.mesh.crdt.VersionVector
import com.hieu.p2p.mesh.crypto.Identity
import com.hieu.p2p.mesh.dm.AttachmentStore
import com.hieu.p2p.mesh.dm.DirectMessage
import com.hieu.p2p.mesh.dm.InMemoryAttachmentStore
import com.hieu.p2p.mesh.dm.DirectMessenger
import com.hieu.p2p.mesh.dm.DmKind
import com.hieu.p2p.mesh.dm.SessionKeys
import com.hieu.p2p.mesh.call.CallManager
import com.hieu.p2p.mesh.call.CallPhase
import com.hieu.p2p.mesh.call.CallState
import com.hieu.p2p.mesh.dm.MessageStore
import com.hieu.p2p.mesh.routing.MeshConfig
import com.hieu.p2p.mesh.routing.MeshRouter
import com.hieu.p2p.mesh.routing.RouteInfo
import com.hieu.p2p.mesh.routing.RouterStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

data class MeshStatus(
    val running: Boolean = false,
    val links: Int = 0,
    /** Direct neighbours running an incompatible app version (they can't exchange anything). */
    val outdatedNeighbors: Int = 0,
    val routes: List<RouteInfo> = emptyList(),
    val stats: RouterStats = RouterStats(),
)

data class LogLine(val time: Long, val message: String)

/** One machine on the path of a traceroute, with the round-trip time from you to it and back. */
data class TraceHop(val index: Int, val node: NodeId, val rttMs: Long)

data class TraceResult(
    val target: NodeId,
    val traceId: Long,
    val startedAt: Long,
    /** Relays (and finally the target) in path order; gaps mean a relay did not answer. */
    val hops: List<TraceHop> = emptyList(),
    val reached: Boolean = false,
    val done: Boolean = false,
    val noRoute: Boolean = false,
)

/**
 * One mesh participant: transport + router + CRDT replica + direct messenger.
 *
 * Every mutation is funnelled through [executor] (a single thread in the app, inline in tests),
 * so the protocol code needs no locks. Observers read immutable snapshots from the StateFlows.
 */
class MeshNode(
    val identity: Identity,
    displayName: String,
    private val transport: Transport,
    opLog: OpLog,
    messageStore: MessageStore,
    private val executor: Executor,
    private val attachments: AttachmentStore = InMemoryAttachmentStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: MeshConfig = MeshConfig(),
) {
    val id: NodeId get() = identity.nodeId

    private val router = MeshRouter(identity, transport, clock, config)
    private val replica = CrdtReplica(identity.nodeId, clock, opLog)
    private val sessionKeys = SessionKeys(identity)
    private val messenger = DirectMessenger(identity, router, messageStore, clock, attachments, sessionKeys)
    private val calls = CallManager(identity, router, sessionKeys, clock)

    /** The current (or just-ended) call. */
    val call: StateFlow<CallState> get() = calls.state

    /** Decrypted media from the other side of the call; invoked on the protocol thread. */
    var onCallMedia: (ByteArray) -> Unit = {}
    private var running = false
    private var lastDigestAt = 0L
    /** Neighbours to nudge with our digest on the next tick, so catch-up data cascades hop by hop. */
    private val digestDue = LinkedHashSet<String>()
    private val outdated = HashSet<String>()

    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    /** Bumped whenever replicated data changes; observers re-read [data]. */
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    private val _messagesVersion = MutableStateFlow(0L)
    val messagesVersion: StateFlow<Long> = _messagesVersion.asStateFlow()

    private val _trace = MutableStateFlow<TraceResult?>(null)
    /** The most recent traceroute you started (one at a time). */
    val trace: StateFlow<TraceResult?> = _trace.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** Thread-safe read access to replicated state. */
    val data: CrdtStore get() = replica.store

    init {
        router.displayName = displayName
        router.localStatus = { nodeStatus }
        router.callback = RouterCallback()
        messenger.onChanged = { _messagesVersion.update { it + 1 } }
        messenger.onLog = ::log
        calls.onLog = ::log
        calls.onMedia = { onCallMedia(it) }
    }

    // ---- public API (thread-safe; work happens on the executor) --------------------------------

    fun start() = post {
        if (running) return@post
        running = true
        log("Khởi động nút ${id.short}")
        transport.start(TransportListener())
        publishStatus()
    }

    fun stop() = post {
        if (!running) return@post
        running = false
        transport.stop()
        router.reset()
        digestDue.clear()
        log("Đã dừng mạng lưới")
        publishStatus()
    }

    fun tick() = post {
        if (!running) return@post
        router.tick()
        messenger.tick()
        calls.tick()
        _trace.value?.let { t ->
            if (!t.done && clock() - t.startedAt > TRACE_TIMEOUT_MS) {
                _trace.value = t.copy(done = true)
                log("Dò đường: hết ${TRACE_TIMEOUT_MS / 1000} giây, không có phản hồi từ đích")
            }
        }
        val now = clock()
        val links = router.links()
        if (now - lastDigestAt >= config.syncIntervalMs) {
            lastDigestAt = now
            digestDue += links
        }
        digestDue.filter { it in links }.forEach(::sendDigest)
        digestDue.clear()
        publishStatus()
    }

    @Volatile private var nodeStatus = NodeStatus()
    private var announcedPosition: GeoPoint? = null

    /**
     * This phone's position (null = not shared) and battery. Moving far since the last announce
     * triggers an early one, so the radar on other phones keeps up with someone walking.
     */
    fun setLocalStatus(status: NodeStatus) = post {
        nodeStatus = status
        val now = status.position
        val before = announcedPosition
        val moved = when {
            now == null -> before != null
            before == null -> true
            else -> now.distanceTo(before) > EARLY_ANNOUNCE_METRES
        }
        if (moved && running) {
            announcedPosition = now
            router.announceNow()
        }
    }

    fun startCall(peer: NodeId, video: Boolean) = post { calls.call(peer, video) }
    fun acceptCall(video: Boolean) = post { calls.accept(video) }
    fun declineCall() = post { calls.decline() }
    fun hangup() = post { calls.hangup() }
    fun dismissCall() = post { calls.dismiss() }
    /** Debug builds only: see [CallManager.preview]. */
    fun previewCall(peer: NodeId, phase: CallPhase, video: Boolean, ringing: Boolean) =
        post { calls.preview(peer, phase, video, ringing) }

    /** Live media out (from the app's call engine, any thread). */
    fun sendCallMedia(frame: ByteArray) = post { calls.sendMedia(frame) }

    /** Probes the path to [dst]: every relay reports itself, then the destination answers. */
    fun trace(dst: NodeId) = post {
        val now = clock()
        if (!running || !router.hasRoute(dst)) {
            _trace.value = TraceResult(dst, 0, now, done = true, noRoute = true)
            return@post
        }
        val id = Crypto.randomLong()
        _trace.value = TraceResult(dst, id, now)
        router.sendUnicast(dst, PacketType.TRACE, ByteWriter(8).long(id).toByteArray())
        log("Dò đường tới ${dst.short}")
    }

    private fun handleTrace(packet: Packet) {
        val reader = try { ByteReader(packet.payload) } catch (e: RuntimeException) { return }
        val traceId = try { reader.long() } catch (e: RuntimeException) { return }
        when (packet.type) {
            // Someone is probing us: answer so they know the trace arrived, and at which hop.
            PacketType.TRACE -> router.sendUnicast(
                packet.src, PacketType.TRACE_REPLY, ByteWriter(12).long(traceId).int(packet.hops + 1).toByteArray(),
            )
            PacketType.TRACE_HOP, PacketType.TRACE_REPLY -> {
                val current = _trace.value ?: return
                if (current.traceId != traceId || current.done) return
                val index = try { reader.int() } catch (e: RuntimeException) { return }
                if (index !in 1..64) return
                val hop = TraceHop(index, packet.src, clock() - current.startedAt)
                val hops = (current.hops.filter { it.index != index } + hop).sortedBy { it.index }
                val reached = packet.type == PacketType.TRACE_REPLY && packet.src == current.target
                _trace.value = current.copy(hops = hops, reached = current.reached || reached, done = reached)
                if (reached) log("Dò đường xong: ${index} bước, khứ hồi ${hop.rttMs} ms")
            }
            else -> Unit
        }
    }

    fun setDisplayName(name: String) = post {
        router.displayName = name
        if (running) router.announceNow()
    }

    /** Writes `collection/key` (null deletes) and floods the op to the mesh. */
    fun put(collection: String, key: String, value: String?) = post {
        val op = replica.put(collection, key, value)
        _dataVersion.update { it + 1 }
        if (running) router.broadcast(PacketType.CRDT_OPS, CrdtOp.encodeList(listOf(op)))
    }

    fun sendDirect(peer: NodeId, text: String) = post { messenger.send(peer, text) }

    /** Voice note or video clip; [preview] is a waveform (voice) or small JPEG (video). */
    fun sendAttachment(peer: NodeId, kind: DmKind, bytes: ByteArray, caption: String, durationMs: Int, preview: ByteArray?) = post {
        try {
            messenger.sendAttachment(peer, kind, bytes, caption, durationMs, preview)
        } catch (e: Exception) {
            log("Không gửi được tệp: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** [jpeg] should already be compressed; at most [DirectMessenger.MAX_IMAGE_BYTES]. */
    fun sendImage(peer: NodeId, jpeg: ByteArray, caption: String) = post {
        try {
            messenger.sendImage(peer, jpeg, caption)
        } catch (e: Exception) {
            log("Không gửi được ảnh: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun conversation(peer: NodeId): List<DirectMessage> = messenger.conversation(peer)

    fun conversations(): List<DirectMessage> = messenger.conversations()

    fun peerName(id: NodeId): String? = router.peer(id)?.name

    // ---- internals -----------------------------------------------------------------------------

    private fun sendDigest(link: String) {
        router.sendToLink(link, PacketType.SYNC_DIGEST, VersionVector.encode(replica.store.versionVector()))
    }

    private fun handleDigest(packet: Packet, link: String) {
        val remote = VersionVector.decode(packet.payload) ?: return
        val missing = replica.store.missingFor(remote)
        if (missing.isNotEmpty()) {
            log("Đồng bộ: gửi ${missing.size} thao tác cho hàng xóm")
            CrdtOp.encodeChunks(missing, SYNC_CHUNK_BYTES).forEach { router.sendToLink(link, PacketType.SYNC_OPS, it) }
        }
        // Ask back only if they have something we lack; terminates once both sides converge.
        if (replica.store.isBehind(remote)) sendDigest(link)
    }

    private fun mergeOps(packet: Packet, fromLink: String) {
        val ops = CrdtOp.decodeList(packet.payload) ?: return
        val fresh = replica.merge(ops)
        if (fresh.isEmpty()) return
        _dataVersion.update { it + 1 }
        if (packet.type == PacketType.SYNC_OPS) {
            log("Đồng bộ: nhận ${fresh.size} thao tác mới")
            // Flooded ops already reach everyone; caught-up ops must be offered onward explicitly.
            digestDue += router.links().filter { it != fromLink }
        }
    }

    private fun publishStatus() {
        val links = router.links()
        outdated.retainAll(links.toSet())
        _status.value = MeshStatus(running, links.size, outdated.size, router.routes(), router.stats())
    }

    /** Extra sink for log lines (the app mirrors them to logcat). */
    var logSink: ((String) -> Unit)? = null

    fun log(message: String) {
        logSink?.invoke(message)
        val line = LogLine(clock(), message)
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    private fun post(block: () -> Unit) = executor.execute {
        try {
            block()
        } catch (e: Exception) {
            log("Lỗi: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private inner class TransportListener : Transport.Listener {
        override fun onLinkUp(linkId: String) = post {
            if (!running) return@post
            router.linkUp(linkId)
            sendDigest(linkId)
            publishStatus()
        }

        override fun onLinkDown(linkId: String) = post {
            router.linkDown(linkId)
            publishStatus()
        }

        override fun onReceive(linkId: String, bytes: ByteArray) = post {
            if (running) router.receive(linkId, bytes)
        }

        override fun onTransportLog(message: String) = post { log(message) }
    }

    private inner class RouterCallback : MeshRouter.Callback {
        override fun onPacket(packet: Packet, fromLink: String) {
            when (packet.type) {
                PacketType.CRDT_OPS, PacketType.SYNC_OPS -> mergeOps(packet, fromLink)
                PacketType.SYNC_DIGEST -> handleDigest(packet, fromLink)
                PacketType.UNICAST, PacketType.ACK, PacketType.FILE_CHUNK, PacketType.FILE_NACK -> messenger.onPacket(packet)
                PacketType.TRACE, PacketType.TRACE_HOP, PacketType.TRACE_REPLY -> handleTrace(packet)
                PacketType.CALL_SIGNAL, PacketType.CALL_MEDIA -> calls.onPacket(packet)
                PacketType.ANNOUNCE -> Unit
            }
        }

        override fun onTopologyChanged() {
            messenger.flushQueued()
            publishStatus()
        }

        override fun onLog(message: String) = log(message)

        override fun onVersionMismatch(linkId: String, theirVersion: Int) {
            outdated += linkId
            publishStatus()
        }
    }

    companion object {
        const val MAX_LOG_LINES = 300
        const val EARLY_ANNOUNCE_METRES = 25.0
        const val TRACE_TIMEOUT_MS = 15_000L
        const val SYNC_CHUNK_BYTES = 24 * 1024
    }
}
