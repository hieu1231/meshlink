package com.hieu.p2p.mesh.dm

import com.hieu.p2p.mesh.core.ByteWriter
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.mesh.crypto.Crypto
import com.hieu.p2p.mesh.crypto.Identity
import com.hieu.p2p.mesh.routing.PeerInfo

/**
 * Pairwise end-to-end key with each peer: HKDF-SHA256(ECDH(myDhKey, peerDhKey)), salted with both
 * node ids. Shared by direct messages and call signalling.
 */
class SessionKeys(private val identity: Identity) {
    private val keys = HashMap<NodeId, ByteArray>()

    @Synchronized
    fun of(peer: PeerInfo): ByteArray = keys.getOrPut(peer.id) {
        val a = minOf(identity.nodeId.raw, peer.id.raw)
        val b = maxOf(identity.nodeId.raw, peer.id.raw)
        Crypto.hkdf(
            identity.agree(peer.dhKey),
            ByteWriter().long(a).long(b).toByteArray(),
            "p2p-mesh/dm/v1".toByteArray(),
        )
    }
}
