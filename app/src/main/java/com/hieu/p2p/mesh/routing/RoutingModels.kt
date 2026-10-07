package com.hieu.p2p.mesh.routing

import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.NodeStatus
import java.security.PublicKey

data class MeshConfig(
    /** Max relay hops for flooded/routed packets. */
    val maxTtl: Int = 12,
    val baseAnnounceIntervalMs: Long = 10_000,
    val maxAnnounceIntervalMs: Long = 60_000,
    /** Extra announce delay per known node, so large meshes self-throttle control traffic. */
    val announceBackoffPerNodeMs: Long = 100,
    /** A route lives for this many announce intervals without a refresh. */
    val routeLifetimeIntervals: Int = 3,
    val routeGraceMs: Long = 5_000,
    val seenCacheSize: Int = 8192,
    val pendingMax: Int = 256,
    val pendingTimeoutMs: Long = 30_000,
    /** Anti-entropy digest period towards each neighbour. */
    val syncIntervalMs: Long = 20_000,
)

/** Public keys and display name of a node, learned from its signed announcements. */
data class PeerInfo(
    val id: NodeId,
    val name: String,
    val signKey: PublicKey,
    val dhKey: PublicKey,
    val status: NodeStatus = NodeStatus(),
)

data class RouteInfo(
    val dest: NodeId,
    val name: String,
    val hops: Int,
    val viaNode: NodeId?,
    val lastSeen: Long,
    /** Last GPS fix the node shared, if any. */
    val position: GeoPoint? = null,
    val batteryPercent: Int = -1,
) {
    val isNeighbor: Boolean get() = hops == 1
}

data class RouterStats(
    val sent: Long = 0,
    val received: Long = 0,
    val relayed: Long = 0,
    val delivered: Long = 0,
    val duplicates: Long = 0,
    val rejected: Long = 0,
)

/** Bounded insertion-ordered set of recently seen packet ids, the core of loop-free flooding. */
internal class SeenCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<Long, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?) = size > capacity
    }

    operator fun contains(id: Long) = map.containsKey(id)

    /** @return true if [id] was not seen before. */
    fun add(id: Long): Boolean = map.put(id, Unit) == null
}
