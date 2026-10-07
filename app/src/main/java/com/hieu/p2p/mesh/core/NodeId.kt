package com.hieu.p2p.mesh.core

import java.security.MessageDigest

/**
 * 64-bit node address. Self-certifying: derived from SHA-256 of the node's signing public key,
 * so nobody can claim an address without owning the matching private key.
 */
@JvmInline
value class NodeId(val raw: Long) {
    val hex: String get() = java.lang.Long.toHexString(raw).padStart(16, '0')

    /** Short human-friendly form, e.g. "A1B2-C3D4". */
    val short: String get() = hex.substring(0, 4).uppercase() + "-" + hex.substring(4, 8).uppercase()

    val isBroadcast: Boolean get() = raw == 0L

    override fun toString(): String = hex

    companion object {
        val BROADCAST = NodeId(0L)

        fun fromHex(hex: String): NodeId = NodeId(java.lang.Long.parseUnsignedLong(hex, 16))

        fun fromPublicKey(encoded: ByteArray): NodeId {
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (digest[i].toLong() and 0xFF)
            return NodeId(if (v == 0L) 1L else v)
        }
    }
}
