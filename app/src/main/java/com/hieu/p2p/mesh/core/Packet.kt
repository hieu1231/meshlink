package com.hieu.p2p.mesh.core

enum class PacketType(val code: Int) {
    /** Signed originator announcement: builds routing tables and distributes public keys. */
    ANNOUNCE(1),
    /** End-to-end encrypted direct message, routed hop-by-hop. */
    UNICAST(2),
    /** Delivery receipt for a [UNICAST] message. */
    ACK(3),
    /** Freshly created CRDT operations, flooded mesh-wide. */
    CRDT_OPS(4),
    /** Single-hop anti-entropy: "this is my version vector". */
    SYNC_DIGEST(5),
    /** Single-hop anti-entropy: "here are the ops you are missing". */
    SYNC_OPS(6),
    /** One encrypted slice of an image attached to a direct message, routed like UNICAST. */
    FILE_CHUNK(7),
    /** Receiver to sender: "these slices are still missing". */
    FILE_NACK(8),
    /** Route probe towards a destination (traceroute). Relays answer with TRACE_HOP. */
    TRACE(9),
    /** A relay telling the prober "I am hop k on your trace". Signed by the relay itself. */
    TRACE_HOP(10),
    /** The destination answering a probe: the trace reached it. */
    TRACE_REPLY(11),
    /** Call set-up and tear-down (invite, ringing, accept, reject, end). Signed, E2E encrypted. */
    CALL_SIGNAL(12),
    /**
     * Live audio/video. Unsigned for speed (tens per second): sealed with the per-call key instead,
     * routed only along the existing route, never flooded, never retransmitted.
     */
    CALL_MEDIA(13);

    companion object {
        fun of(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Wire layout (big-endian):
 * ```
 * magic(1) version(1) type(1) ttl(1) hops(1) id(8) src(8) dst(8) timestamp(8)
 * payloadLen(4) payload(n) sigLen(1) signature(m)
 * ```
 * `ttl` and `hops` change at every relay, so they are excluded from the signature.
 */
class Packet(
    val type: PacketType,
    val id: Long,
    val src: NodeId,
    val dst: NodeId,
    val ttl: Int,
    val hops: Int,
    val timestamp: Long,
    val payload: ByteArray,
    val signature: ByteArray = EMPTY,
) {
    val isBroadcast: Boolean get() = dst.isBroadcast

    fun signable(): ByteArray = ByteWriter(payload.size + 40)
        .byte(type.code).long(id).long(src.raw).long(dst.raw).long(timestamp).raw(payload)
        .toByteArray()

    fun withSignature(sig: ByteArray) = Packet(type, id, src, dst, ttl, hops, timestamp, payload, sig)

    /** Copy as it leaves this node towards the next hop. */
    fun relayed() = Packet(type, id, src, dst, ttl - 1, hops + 1, timestamp, payload, signature)

    fun encode(): ByteArray = ByteWriter(payload.size + HEADER_SIZE + signature.size)
        .byte(MAGIC).byte(VERSION).byte(type.code).byte(ttl).byte(hops)
        .long(id).long(src.raw).long(dst.raw).long(timestamp)
        .bytes(payload)
        .byte(signature.size).raw(signature)
        .toByteArray()

    companion object {
        const val MAGIC = 0x4D
        /** v2 images, v3 position/battery, v4 traceroute, v5 voice/video, v6 calls. Bump on any wire change. */
        const val VERSION = 6
        const val HEADER_SIZE = 42
        /** Keeps encoded packets under the 32 KiB limit of a Nearby BYTES payload. */
        const val MAX_PAYLOAD = 30 * 1024
        private val EMPTY = ByteArray(0)

        /** Protocol version of a frame from this protocol family, or null if it is not one. */
        fun peekVersion(bytes: ByteArray): Int? =
            if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == MAGIC) bytes[1].toInt() and 0xFF else null

        fun decode(bytes: ByteArray): Packet? = try {
            val r = ByteReader(bytes)
            if (r.byte() != MAGIC || r.byte() != VERSION) null
            else {
                val type = PacketType.of(r.byte())
                val ttl = r.byte()
                val hops = r.byte()
                val id = r.long()
                val src = NodeId(r.long())
                val dst = NodeId(r.long())
                val ts = r.long()
                val payload = r.bytes(MAX_PAYLOAD)
                val sig = r.raw(r.byte())
                if (type == null || r.remaining != 0) null
                else Packet(type, id, src, dst, ttl, hops, ts, payload, sig)
            }
        } catch (e: RuntimeException) {
            null
        }
    }
}
