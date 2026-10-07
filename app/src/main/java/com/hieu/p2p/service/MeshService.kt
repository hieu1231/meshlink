package com.hieu.p2p.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hieu.p2p.P2pApp
import com.hieu.p2p.R
import com.hieu.p2p.ui.MainActivity

/**
 * Keeps the node alive while the screen is off: a mesh is only as good as its relays,
 * so every phone must keep forwarding even when its owner is not using the app.
 */
class MeshService : Service() {

    private val mesh get() = (application as P2pApp).mesh

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CALL_MODE) {
            // During a call the mic (and camera) must keep working when the screen is off or
            // another app is in front; Android requires the service to declare that.
            val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
            val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types(audio, video)) }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            mesh.turnOff()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types(audio = false, video = false))
        } catch (e: Exception) {
            // Missing runtime permission (Android 14+ checks them for connectedDevice services).
            stopSelf()
            return START_NOT_STICKY
        }
        mesh.start()
        return START_STICKY
    }

    /** Location type only when we hold the permission, or Android 14+ rejects the start. */
    private fun types(audio: Boolean, video: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mesh.location.hasPermission()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (audio) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return t
    }

    override fun onDestroy() {
        mesh.stop()
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, P2pApp.CHANNEL_MESH)
        .setSmallIcon(R.drawable.ic_stat_mesh)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0, getString(R.string.action_stop),
            PendingIntent.getService(
                this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.hieu.p2p.STOP"
        private const val ACTION_CALL_MODE = "com.hieu.p2p.CALL_MODE"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_VIDEO = "video"

        /** Call while the app is in the foreground (Android only allows mic/camera types then). */
        fun callMode(context: Context, audio: Boolean, video: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, MeshService::class.java).setAction(ACTION_CALL_MODE)
                        .putExtra(EXTRA_AUDIO, audio).putExtra(EXTRA_VIDEO, video)
                )
            }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            (context.applicationContext as P2pApp).mesh.turnOff()
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
