package com.hieu.p2p.mesh.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Compact big-endian binary writer used by every wire format in the protocol. */
class ByteWriter(initialCapacity: Int = 64) {
    private val buffer = ByteArrayOutputStream(initialCapacity)
    private val out = DataOutputStream(buffer)

    fun byte(v: Int) = apply { out.writeByte(v) }
    fun int(v: Int) = apply { out.writeInt(v) }
    fun long(v: Long) = apply { out.writeLong(v) }
    fun raw(v: ByteArray) = apply { out.write(v) }
    fun bytes(v: ByteArray) = apply { out.writeInt(v.size); out.write(v) }
    fun string(v: String) = bytes(v.toByteArray(Charsets.UTF_8))
    fun nullableString(v: String?) = apply {
        if (v == null) byte(0) else { byte(1); string(v) }
    }

    val size: Int get() = buffer.size()

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/** Reader counterpart of [ByteWriter]. Throws on malformed input; callers wrap decoding in try/catch. */
class ByteReader(data: ByteArray) {
    private val buf = ByteBuffer.wrap(data)

    val remaining: Int get() = buf.remaining()

    fun byte(): Int = buf.get().toInt() and 0xFF
    fun int(): Int = buf.int
    fun long(): Long = buf.long
    fun raw(n: Int): ByteArray {
        require(n in 0..buf.remaining()) { "bad length $n" }
        return ByteArray(n).also { buf.get(it) }
    }
    fun bytes(max: Int = Int.MAX_VALUE): ByteArray {
        val n = buf.int
        require(n <= max) { "field too large: $n" }
        return raw(n)
    }
    fun string(): String = String(bytes(), Charsets.UTF_8)
    fun nullableString(): String? = if (byte() == 0) null else string()
}
