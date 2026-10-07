package com.hieu.p2p.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.hieu.p2p.mesh.core.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Link layer on Google Nearby Connections (P2P_CLUSTER: BLE + Bluetooth Classic + Wi-Fi, M-to-N).
 *
 * Nearby only gives us direct neighbours; multi-hop reach comes from the mesh router on top.
 * Each device keeps at most [MAX_LINKS] links — radios degrade beyond a handful of concurrent
 * connections, and a sparse, well-connected graph is exactly what mesh routing needs.
 *
 * Connection authentication is deferred to the mesh layer (signed announces), so connections are
 * auto-accepted here.
 */
class NearbyTransport(context: Context, private val localName: String) : Transport {

    private class Found(val name: String, val foundAt: Long)

    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val connected: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending = HashSet<String>()
    private val discovered = HashMap<String, Found>()
    private var listener: Transport.Listener? = null
    private var active = false

    override fun start(listener: Transport.Listener) {
        main.post {
            if (active) return@post
            active = true
            this.listener = listener
            startAdvertising()
            startDiscovery()
            main.postDelayed(maintenance, MAINTENANCE_MS)
        }
    }

    override fun stop() {
        main.post { shutdown() }
    }

    private fun shutdown() {
        if (!active) return
        active = false
        main.removeCallbacks(maintenance)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        val dropped = connected.toList()
        connected.clear()
        pending.clear()
        discovered.clear()
        dropped.forEach { listener?.onLinkDown(it) }
        listener = null
    }

    override fun send(linkId: String, bytes: ByteArray) {
        if (linkId !in connected) return
        client.sendPayload(linkId, Payload.fromBytes(bytes))
            .addOnFailureListener { log("Gửi tới $linkId lỗi: ${it.message}") }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(localName, SERVICE_ID, lifecycle, options)
            .addOnSuccessListener { log("Đang quảng bá (advertising)") }
            .addOnFailureListener { log("Không quảng bá được: ${it.message}") }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discovery, options)
            .addOnSuccessListener { log("Đang dò tìm thiết bị (discovery)") }
            .addOnFailureListener { log("Không dò tìm được: ${it.message}") }
    }

    private fun hasCapacity() = connected.size + pending.size < MAX_LINKS

    private fun tryConnect(endpointId: String) {
        val found = discovered[endpointId] ?: return
        if (!active || endpointId in connected || endpointId in pending || !hasCapacity()) return
        // Both sides discover each other; the lower name dials first to avoid simultaneous-dial
        // collisions. The other side dials too if nothing happened after a grace period.
        val waited = SystemClock.elapsedRealtime() - found.foundAt
        if (localName > found.name && waited < DIAL_GRACE_MS) return
        pending += endpointId
        client.requestConnection(localName, endpointId, lifecycle)
            .addOnFailureListener {
                pending -= endpointId
                log("Kết nối ${found.name.take(8)} thất bại: ${it.message}")
            }
    }

    private val maintenance = object : Runnable {
        override fun run() {
            if (!active) return
            discovered.keys.toList().forEach(::tryConnect)
            main.postDelayed(this, MAINTENANCE_MS)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID || info.endpointName == localName) return
            discovered[endpointId] = Found(info.endpointName, SystemClock.elapsedRealtime())
            tryConnect(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!active || (endpointId !in pending && !hasCapacity())) {
                client.rejectConnection(endpointId)
                return
            }
            pending += endpointId
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pending -= endpointId
            if (resolution.status.isSuccess && active) {
                if (connected.add(endpointId)) listener?.onLinkUp(endpointId)
            } else {
                log("Kết nối $endpointId không thành công (${resolution.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connected.remove(endpointId)) listener?.onLinkDown(endpointId)
            // Look for a replacement neighbour right away.
            main.post { discovered.keys.toList().forEach(::tryConnect) }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { listener?.onReceive(endpointId, it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun log(message: String) {
        listener?.onTransportLog(message)
    }

    companion object {
        const val SERVICE_ID = "com.hieu.p2p.mesh.v1"
        const val MAX_LINKS = 4
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val DIAL_GRACE_MS = 6_000L
        private const val MAINTENANCE_MS = 10_000L
    }
}
