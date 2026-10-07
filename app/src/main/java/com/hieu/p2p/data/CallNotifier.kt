package com.hieu.p2p.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.mesh.call.CallPhase
import com.hieu.p2p.mesh.call.CallState
import com.hieu.p2p.ui.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reacts to call state for the whole app: rings and shows a full-screen incoming-call notification,
 * starts/stops the media engine, and tidies up a call that ended with no screen open.
 */
class CallNotifier(private val context: Context, private val runtime: MeshRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifications = NotificationManagerCompat.from(context)
    private var ringtone: Ringtone? = null
    private var lastPhase = CallPhase.IDLE
    private var statsLog: Job? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.call_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // we loop the ringtone ourselves
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun watch() = scope.launch {
        runtime.node.call.collect { state ->
            val previous = lastPhase
            lastPhase = state.phase
            if (state.phase == previous && state.phase != CallPhase.ACTIVE) return@collect
            when (state.phase) {
                CallPhase.INCOMING -> if (previous != CallPhase.INCOMING) ringFor(state)
                CallPhase.ACTIVE -> {
                    stopRinging()
                    if (previous != CallPhase.ACTIVE) {
                        runtime.callEngine.start(state.video)
                        statsLog = logStats(state.hops)
                    }
                }
                CallPhase.ENDED -> {
                    stopRinging()
                    statsLog?.cancel()
                    statsLog = null
                    runtime.callEngine.stop()
                    // If no call screen is open to show "call ended", clear it ourselves.
                    delay(4_000)
                    runtime.node.dismissCall()
                }
                else -> stopRinging()
            }
        }
    }

    /** Field numbers for real calls: `adb logcat -s MeshLink:I`. */
    private fun logStats(hops: Int) = scope.launch {
        while (true) {
            delay(5_000)
            val engine = runtime.callEngine
            val v = engine.view.value
            Log.i("MeshLink", "call ${hops} hop(s): ${engine.stats} · ${v.bitrateKbps} kbps · ${v.quality}")
        }
    }

    private fun ringFor(state: CallState) {
        val peer = state.peer ?: return
        val name = runtime.nameOf(peer)
        val open = PendingIntent.getActivity(
            context, 10, CallActivity.intent(context, peer, state.video),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context, 11, Intent(context, DeclineReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(name)
            .setContentText(context.getString(if (state.video) R.string.call_incoming_video else R.string.call_incoming_voice))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.call_decline), decline)
            .addAction(0, context.getString(R.string.call_answer), open)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
        ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        vibrator()?.cancel()
        notifications.cancel(NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION")
    private fun vibrator() = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** "Decline" straight from the notification. */
    class DeclineReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (context.applicationContext as P2pApp).mesh.node.declineCall()
        }
    }

    companion object {
        private const val CHANNEL = "calls"
        private const val NOTIFICATION_ID = 7
    }
}
