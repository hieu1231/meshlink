package com.hieu.p2p.mesh.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A GPS fix as it travels in the mesh: microdegrees (~0.1 m resolution) in two ints.
 * [fixedAt] is in the local clock of whoever holds the value; on the wire only the fix's
 * age is sent, so phones with skewed clocks still agree on how fresh a position is.
 */
data class GeoPoint(
    val latE6: Int,
    val lonE6: Int,
    /** 68% confidence radius in metres, as reported by the GPS. */
    val accuracyM: Int,
    val fixedAt: Long,
) {
    val lat: Double get() = latE6 / 1e6
    val lon: Double get() = lonE6 / 1e6

    /** Great-circle distance in metres (haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing to [other], degrees clockwise from true north, 0..360. */
    fun bearingTo(other: GeoPoint): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(other.lat)
        val dLon = Math.toRadians(other.lon - lon)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Combined uncertainty of the line between two fixes, in metres. */
    fun uncertaintyTo(other: GeoPoint): Int = maxOf(accuracyM + other.accuracyM, MIN_RESOLVABLE_M)

    /**
     * Whether the direction to [other] means anything. When the two phones are closer together
     * than their GPS errors add up to, the computed bearing is noise (it can point the opposite
     * way), so callers must say "very close" instead of drawing an arrow.
     */
    fun directionReliableTo(other: GeoPoint): Boolean = distanceTo(other) > uncertaintyTo(other)

    companion object {
        /** Even with perfect fixes, phone GPS cannot resolve direction at a few metres. */
        const val MIN_RESOLVABLE_M = 8
        const val EARTH_RADIUS_M = 6_371_008.8

        fun of(lat: Double, lon: Double, accuracyM: Float, fixedAt: Long) = GeoPoint(
            Math.round(lat * 1e6).toInt(), Math.round(lon * 1e6).toInt(),
            accuracyM.toInt().coerceIn(0, 65_535), fixedAt,
        )
    }
}

/** What a node says about itself besides keys and name. */
data class NodeStatus(val position: GeoPoint? = null, val batteryPercent: Int = -1)
