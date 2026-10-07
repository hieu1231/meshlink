package com.hieu.p2p.mesh.crdt

import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.core.NodeId

/**
 * One write to a LWW register `collection/key`. `value == null` is a tombstone (delete).
 * `(origin, seq)` uniquely identifies the op; seq is dense per origin (1, 2, 3, ...), which lets
 * replicas summarise everything they hold as a compact version vector.
 */
data class CrdtOp(
    val origin: NodeId,
    val seq: Long,
    val collection: String,
    val key: String,
    val value: String?,
    val hlc: Hlc,
) {
    fun encodeTo(w: ByteWriter) {
        w.long(origin.raw).long(seq).string(collection).string(key).nullableString(value)
            .long(hlc.wall).int(hlc.counter)
    }

    companion object {
        fun decodeFrom(r: ByteReader): CrdtOp {
            val origin = NodeId(r.long())
            val seq = r.long()
            val collection = r.string()
            val key = r.string()
            val value = r.nullableString()
            val hlc = Hlc(r.long(), r.int(), origin)
            return CrdtOp(origin, seq, collection, key, value, hlc)
        }

        fun encodeList(ops: List<CrdtOp>): ByteArray {
            val w = ByteWriter(64 * ops.size + 8).int(ops.size)
            ops.forEach { it.encodeTo(w) }
            return w.toByteArray()
        }

        fun decodeList(bytes: ByteArray): List<CrdtOp>? = try {
            val r = ByteReader(bytes)
            val n = r.int()
            require(n in 0..100_000)
            List(n) { decodeFrom(r) }
        } catch (e: RuntimeException) {
            null
        }

        /** Splits ops into encoded batches that each fit in one packet. */
        fun encodeChunks(ops: List<CrdtOp>, maxBytes: Int): List<ByteArray> {
            val chunks = mutableListOf<ByteArray>()
            var batch = mutableListOf<CrdtOp>()
            var size = 4
            for (op in ops) {
                val opSize = ByteWriter().also { op.encodeTo(it) }.size
                if (batch.isNotEmpty() && size + opSize > maxBytes) {
                    chunks += encodeList(batch)
                    batch = mutableListOf()
                    size = 4
                }
                batch += op
                size += opSize
            }
            if (batch.isNotEmpty()) chunks += encodeList(batch)
            return chunks
        }
    }
}

/** Version vector: per origin, the highest seq such that all ops 1..seq are held. */
object VersionVector {
    fun encode(vv: Map<NodeId, Long>): ByteArray {
        val w = ByteWriter(4 + vv.size * 16).int(vv.size)
        vv.forEach { (node, seq) -> w.long(node.raw).long(seq) }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<NodeId, Long>? = try {
        val r = ByteReader(bytes)
        val n = r.int()
        require(n in 0..r.remaining / 16)
        HashMap<NodeId, Long>(n).apply { repeat(n) { put(NodeId(r.long()), r.long()) } }
    } catch (e: RuntimeException) {
        null
    }
}
