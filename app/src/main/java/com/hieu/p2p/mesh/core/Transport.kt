package com.hieu.p2p.mesh.core

/**
 * Physical link layer (Nearby Connections, Wi-Fi Direct, BLE, a test double...).
 * A "link" is a direct radio connection to one neighbour; the mesh layer handles everything beyond one hop.
 */
interface Transport {
    fun start(listener: Listener)
    fun stop()
    fun send(linkId: String, bytes: ByteArray)

    interface Listener {
        fun onLinkUp(linkId: String)
        fun onLinkDown(linkId: String)
        fun onReceive(linkId: String, bytes: ByteArray)
        fun onTransportLog(message: String) {}
    }
}
