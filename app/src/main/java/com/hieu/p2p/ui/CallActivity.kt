package com.hieu.p2p.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.data.CallEngine
import com.hieu.p2p.databinding.ActivityCallBinding
import com.hieu.p2p.mesh.call.CallPhase
import com.hieu.p2p.mesh.call.CallState
import com.hieu.p2p.mesh.call.EndReason
import com.hieu.p2p.mesh.core.NodeId
import com.hieu.p2p.service.MeshService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The call screen, for outgoing, incoming and live calls. Always dark. Remote video fills the
 * screen; your camera sits in a small card; with no picture, a big avatar says who it is.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private val mesh get() = (application as P2pApp).mesh
    private val engine get() = mesh.callEngine
    private val peer: NodeId by lazy { NodeId.fromHex(intent.getStringExtra(EXTRA_PEER)!!) }
    private var proximity: PowerManager.WakeLock? = null
    private var callModeSent = false
    private var pendingAnswerVideo: Boolean? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val video = pendingAnswerVideo ?: return@registerForActivityResult
        pendingAnswerVideo = null
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            mesh.node.acceptCall(video && granted[Manifest.permission.CAMERA] == true)
        } else {
            mesh.node.declineCall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark, so light status and navigation bar icons whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Ring over the lock screen and wake the display, like the phone's own dialler.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(binding.topBar.paddingLeft, bars.top + dp(12), binding.topBar.paddingRight, binding.topBar.paddingBottom)
            binding.controls.setPadding(0, binding.controls.paddingTop, 0, bars.bottom + dp(24))
            binding.incoming.setPadding(binding.incoming.paddingLeft, 0, binding.incoming.paddingRight, bars.bottom + dp(40))
            (binding.localCard.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = bars.top + dp(96)
            insets
        }

        val name = mesh.nameOf(peer)
        binding.bigName.text = name
        binding.topName.text = name
        binding.bigAvatar.bindAvatar(name, peer)
        // A deep wash of the caller's own colour behind everything, as on current call screens.
        val wash = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.blendARGB(MeshRadarView.avatarColor(peer), Color.BLACK, 0.45f),
                ColorUtils.blendARGB(MeshRadarView.avatarColor(peer), Color.BLACK, 0.8f),
                ContextCompat.getColor(this, R.color.call_bg),
            ),
        )
        binding.cover.background = wash

        // Starting a call from the chat screen: place it now.
        if (intent.getBooleanExtra(EXTRA_DIAL, false) && savedInstanceState == null) {
            mesh.node.startCall(peer, intent.getBooleanExtra(EXTRA_VIDEO, false))
        }

        binding.btnEnd.setOnClickListener { mesh.node.hangup() }
        binding.btnDecline.setOnClickListener { mesh.node.declineCall() }
        binding.btnAnswer.setOnClickListener { answer(video = mesh.node.call.value.video) }
        binding.btnVoiceOnly.setOnClickListener { answer(video = false) }
        binding.btnMute.setOnClickListener { engine.setMuted(!engine.view.value.muted) }
        binding.btnSpeaker.setOnClickListener { engine.setSpeaker(!engine.view.value.speaker) }
        binding.btnFlip.setOnClickListener { engine.switchCamera() }
        binding.btnCamera.setOnClickListener {
            val on = !engine.view.value.cameraOn
            if (on && !granted(Manifest.permission.CAMERA)) return@setOnClickListener
            engine.setCameraOn(on)
            updateCallMode(video = on)
        }
        // Tap the picture to hide/show controls, so the video can be seen in full.
        binding.remoteVideo.isClickable = true
        binding.remoteVideo.setOnClickListener {
            val show = !binding.controls.isVisible
            binding.controls.isVisible = show
            binding.topBar.isVisible = show
        }

        binding.localVideo.surfaceTextureListener = textureListener { st -> engine.attachPreview(st) }
        binding.remoteVideo.surfaceTextureListener = textureListener { st -> engine.attachRemote(st?.let(::Surface)) }

        collectWhileStarted(mesh.node.call.combine(engine.view) { c, v -> c to v }) { (call, view) -> render(call, view) }
        lifecycleScope.launch {
            while (true) {
                delay(1_000)
                val c = mesh.node.call.value
                if (c.phase == CallPhase.ACTIVE) render(c, engine.view.value)
            }
        }
    }

    override fun onDestroy() {
        proximity?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun answer(video: Boolean) {
        val needed = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (video && !granted(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            mesh.node.acceptCall(video)
        } else {
            pendingAnswerVideo = video
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun render(call: CallState, view: CallEngine.View) {
        if (call.peer != null && call.peer != peer) return
        val name = mesh.nameOf(peer)
        val active = call.phase == CallPhase.ACTIVE
        val incoming = call.phase == CallPhase.INCOMING

        binding.incoming.isVisible = incoming
        binding.cellVoiceOnly.isVisible = incoming && call.video
        binding.controls.isVisible = !incoming && call.phase != CallPhase.ENDED && (binding.controls.isVisible || !active || !view.remoteVideo)
        binding.btnEnd.isVisible = !incoming

        // Picture: remote video when it is flowing, otherwise the big avatar.
        val showRemote = active && view.remoteVideo
        binding.cover.isVisible = !showRemote
        binding.identity.isVisible = !showRemote
        binding.localCard.isVisible = active && view.cameraOn
        binding.cellFlip.isVisible = view.cameraOn
        if (showRemote && view.remoteWidth > 0) fitCenterCrop(binding.remoteVideo, view.remoteWidth, view.remoteHeight)
        mirror(binding.localVideo, view.frontCamera)

        val status = when (call.phase) {
            CallPhase.OUTGOING -> getString(if (call.ringing) R.string.call_ringing else R.string.call_calling)
            CallPhase.INCOMING -> getString(if (call.video) R.string.call_incoming_video_status else R.string.call_incoming)
            CallPhase.ACTIVE -> {
                val elapsed = Formats.duration((System.currentTimeMillis() - call.activeSince).toInt())
                if (call.hops <= 1) getString(R.string.call_active_direct, elapsed) else getString(R.string.call_active_via, elapsed, call.hops)
            }
            CallPhase.ENDED -> endText(call.endReason, name, call)
            CallPhase.IDLE -> getString(R.string.call_connecting)
        }
        binding.bigStatus.text = status
        binding.topStatus.text = status
        binding.topBar.isVisible = showRemote && binding.controls.isVisible
        binding.topName.text = name

        val note = when {
            !active -> null
            view.videoDroppedForQuality -> getString(R.string.call_video_dropped)
            view.quality == CallEngine.Quality.POOR -> getString(R.string.call_quality_poor)
            view.quality == CallEngine.Quality.FAIR -> getString(R.string.call_quality_fair)
            call.video && call.hops > 2 -> getString(R.string.call_far_warning, call.hops)
            else -> null
        }
        binding.quality.isVisible = note != null
        binding.quality.text = note

        // Control states: filled white = the thing is switched away from normal.
        toggle(binding.btnMute, view.muted, R.drawable.ic_mic_off, R.drawable.ic_mic)
        binding.lblMute.setText(if (view.muted) R.string.call_unmute else R.string.call_mute)
        toggle(binding.btnCamera, !view.cameraOn, R.drawable.ic_videocam_off, R.drawable.ic_videocam)
        binding.lblCamera.setText(if (view.cameraOn) R.string.call_camera else R.string.call_camera_on)
        toggle(binding.btnSpeaker, view.speaker, R.drawable.ic_volume_up, R.drawable.ic_phone_in_talk)
        binding.lblSpeaker.setText(if (view.speaker) R.string.call_speaker else R.string.call_earpiece)

        if (active) {
            if (!callModeSent) {
                callModeSent = true
                updateCallMode(video = view.cameraOn || call.video)
            }
            holdProximity(!view.speaker && !view.cameraOn)
        } else {
            holdProximity(false)
        }

        if (call.phase == CallPhase.ENDED) finishSoon()
    }

    private fun endText(reason: EndReason?, name: String, call: CallState): String = when (reason) {
        EndReason.DECLINED -> getString(R.string.call_end_declined, name)
        EndReason.BUSY -> getString(R.string.call_end_busy, name)
        EndReason.NO_ANSWER -> getString(R.string.call_end_no_answer)
        EndReason.LOST -> getString(R.string.call_end_lost, name)
        EndReason.NO_ROUTE -> getString(R.string.call_end_no_route, name)
        else -> getString(R.string.call_end_hangup, if (call.activeSince > 0) Formats.duration((System.currentTimeMillis() - call.activeSince).toInt()) else "00:00")
    }

    private var finishing = false

    private fun finishSoon() {
        if (finishing) return
        finishing = true
        if (callModeSent) updateCallMode(audio = false, video = false)
        lifecycleScope.launch {
            delay(1_800)
            mesh.node.dismissCall()
            finish()
        }
    }

    private fun updateCallMode(video: Boolean, audio: Boolean = true) =
        MeshService.callMode(this, audio = audio, video = video && audio)

    private fun toggle(button: com.google.android.material.button.MaterialButton, on: Boolean, onIcon: Int, offIcon: Int) {
        button.setIconResource(if (on) onIcon else offIcon)
        button.backgroundTintList = ColorStateList.valueOf(if (on) Color.WHITE else ContextCompat.getColor(this, R.color.call_glass))
        button.iconTint = ColorStateList.valueOf(if (on) ContextCompat.getColor(this, R.color.call_bg) else Color.WHITE)
    }

    /** Fills the screen with the remote picture, cropping the overflow (like other video apps). */
    private fun fitCenterCrop(view: TextureView, w: Int, h: Int) {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val scale = maxOf(vw / w, vh / h)
        val sx = w * scale / vw
        val sy = h * scale / vh
        view.setTransform(Matrix().apply { setScale(sx, sy, vw / 2, vh / 2) })
    }

    /** Selfie preview reads as a mirror, as people expect; the other side sees you unmirrored. */
    private fun mirror(view: TextureView, front: Boolean) {
        val w = view.width.toFloat()
        view.setTransform(Matrix().apply { if (front) setScale(-1f, 1f, w / 2, 0f) })
    }

    /** Audio call at the ear: screen off when near the face, like a phone call. */
    private fun holdProximity(hold: Boolean) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (hold) {
            if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "meshlink:call")
            }
            proximity?.takeIf { !it.isHeld }?.acquire(3 * 60 * 60_000L)
        } else {
            proximity?.takeIf { it.isHeld }?.release()
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun textureListener(onChange: (SurfaceTexture?) -> Unit) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = onChange(surface)
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            onChange(null)
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    companion object {
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_DIAL = "dial"

        /** Opens the screen for an existing (e.g. incoming) call. */
        fun intent(context: Context, peer: NodeId, video: Boolean) =
            Intent(context, CallActivity::class.java)
                .putExtra(EXTRA_PEER, peer.hex).putExtra(EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Places a new call and opens the screen. Permissions must already be granted. */
        fun dial(context: Context, peer: NodeId, video: Boolean) {
            context.startActivity(intent(context, peer, video).putExtra(EXTRA_DIAL, true))
        }
    }
}
