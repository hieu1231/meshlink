package com.hieu.p2p.mesh.crypto

import com.hieu.p2p.mesh.core.NodeId
import java.security.KeyPair
import java.security.PublicKey

/**
 * A node's long-term identity: one P-256 key pair for signing, one for ECDH.
 * The [nodeId] is derived from the signing key, which makes addresses self-certifying.
 */
class Identity(private val signPair: KeyPair, private val dhPair: KeyPair) {
    val signPublic: ByteArray = signPair.public.encoded
    val dhPublic: ByteArray = dhPair.public.encoded
    val nodeId: NodeId = NodeId.fromPublicKey(signPublic)

    fun sign(data: ByteArray): ByteArray = Crypto.sign(signPair.private, data)

    fun agree(peerDhKey: PublicKey): ByteArray = Crypto.sharedSecret(dhPair.private, peerDhKey)

    /** sign-private, sign-public, dh-private, dh-public (PKCS#8 / X.509). */
    fun export(): List<ByteArray> = listOf(signPair.private.encoded, signPublic, dhPair.private.encoded, dhPublic)

    companion object {
        fun generate() = Identity(Crypto.generateKeyPair(), Crypto.generateKeyPair())

        fun restore(parts: List<ByteArray>): Identity {
            require(parts.size == 4)
            return Identity(
                KeyPair(Crypto.publicKey(parts[1]), Crypto.privateKey(parts[0])),
                KeyPair(Crypto.publicKey(parts[3]), Crypto.privateKey(parts[2])),
            )
        }
    }
}
