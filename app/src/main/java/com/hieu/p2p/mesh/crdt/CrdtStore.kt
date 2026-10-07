package com.hieu.p2p.mesh.crdt

import com.hieu.p2p.mesh.core.NodeId
import java.util.TreeMap

/**
 * Operation-based LWW-Map CRDT with a full op log for anti-entropy.
 *
 * [apply] is commutative, associative and idempotent: replicas that have received the same set
 * of ops — in any order, with any duplication — hold identical state.
 */
class CrdtStore {
    data class Entry(val key: String, val value: String, val hlc: Hlc)

    private class Register(val value: String?, val hlc: Hlc)

    private val registers = HashMap<String, HashMap<String, Register>>()
    private val log = HashMap<NodeId, TreeMap<Long, CrdtOp>>()
    private val contiguous = HashMap<NodeId, Long>()

    /** @return true if the op was new to this replica. */
    @Synchronized
    fun apply(op: CrdtOp): Boolean {
        val perOrigin = log.getOrPut(op.origin) { TreeMap() }
        if (perOrigin.containsKey(op.seq)) return false
        perOrigin[op.seq] = op

        var c = contiguous[op.origin] ?: 0L
        while (perOrigin.containsKey(c + 1)) c++
        contiguous[op.origin] = c

        val collection = registers.getOrPut(op.collection) { HashMap() }
        val current = collection[op.key]
        if (current == null || op.hlc > current.hlc) collection[op.key] = Register(op.value, op.hlc)
        return true
    }

    @Synchronized
    fun versionVector(): Map<NodeId, Long> = HashMap(contiguous)

    /** Ops a replica with version vector [remote] has not got yet. */
    @Synchronized
    fun missingFor(remote: Map<NodeId, Long>): List<CrdtOp> =
        log.flatMap { (origin, ops) -> ops.tailMap(remote[origin] ?: 0L, false).values }

    /** True if [remote] holds ops this replica is missing. */
    @Synchronized
    fun isBehind(remote: Map<NodeId, Long>): Boolean =
        remote.any { (origin, seq) -> seq > (contiguous[origin] ?: 0L) }

    @Synchronized
    fun maxSeq(origin: NodeId): Long = log[origin]?.lastKey() ?: 0L

    @Synchronized
    fun get(collection: String, key: String): String? = registers[collection]?.get(key)?.value

    @Synchronized
    fun entries(collection: String): List<Entry> =
        registers[collection].orEmpty().mapNotNull { (k, r) -> r.value?.let { Entry(k, it, r.hlc) } }

    @Synchronized
    fun opCount(): Int = log.values.sumOf { it.size }
}
