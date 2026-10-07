package com.hieu.p2p.mesh.dm

import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.crypto.Identity
import com.hieu.p2p.mesh.routing.MeshRouter
import com.hieu.p2p.mesh.routing.PeerInfo
import java.security.MessageDigest

enum class DmStatus {
    /** No route to the recipient yet; held locally (store-and-forward). */
    QUEUED,
    /** Handed to the mesh, waiting for the end-to-end ACK. */
    SENT,
    DELIVERED,
    /** Incoming attachment whose slices are still arriving. */
    RECEIVING,
    RECEIVED,
}

/** Wire value is the ordinal: never reorder, only append. */
enum class DmKind(val maxBytes: Int) {
    TEXT(0),
    IMAGE(512 * 1024),
    /** AAC voice note, ~3 KB per second. */
    VOICE(512 * 1024),
    /** Short clip re-encoded to ~360p. */
    VIDEO(2 * 1024 * 1024);

    val hasAttachment: Boolean get() = this != TEXT
}

data class DirectMessage(
    val id: Long,
    val peer: NodeId,
    val outgoing: Boolean,
    /** Message body, or the caption of an image. */
    val text: String,
    val timestamp: Long,
    val status: DmStatus,
    val attempts: Int = 0,
    val lastAttempt: Long = 0,
    val kind: DmKind = DmKind.TEXT,
    /** Size of the attachment (image, voice or video) in bytes. */
    val imageBytes: Int = 0,
    val chunks: Int = 0,
    /** Slices received so far (incoming attachments). */
    val progress: Int = 0,
    /** Length of a voice note or video. */
    val durationMs: Int = 0,
    /** Small preview sent ahead of the slices: waveform for voice, JPEG thumbnail for video. */
    val preview: ByteArray? = null,
)

interface MessageStore {
    fun loadAll(): List<DirectMessage>
    fun upsert(message: DirectMessage)
}

class InMemoryMessageStore : MessageStore {
    private val map = LinkedHashMap<Long, DirectMessage>()
    @Synchronized override fun loadAll() = map.values.toList()
    @Synchronized override fun upsert(message: DirectMessage) { map[message.id] = message }
}

/** Where image bytes live, keyed by message id (files on Android, memory in tests). */
interface AttachmentStore {
    fun save(id: Long, bytes: ByteArray)
    fun load(id: Long): ByteArray?
}

class InMemoryAttachmentStore : AttachmentStore {
    private val map = HashMap<Long, ByteArray>()
    @Synchronized override fun save(id: Long, bytes: ByteArray) { map[id] = bytes }
    @Synchronized override fun load(id: Long): ByteArray? = map[id]
}

/**
 * End-to-end encrypted direct messages over the mesh.
 *
 * Key: HKDF-SHA256(ECDH(myDhKey, peerDhKey)), bound to both node ids. Each message is sealed with
 * AES-256-GCM; the header (sender, recipient, message id) is authenticated as AAD, so relays can
 * route the packet but cannot read, alter or re-address it. Delivery is confirmed with an ACK and
 * retried with exponential backoff until then.
 *
 * Images: the UNICAST message carries the image's size, slice count and SHA-256; the bytes follow
 * as separately sealed FILE_CHUNK packets. The receiver asks for missing slices with FILE_NACK and
 * ACKs only once the reassembled image matches the hash. A re-sent header doubles as a poll: the
 * receiver answers with an ACK (complete) or a NACK (what is missing).
 */
