package com.hieu.p2p.ui

import android.app.Activity
import android.os.Bundle
import com.hieu.p2p.P2pApp
import com.hieu.p2p.mesh.call.CallPhase
import com.hieu.p2p.mesh.core.NodeId

/**
 * Debug-only: opens the call screen in a chosen state with nobody on the other end, for design
 * review. In an active call your own camera and mic are looped back as the "remote" side.
 * Must run in a fresh process (adb `am start -S`) so the sample name is picked up.
 *
 * Extras: `phase` = calling | ringing | incoming | active (default active), `video` = true/false.
 */
class CallPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val peer = NodeId(0x34fdc2f71df8c90cL)
        getSharedPreferences("mesh", MODE_PRIVATE).edit().putString("peer_name_${peer.hex}", "Hieu").apply()
        val video = intent.getBooleanExtra("video", false)
        val (phase, ringing) = when (intent.getStringExtra("phase")) {
            "calling" -> CallPhase.OUTGOING to false
            "ringing" -> CallPhase.OUTGOING to true
            "incoming" -> CallPhase.INCOMING to false
            else -> CallPhase.ACTIVE to false
        }
        (application as P2pApp).mesh.node.previewCall(peer, phase, video, ringing)
        startActivity(CallActivity.intent(this, peer, video))
        finish()
    }
}
