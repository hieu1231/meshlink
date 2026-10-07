package com.hieu.p2p.mesh.routing

import com.hieu.p2p.mesh.core.ByteReader
import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.core.NodeStatus
import com.hieu.p2p.mesh.core.Packet
import com.hieu.p2p.mesh.core.PacketType
import com.hieu.p2p.mesh.core.Transport
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.crypto.Identity
import java.util.concurrent.ConcurrentHashMap

/**
 * Proactive distance-vector mesh router (BATMAN-inspired) with flooding fallback.
 *
 * - Every node periodically floods a signed ANNOUNCE. Each receiver records the neighbour link the
 *   announce arrived on as the next hop towards its originator, preferring fresher sequence numbers
 *   and then fewer hops. This yields a routing table without any node knowing the full topology.
 * - Unicast packets follow the routing table hop by hop; if no route is known they are flooded.
 * - Broadcast packets are flooded; a bounded cache of packet ids makes flooding loop-free.
 * - Every packet is signed by its originator. Relays verify before forwarding, so forged or
 *   tampered traffic dies at the first honest hop. Packets from not-yet-known originators are
 *   parked until their announce (carrying the public key) arrives.
 *
 * Not thread-safe: all calls must come from a single thread (see MeshNode). [peer] is the only
 * method safe to call from other threads.
 */
