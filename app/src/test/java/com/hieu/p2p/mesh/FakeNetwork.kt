package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.core.Transport
import com.hieu.p2p.mesh.crdt.InMemoryOpLog
import com.hieu.p2p.mesh.crypto.Identity
import com.hieu.p2p.mesh.dm.InMemoryAttachmentStore
import com.hieu.p2p.mesh.dm.InMemoryMessageStore
import java.util.concurrent.Executor

/**
 * Deterministic in-memory radio: links are explicit, frames are queued FIFO and delivered by [drain].
 * Lets tests build arbitrary topologies (chains, rings, partitions) with a controllable clock.
 */
class FakeNetwork {
    var now = 1_700_000_000_000L
    private val queue = ArrayDeque<Triple<String, String, ByteArray>>() // from, to, bytes
    private val transports = LinkedHashMap<String, FakeTransport>()
    val nodes = LinkedHashMap<String, MeshNode>()
    val attachments = LinkedHashMap<String, InMemoryAttachmentStore>()
    /** Optional hook to tamper with frames in flight: return null to drop. */
    var interceptor: ((from: String, to: String, bytes: ByteArray) -> ByteArray?)? = null

    fun node(name: String): MeshNode {
        val transport = FakeTransport(name)
        transports[name] = transport
        val files = InMemoryAttachmentStore().also { attachments[name] = it }
        val node = MeshNode(
            identity = Identity.generate(),
            displayName = name,
            transport = transport,
            opLog = InMemoryOpLog(),
            messageStore = InMemoryMessageStore(),
            executor = Executor { it.run() },
            attachments = files,
            clock = { now },
        )
        nodes[name] = node
        node.start()
        return node
    }

    fun link(a: String, b: String) {
        transports.getValue(a).up(b)
        transports.getValue(b).up(a)
        drain()
    }

    fun unlink(a: String, b: String) {
        transports.getValue(a).down(b)
        transports.getValue(b).down(a)
        drain()
    }

    /** Advance time and let every node run its periodic work. */
    fun advance(ms: Long, step: Long = 1_000) {
        var left = ms
        while (left > 0) {
            val d = minOf(step, left)
            now += d
            left -= d
            nodes.values.forEach { it.tick() }
            drain()
        }
    }

    fun drain(maxFrames: Int = 1_000_000) {
        var n = 0
        while (queue.isNotEmpty()) {
            check(++n <= maxFrames) { "network did not quiesce" }
            val (from, to, bytes) = queue.removeFirst()
            val target = transports.getValue(to)
            if (from !in target.links) continue
            val frame = interceptor?.invoke(from, to, bytes) ?: if (interceptor != null) continue else bytes
            target.listener?.onReceive(from, frame)
        }
    }

    inner class FakeTransport(private val name: String) : Transport {
        var listener: Transport.Listener? = null
        val links = LinkedHashSet<String>()

        override fun start(listener: Transport.Listener) {
            this.listener = listener
        }

        override fun stop() {
            links.toList().forEach { down(it) }
            listener = null
        }

        override fun send(linkId: String, bytes: ByteArray) {
            if (linkId in links) queue.addLast(Triple(name, linkId, bytes))
        }

        fun up(peer: String) {
            if (links.add(peer)) listener?.onLinkUp(peer)
        }

        fun down(peer: String) {
            if (links.remove(peer)) listener?.onLinkDown(peer)
        }
    }
}