class DirectMessenger(
    private val identity: Identity,
    private val router: MeshRouter,
    private val store: MessageStore,
    private val clock: () -> Long,
    private val attachments: AttachmentStore = InMemoryAttachmentStore(),
    private val keys: SessionKeys = SessionKeys(identity),
) {
    private class ImageMeta(val size: Int, val chunks: Int, val sha256: ByteArray, val durationMs: Int, val preview: ByteArray)

    private class Incoming(val peer: NodeId) {
        var meta: ImageMeta? = null
        val slices = HashMap<Int, ByteArray>()
        var lastActivity = 0L
        var lastNack = 0L
    }

    private val messages = LinkedHashMap<Long, DirectMessage>()
    private val incoming = HashMap<Long, Incoming>()

    var onChanged: () -> Unit = {}
    var onLog: (String) -> Unit = {}

    init {
        store.loadAll().forEach { messages[it.id] = it }
    }

    @Synchronized
    fun conversation(peer: NodeId): List<DirectMessage> =
        messages.values.filter { it.peer == peer }.sortedBy { it.timestamp }

    /** Latest message of each conversation, newest first. */
    @Synchronized
    fun conversations(): List<DirectMessage> =
        messages.values.groupBy { it.peer }.map { (_, list) -> list.maxBy { it.timestamp } }
            .sortedByDescending { it.timestamp }

    fun send(peer: NodeId, text: String): DirectMessage {
        val message = DirectMessage(Crypto.randomLong(), peer, true, text, clock(), DmStatus.QUEUED)
        save(message)
        transmit(message)
        return message
    }

    fun sendImage(peer: NodeId, bytes: ByteArray, caption: String): DirectMessage =
        sendAttachment(peer, DmKind.IMAGE, bytes, caption)

    /**
     * Sends a file attached to a message. [preview] travels inside the (small, encrypted) header so
     * the recipient sees a waveform or a thumbnail immediately, while the slices are still coming.
     */
    fun sendAttachment(
        peer: NodeId, kind: DmKind, bytes: ByteArray, caption: String, durationMs: Int = 0, preview: ByteArray? = null,
    ): DirectMessage {
        require(kind.hasAttachment) { "no attachment for $kind" }
        require(bytes.isNotEmpty() && bytes.size <= kind.maxBytes) { "$kind size ${bytes.size}" }
        require((preview?.size ?: 0) <= MAX_PREVIEW_BYTES) { "preview too large" }
        val id = Crypto.randomLong()
        attachments.save(id, bytes)
        val chunks = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        val message = DirectMessage(
            id, peer, true, caption, clock(), DmStatus.QUEUED,
            kind = kind, imageBytes = bytes.size, chunks = chunks, durationMs = durationMs, preview = preview,
        )
        save(message)
        onLog("${label(kind)} ${bytes.size / 1024} KB, $chunks mảnh, gửi tới ${peer.short}")
        transmit(message)
        return message
    }

    /** Topology changed: messages waiting for a route may now be deliverable. */
    fun flushQueued() {
        snapshot().filter { it.outgoing && it.status == DmStatus.QUEUED }.forEach { transmit(it) }
    }

    fun tick() {
        val now = clock()
        snapshot()
            .filter { it.outgoing && (it.status == DmStatus.QUEUED || it.status == DmStatus.SENT) }
            .filter { now - it.lastAttempt >= retryDelay(it) }
            .forEach { transmit(it) }

        // Receiver side: transfers that stalled ask for their missing slices.
        for ((id, transfer) in incoming.toList()) {
            if (now - transfer.lastActivity > INCOMING_TIMEOUT_MS) {
                incoming.remove(id)
                continue
            }
            val meta = transfer.meta ?: continue
            if (now - transfer.lastActivity > STALL_MS && now - transfer.lastNack > STALL_MS) {
                requestMissing(id, transfer, meta)
            }
        }
    }

    fun onPacket(packet: Packet) {
        when (packet.type) {
            PacketType.UNICAST -> receive(packet)
            PacketType.ACK -> acknowledge(packet)
            PacketType.FILE_CHUNK -> receiveChunk(packet)
            PacketType.FILE_NACK -> resendChunks(packet)
            else -> Unit
        }
    }

    // ---- sending -------------------------------------------------------------------------------

    private fun transmit(message: DirectMessage) {
        val peer = router.peer(message.peer)
        if (peer == null || !router.hasRoute(message.peer)) {
            if (message.kind.hasAttachment && message.attempts == 0 && message.status == DmStatus.QUEUED) {
                onLog("${label(message.kind)} chờ: chưa có đường tới ${message.peer.short}")
            }
            return
        }
        val body = ByteWriter().byte(message.kind.ordinal).long(message.timestamp).string(message.text)
        var image: ByteArray? = null
        if (message.kind.hasAttachment) {
            image = attachments.load(message.id) ?: return
            body.int(image.size).int(message.chunks).raw(sha256(image))
                .int(message.durationMs).bytes(message.preview ?: ByteArray(0))
        }
        val sealed = Crypto.seal(sessionKey(peer), body.toByteArray(), aad(identity.nodeId, message.peer, message.id))
        val payload = ByteWriter(sealed.size + 16).long(message.id).bytes(sealed).toByteArray()
        if (!router.sendUnicast(message.peer, PacketType.UNICAST, payload)) return
        // Slices go out with the first header only; later headers are polls answered by ACK/NACK.
        if (image != null && message.attempts == 0) {
            for (i in 0 until message.chunks) sendChunk(peer, message.id, image, i)
            onLog("Đã đẩy ${message.chunks} mảnh vào mạng lưới")
        } else if (image != null) {
            onLog("Hỏi lại người nhận ${label(message.kind).lowercase()} (lần ${message.attempts})")
        }
        val status = if (message.status == DmStatus.QUEUED) DmStatus.SENT else message.status
        save(message.copy(status = status, attempts = message.attempts + 1, lastAttempt = clock()))
    }

    private fun sendChunk(peer: PeerInfo, id: Long, image: ByteArray, index: Int) {
        val from = index * CHUNK_BYTES
        val slice = image.copyOfRange(from, minOf(image.size, from + CHUNK_BYTES))
        val sealed = Crypto.seal(sessionKey(peer), slice, chunkAad(identity.nodeId, peer.id, id, index))
        router.sendUnicast(
            peer.id, PacketType.FILE_CHUNK,
            ByteWriter(sealed.size + 20).long(id).int(index).bytes(sealed).toByteArray(),
        )
    }

    private fun resendChunks(packet: Packet) {
        val (id, missing) = try {
            val r = ByteReader(packet.payload)
            val id = r.long()
            val n = r.int()
            require(n in 0..MAX_NACK)
            id to List(n) { r.int() }
        } catch (e: RuntimeException) {
            return
        }
        val message = synchronized(this) { messages[id] } ?: return
        if (!message.outgoing || message.peer != packet.src || message.status == DmStatus.DELIVERED) return
        val peer = router.peer(packet.src) ?: return
        val image = attachments.load(id) ?: return
        missing.filter { it in 0 until message.chunks }.forEach { sendChunk(peer, id, image, it) }
        onLog("Gửi bù ${missing.size} mảnh ảnh bị rớt")
        save(message.copy(lastAttempt = clock()))
    }

    // ---- receiving -----------------------------------------------------------------------------

    private fun receive(packet: Packet) {
        val peer = router.peer(packet.src) ?: return
        val parsed = try {
            val r = ByteReader(packet.payload)
            r.long() to r.bytes(Packet.MAX_PAYLOAD)
        } catch (e: RuntimeException) {
            return
        }
        val (id, sealed) = parsed
        val plain = Crypto.open(sessionKey(peer), sealed, aad(packet.src, identity.nodeId, id)) ?: return
        val existing = synchronized(this) { messages[id] }
        try {
            val r = ByteReader(plain)
            val kind = DmKind.entries.getOrNull(r.byte()) ?: return
            val sentAt = r.long()
            val text = r.string()
            if (kind == DmKind.TEXT) {
                if (existing == null) save(DirectMessage(id, packet.src, false, text, sentAt, DmStatus.RECEIVED))
                ack(packet.src, id)
                return
            }
            val meta = ImageMeta(r.int(), r.int(), r.raw(32), r.int(), r.bytes(MAX_PREVIEW_BYTES))
            if (meta.size !in 1..kind.maxBytes || meta.chunks != (meta.size + CHUNK_BYTES - 1) / CHUNK_BYTES) return
            if (existing?.status == DmStatus.RECEIVED) {
                ack(packet.src, id)
                return
            }
            if (existing == null) {
                save(
                    DirectMessage(
                        id, packet.src, false, text, sentAt, DmStatus.RECEIVING,
                        kind = kind, imageBytes = meta.size, chunks = meta.chunks,
                        durationMs = meta.durationMs.coerceAtLeast(0), preview = meta.preview.takeIf { it.isNotEmpty() },
                    )
                )
            }
            val transfer = incoming.getOrPut(id) { Incoming(packet.src) }
            val isPoll = transfer.meta != null || existing != null
            transfer.meta = meta
            transfer.lastActivity = clock()
            if (!tryComplete(id, transfer) && isPoll) requestMissing(id, transfer, meta)
        } catch (e: RuntimeException) {
            return
        }
    }

    private fun receiveChunk(packet: Packet) {
        val peer = router.peer(packet.src) ?: return
        val (id, index, sealed) = try {
            val r = ByteReader(packet.payload)
            Triple(r.long(), r.int(), r.bytes(Packet.MAX_PAYLOAD))
        } catch (e: RuntimeException) {
            return
        }
        val existing = synchronized(this) { messages[id] }
        if (existing?.status == DmStatus.RECEIVED) return
        val transfer = incoming[id] ?: run {
            // Slice overtook its header (flooded path). Keep a bounded number of such transfers.
            if (incoming.size >= MAX_INCOMING) return
            Incoming(packet.src).also { incoming[id] = it }
        }
        if (transfer.peer != packet.src || index < 0 || index >= MAX_CHUNKS) return
        val slice = Crypto.open(sessionKey(peer), sealed, chunkAad(packet.src, identity.nodeId, id, index)) ?: return
        transfer.slices[index] = slice
        transfer.lastActivity = clock()
        if (!tryComplete(id, transfer) && existing != null && transfer.slices.size != existing.progress) {
            save(existing.copy(progress = transfer.slices.size))
        }
    }

    private fun tryComplete(id: Long, transfer: Incoming): Boolean {
        val meta = transfer.meta ?: return false
        if ((0 until meta.chunks).any { it !in transfer.slices }) return false
        val out = java.io.ByteArrayOutputStream(meta.size)
        for (i in 0 until meta.chunks) out.write(transfer.slices.getValue(i))
        val bytes = out.toByteArray()
        if (bytes.size != meta.size || !sha256(bytes).contentEquals(meta.sha256)) {
            onLog("Ảnh ghép lại sai mã kiểm tra, nhận lại từ đầu")
            transfer.slices.clear() // corrupted: start over
            return false
        }
        attachments.save(id, bytes)
        incoming.remove(id)
        onLog("Nhận đủ tệp ${meta.size / 1024} KB từ ${transfer.peer.short}")
        val message = synchronized(this) { messages[id] } ?: return true
        save(message.copy(status = DmStatus.RECEIVED, progress = meta.chunks))
        ack(transfer.peer, id)
        return true
    }

    private fun requestMissing(id: Long, transfer: Incoming, meta: ImageMeta) {
        val missing = (0 until meta.chunks).filter { it !in transfer.slices }.take(MAX_NACK)
        if (missing.isEmpty()) return
        transfer.lastNack = clock()
        val w = ByteWriter(12 + missing.size * 4).long(id).int(missing.size)
        missing.forEach { w.int(it) }
        router.sendUnicast(transfer.peer, PacketType.FILE_NACK, w.toByteArray())
    }

    private fun ack(to: NodeId, id: Long) {
        // Always ACK, even duplicates: the previous ACK may have been lost.
        router.sendUnicast(to, PacketType.ACK, ByteWriter().long(id).toByteArray())
    }

    private fun acknowledge(packet: Packet) {
        val id = try { ByteReader(packet.payload).long() } catch (e: RuntimeException) { return }
        val message = synchronized(this) { messages[id] } ?: return
        if (message.outgoing && message.peer == packet.src && message.status != DmStatus.DELIVERED) {
            if (message.kind.hasAttachment) onLog("${label(message.kind)} đã đến nơi")
            save(message.copy(status = DmStatus.DELIVERED))
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun sessionKey(peer: PeerInfo): ByteArray = keys.of(peer)

    private fun aad(from: NodeId, to: NodeId, id: Long) =
        ByteWriter(24).long(from.raw).long(to.raw).long(id).toByteArray()

    private fun chunkAad(from: NodeId, to: NodeId, id: Long, index: Int) =
        ByteWriter(28).long(from.raw).long(to.raw).long(id).int(index).toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Attachments get time for their slices to cross the mesh before the first poll. */
    private fun retryDelay(m: DirectMessage): Long =
        backoff(m.attempts) + if (m.kind.hasAttachment && m.attempts == 1) minOf(m.chunks * SLICE_GRACE_MS, MAX_GRACE_MS) else 0

    private fun label(kind: DmKind) = when (kind) {
        DmKind.IMAGE -> "Ảnh"
        DmKind.VOICE -> "Tin thoại"
        DmKind.VIDEO -> "Video"
        DmKind.TEXT -> "Tin"
    }

    private fun backoff(attempts: Int): Long =
        if (attempts == 0) 0 else minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(attempts - 1, 6))

    @Synchronized
    private fun snapshot() = messages.values.toList()

    private fun save(message: DirectMessage) {
        synchronized(this) { messages[message.id] = message }
        store.upsert(message)
        onChanged()
    }

    companion object {
        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        /** Slice size: well under one packet, small enough that a lost slice is cheap to resend. */
        const val CHUNK_BYTES = 16 * 1024
        val MAX_IMAGE_BYTES = DmKind.IMAGE.maxBytes
        /** Waveform or thumbnail riding in the header packet. */
        const val MAX_PREVIEW_BYTES = 12 * 1024
        private val MAX_CHUNKS = DmKind.VIDEO.maxBytes / CHUNK_BYTES
        private val MAX_NACK = MAX_CHUNKS
        private const val MAX_INCOMING = 8
        private const val STALL_MS = 4_000L
        private const val SLICE_GRACE_MS = 1_000L
        private const val MAX_GRACE_MS = 60_000L
        private const val INCOMING_TIMEOUT_MS = 10 * 60_000L
    }
}
