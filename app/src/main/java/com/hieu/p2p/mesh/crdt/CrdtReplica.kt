package com.hieu.p2p.mesh.crdt

import com.hieu.p2p.mesh.core.NodeId

/** Durable append-only storage for CRDT ops. */
interface OpLog {
    fun loadAll(): List<CrdtOp>
    fun append(ops: List<CrdtOp>)
}

class InMemoryOpLog : OpLog {
    private val ops = mutableListOf<CrdtOp>()
    @Synchronized override fun loadAll(): List<CrdtOp> = ops.toList()
    @Synchronized override fun append(ops: List<CrdtOp>) { this.ops += ops }
}

/** Local replica: store + clock + persistence. Creates local ops and merges remote ones. */
class CrdtReplica(private val node: NodeId, clock: () -> Long, private val opLog: OpLog) {
    val store = CrdtStore()
    private val hlc = HlcClock(node, clock)
    private var nextSeq: Long

    init {
        for (op in opLog.loadAll()) {
            store.apply(op)
            hlc.observe(op.hlc)
        }
        nextSeq = store.maxSeq(node) + 1
    }

    @Synchronized
    fun put(collection: String, key: String, value: String?): CrdtOp {
        val op = CrdtOp(node, nextSeq++, collection, key, value, hlc.now())
        store.apply(op)
        opLog.append(listOf(op))
        return op
    }

    /** @return the ops that were new. */
    @Synchronized
    fun merge(ops: List<CrdtOp>): List<CrdtOp> {
        val fresh = ops.filter { store.apply(it) }
        fresh.forEach { hlc.observe(it.hlc) }
        if (fresh.isNotEmpty()) opLog.append(fresh)
        return fresh
    }
}
