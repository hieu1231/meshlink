package com.hieu.p2p.mesh.routing

import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.core.GeoPoint

/**
 * Payload of an ANNOUNCE packet (an "originator message" in BATMAN terms).
 * [intervalMs] tells receivers how often to expect the next one, so route expiry adapts
 * to each node's announce rate instead of using a fixed global timeout.
 *
 * Optional tail (v3): battery level and the sender's GPS fix, sent as its *age* rather than a
 * timestamp so clock skew between phones does not matter.
 */
internal class Announce(
    val seq: Int,
    val intervalMs: Int,
    val signPublic: ByteArray,
    val dhPublic: ByteArray,
    val name: String,
    val batteryPercent: Int = -1,
    val latE6: Int = 0,
    val lonE6: Int = 0,
    val accuracyM: Int = -1,
    /** Age of the fix in seconds when sent; -1 = no position shared. */
    val fixAgeSec: Int = -1,
) {
    val hasPosition: Boolean get() = fixAgeSec >= 0

    fun encode(): ByteArray {
        val w = ByteWriter(280)
            .int(seq).int(intervalMs).bytes(signPublic).bytes(dhPublic).string(name)
            .byte(batteryPercent.coerceIn(-1, 100) and 0xFF)
        if (hasPosition) {
            w.byte(1).int(latE6).int(lonE6).int(accuracyM).int(fixAgeSec)
        } else {
            w.byte(0)
        }
        return w.toByteArray()
    }

    fun position(receivedAt: Long): GeoPoint? =
        if (hasPosition) GeoPoint(latE6, lonE6, accuracyM, receivedAt - fixAgeSec * 1000L) else null

    companion object {
        const val MAX_NAME = 32

        fun decode(bytes: ByteArray): Announce? = try {
            val r = ByteReader(bytes)
            val seq = r.int()
            val interval = r.int()
            val sign = r.bytes(512)
            val dh = r.bytes(512)
            val name = r.string().take(MAX_NAME)
            val battery = r.byte().let { if (it == 0xFF) -1 else it.coerceAtMost(100) }
            if (r.byte() == 1) {
                val lat = r.int()
                val lon = r.int()
                val acc = r.int()
                val age = r.int()
                val valid = lat in -90_000_000..90_000_000 && lon in -180_000_000..180_000_000 && age >= 0
                if (valid) Announce(seq, interval, sign, dh, name, battery, lat, lon, acc, age)
                else Announce(seq, interval, sign, dh, name, battery)
            } else {
                Announce(seq, interval, sign, dh, name, battery)
            }
        } catch (e: RuntimeException) {
            null
        }
    }
}
