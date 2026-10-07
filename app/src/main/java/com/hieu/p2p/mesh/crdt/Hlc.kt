package com.hieu.p2p.mesh.crdt

import com.hieu.p2p.mesh.core.NodeId

/**
 * Hybrid Logical Clock timestamp. Totally ordered by (wall, counter, node), so concurrent
 * writes resolve identically on every replica without any coordination.
 */
data class Hlc(val wall: Long, val counter: Int, val node: NodeId) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int = when {
        wall != other.wall -> wall.compareTo(other.wall)
        counter != other.counter -> counter.compareTo(other.counter)
        else -> node.raw.compareTo(other.node.raw)
    }
}

class HlcClock(private val node: NodeId, private val clock: () -> Long) {
    private var wall = 0L
    private var counter = 0

    @Synchronized
    fun now(): Hlc {
        val physical = clock()
        if (physical > wall) {
            wall = physical
            counter = 0
        } else {
            counter++
        }
        return Hlc(wall, counter, node)
    }

    /** Merge a remote timestamp so our next local event orders after it (causality). */
    @Synchronized
    fun observe(remote: Hlc) {
        val physical = clock()
        // A phone with a wildly wrong date must not drag every clock in the mesh into the future.
        if (remote.wall > physical + MAX_DRIFT_MS) return
        val newWall = maxOf(wall, remote.wall, physical)
        counter = when {
            newWall == wall && newWall == remote.wall -> maxOf(counter, remote.counter) + 1
            newWall == wall -> counter + 1
            newWall == remote.wall -> remote.counter + 1
            else -> 0
        }
        wall = newWall
    }

    companion object {
        const val MAX_DRIFT_MS = 24 * 60 * 60 * 1000L
    }
}