class MeshRouter(
    private val identity: Identity,
    private val transport: Transport,
    private val clock: () -> Long,
    private val config: MeshConfig = MeshConfig(),
) {
    interface Callback {
        /** A verified packet addressed to this node (or broadcast) arrived. */
        fun onPacket(packet: Packet, fromLink: String)
        fun onTopologyChanged() {}
        fun onLog(message: String) {}
        /** A neighbour speaks another protocol version: both phones need the same app version. */
        fun onVersionMismatch(linkId: String, theirVersion: Int) {}
    }

    private class Route(
        var viaLink: String,
        var hops: Int,
        var seq: Int,
        var packetId: Long,
        var lastSeen: Long,
        var expiresAt: Long,
    )

    private class Parked(val packet: Packet, val link: String, val at: Long)

    var callback: Callback? = null
    var displayName: String = ""

    /** Supplies this node's own position/battery each time an announce is built. */
    var localStatus: () -> NodeStatus = { NodeStatus() }

    val me: NodeId get() = identity.nodeId

    private val links = LinkedHashSet<String>()
    private val linkToNode = HashMap<String, NodeId>()
    private val routes = HashMap<NodeId, Route>()
    private val peers = ConcurrentHashMap<NodeId, PeerInfo>()
    private val seen = SeenCache(config.seenCacheSize)
    private val parked = ArrayDeque<Parked>()
    private val mismatchedLinks = HashSet<String>()
    // Seeded from wall-clock seconds so a restarted node's announces are not mistaken for stale ones.
    private var announceSeq = (clock() / 1000).toInt()
    private var lastAnnounceAt = 0L
    private var topologyDirty = false

    private var sent = 0L
    private var received = 0L
    private var relayed = 0L
    private var delivered = 0L
    private var duplicates = 0L
    private var rejected = 0L

    fun stats() = RouterStats(sent, received, relayed, delivered, duplicates, rejected)

    fun links(): List<String> = links.toList()

    fun peer(id: NodeId): PeerInfo? = peers[id]

    fun hasRoute(dst: NodeId): Boolean = liveRoute(dst) != null

    fun routes(): List<RouteInfo> {
        val now = clock()
        return routes.filter { it.value.expiresAt >= now && it.value.viaLink in links }
            .map { (id, r) ->
                val peer = peers[id]
                RouteInfo(
                    id, peer?.name.orEmpty(), r.hops, linkToNode[r.viaLink], r.lastSeen,
                    peer?.status?.position, peer?.status?.batteryPercent ?: -1,
                )
            }
            .sortedWith(compareBy<RouteInfo> { it.hops }.thenBy { it.name.lowercase() })
    }

    // ---- link events ---------------------------------------------------------------------------

    fun linkUp(linkId: String) {
        if (links.add(linkId)) {
            log("Kết nối trực tiếp mới: $linkId")
            topologyDirty = true
        }
        announce()
        flushTopology()
    }

    fun linkDown(linkId: String) {
        if (!links.remove(linkId)) return
        mismatchedLinks.remove(linkId)
        val node = linkToNode.remove(linkId)
        val before = routes.size
        routes.values.removeAll { it.viaLink == linkId }
        log("Mất kết nối ${node?.short ?: linkId}, gỡ ${before - routes.size} tuyến")
        topologyDirty = true
        flushTopology()
    }

    fun reset() {
        links.clear()
        linkToNode.clear()
        routes.clear()
        parked.clear()
        topologyDirty = true
        flushTopology()
    }

    // ---- inbound -------------------------------------------------------------------------------

    fun receive(linkId: String, bytes: ByteArray) {
        if (linkId !in links) return
        val p = Packet.decode(bytes)
        if (p == null) {
            rejected++
            val version = Packet.peekVersion(bytes)
            if (version != null && version != Packet.VERSION && mismatchedLinks.add(linkId)) {
                log("Máy kề bên dùng giao thức v$version, máy này v${Packet.VERSION}. Cần cài cùng một bản app")
                callback?.onVersionMismatch(linkId, version)
            }
            return
        }
        received++
        if (p.src == me) return // our own flood echoing back
        if (p.hops == 0) bindLink(linkId, p.src)

        when {
            p.type == PacketType.ANNOUNCE -> handleAnnounce(p, linkId)
            p.type == PacketType.CALL_MEDIA -> handleMedia(p, linkId)
            p.id in seen -> duplicates++
            else -> {
                val peer = peers[p.src]
                if (peer == null) park(p, linkId) else verifyAndProcess(p, linkId, peer)
            }
        }
        flushTopology()
    }

    private fun handleAnnounce(p: Packet, linkId: String) {
        val now = clock()
        val metric = p.hops + 1
        if (p.id in seen) {
            // Same announce via another path: only useful if that path is shorter.
            duplicates++
            val r = routes[p.src]
            if (r != null && r.packetId == p.id && metric < r.hops) {
                r.hops = metric
                r.viaLink = linkId
                topologyDirty = true
            }
            return
        }
        val ann = Announce.decode(p.payload)
        if (ann == null || NodeId.fromPublicKey(ann.signPublic) != p.src) {
            reject("Announce không hợp lệ từ ${p.src.short}")
            return
        }
        val keys = try {
            Crypto.publicKey(ann.signPublic) to Crypto.publicKey(ann.dhPublic)
        } catch (e: Exception) {
            reject("Khóa công khai hỏng từ ${p.src.short}")
            return
        }
        if (!Crypto.verify(keys.first, p.signable(), p.signature)) {
            reject("Chữ ký announce sai từ ${p.src.short}")
            return
        }
        seen.add(p.id)

        val known = peers[p.src]
        val status = NodeStatus(ann.position(now), ann.batteryPercent)
        if (known == null || known.name != ann.name || known.status != status) {
            peers[p.src] = PeerInfo(p.src, ann.name, keys.first, keys.second, status)
            topologyDirty = true
            if (known == null) log("Phát hiện nút \"${ann.name}\" (${p.src.short}) cách $metric bước")
        }

        val lifetime = ann.intervalMs.toLong().coerceIn(1_000, config.maxAnnounceIntervalMs) *
            config.routeLifetimeIntervals + config.routeGraceMs
        val r = routes[p.src]
        if (r == null || r.expiresAt < now || r.viaLink !in links || ann.seq > r.seq ||
            (ann.seq == r.seq && metric < r.hops)
        ) {
            if (r == null || r.hops != metric || r.viaLink != linkId) topologyDirty = true
            routes[p.src] = Route(linkId, metric, ann.seq, p.id, now, now + lifetime)
        }

        if (p.ttl > 1) {
            relayed++
            floodExcept(p.relayed(), linkId)
        }
        if (known == null) releaseParked(p.src)
    }

    private fun verifyAndProcess(p: Packet, linkId: String, peer: PeerInfo) {
        if (!Crypto.verify(peer.signKey, p.signable(), p.signature)) {
            reject("Chữ ký sai: gói ${p.type} giả danh ${p.src.short}")
            return
        }
        if (!seen.add(p.id)) {
            duplicates++
            return
        }
        val forMe = p.dst == me
        if (forMe || p.isBroadcast) {
            delivered++
            callback?.onPacket(p, linkId)
        }
        if (!forMe && p.ttl > 1) {
            // Traceroute: a relay can't write itself into the probe (that would break the
            // originator's signature), so it reports back separately, signed as itself.
            if (p.type == PacketType.TRACE) reportTraceHop(p)
            forward(p.relayed(), linkId)
        }
    }

    private fun park(p: Packet, linkId: String) {
        if (parked.size >= config.pendingMax) parked.removeFirst()
        parked.addLast(Parked(p, linkId, clock()))
    }

    private fun releaseParked(src: NodeId) {
        val peer = peers[src] ?: return
        val ready = parked.filter { it.packet.src == src }
        if (ready.isEmpty()) return
        parked.removeAll { it.packet.src == src }
        for (item in ready) {
            if (item.link in links && item.packet.id !in seen) verifyAndProcess(item.packet, item.link, peer)
        }
    }

    // ---- outbound ------------------------------------------------------------------------------

    /** Sends to [dst] along the best known route, or floods if none is known. */
    fun sendUnicast(dst: NodeId, type: PacketType, payload: ByteArray): Boolean {
        if (links.isEmpty()) return false
        val p = build(type, dst, payload, config.maxTtl)
        val route = liveRoute(dst)
        if (route != null) transmit(route.viaLink, p) else floodExcept(p, null)
        return true
    }

    fun broadcast(type: PacketType, payload: ByteArray): Boolean {
        if (links.isEmpty()) return false
        floodExcept(build(type, NodeId.BROADCAST, payload, config.maxTtl), null)
        return true
    }

    /** Single-hop packet to one neighbour (ttl = 1, never relayed). */
    fun sendToLink(linkId: String, type: PacketType, payload: ByteArray) {
        if (linkId !in links) return
        transmit(linkId, build(type, NodeId.BROADCAST, payload, 1))
    }

    fun announceNow() {
        announce()
        flushTopology()
    }

    // ---- housekeeping --------------------------------------------------------------------------

    fun tick() {
        val now = clock()
        if (now - lastAnnounceAt >= announceInterval()) announce()

        val expired = routes.filterValues { it.expiresAt < now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { routes.remove(it) }
            log("Hết hạn ${expired.size} tuyến: ${expired.joinToString { peers[it]?.name ?: it.short }}")
            topologyDirty = true
        }
        while (parked.isNotEmpty() && now - parked.first().at > config.pendingTimeoutMs) parked.removeFirst()
        flushTopology()
    }

    private fun announceInterval(): Long = minOf(
        config.maxAnnounceIntervalMs,
        config.baseAnnounceIntervalMs + routes.size * config.announceBackoffPerNodeMs,
    )

    private fun announce() {
        lastAnnounceAt = clock()
        if (links.isEmpty()) return
        announceSeq++
        val status = localStatus()
        val pos = status.position
        val payload = Announce(
            announceSeq,
            announceInterval().toInt(),
            identity.signPublic,
            identity.dhPublic,
            displayName.take(Announce.MAX_NAME),
            status.batteryPercent,
            pos?.latE6 ?: 0,
            pos?.lonE6 ?: 0,
            pos?.accuracyM ?: -1,
            pos?.let { ((clock() - it.fixedAt) / 1000).toInt().coerceAtLeast(0) } ?: -1,
        ).encode()
        floodExcept(build(PacketType.ANNOUNCE, NodeId.BROADCAST, payload, config.maxTtl), null)
    }

    /**
     * Fast path for live call media: no signature to check (end-to-end AEAD protects it), and it
     * only ever follows the routing table. With no route, or a route pointing back where it came
     * from, the packet is dropped: flooding real-time media would flatten the mesh.
     */
    private fun handleMedia(p: Packet, linkId: String) {
        if (p.dst == me) {
            delivered++
            callback?.onPacket(p, linkId)
            return
        }
        if (p.ttl <= 1 || p.isBroadcast) return
        val route = liveRoute(p.dst) ?: return
        if (route.viaLink == linkId) return
        relayed++
        transmit(route.viaLink, p.relayed())
    }

    /** Sends unsigned, route-only (see [handleMedia]). @return false when there is no route. */
    fun sendRouted(dst: NodeId, type: PacketType, payload: ByteArray): Boolean {
        val route = liveRoute(dst) ?: return false
        transmit(route.viaLink, Packet(type, Crypto.randomLong(), me, dst, config.maxTtl, 0, clock(), payload))
        return true
    }

    /** Hops to [dst] along the current route, or null. */
    fun hopsTo(dst: NodeId): Int? = liveRoute(dst)?.hops

    private fun reportTraceHop(probe: Packet) {
        val traceId = try { ByteReader(probe.payload).long() } catch (e: RuntimeException) { return }
        val payload = ByteWriter(12).long(traceId).int(probe.hops + 1).toByteArray()
        sendUnicast(probe.src, PacketType.TRACE_HOP, payload)
    }

    private fun forward(p: Packet, fromLink: String) {
        relayed++
        if (p.isBroadcast) {
            floodExcept(p, fromLink)
            return
        }
        val route = liveRoute(p.dst)
        if (route != null && route.viaLink != fromLink) transmit(route.viaLink, p) else floodExcept(p, fromLink)
    }

    private fun build(type: PacketType, dst: NodeId, payload: ByteArray, ttl: Int): Packet {
        require(payload.size <= Packet.MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val unsigned = Packet(type, Crypto.randomLong(), me, dst, ttl, 0, clock(), payload)
        val p = unsigned.withSignature(identity.sign(unsigned.signable()))
        seen.add(p.id)
        return p
    }

    private fun liveRoute(dst: NodeId): Route? =
        routes[dst]?.takeIf { it.expiresAt >= clock() && it.viaLink in links }

    private fun floodExcept(p: Packet, except: String?) {
        val bytes = p.encode()
        for (link in links.toList()) {
            if (link == except) continue
            transport.send(link, bytes)
            sent++
        }
    }

    private fun transmit(link: String, p: Packet) {
        transport.send(link, p.encode())
        sent++
    }

    private fun bindLink(linkId: String, node: NodeId) {
        if (linkToNode[linkId] != node) {
            linkToNode[linkId] = node
            topologyDirty = true
        }
    }

    private fun reject(reason: String) {
        rejected++
        log(reason)
    }

    private fun flushTopology() {
        if (topologyDirty) {
            topologyDirty = false
            callback?.onTopologyChanged()
        }
    }

    private fun log(message: String) {
        callback?.onLog(message)
    }
}
