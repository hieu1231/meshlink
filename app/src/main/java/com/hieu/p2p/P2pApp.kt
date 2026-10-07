package com.hieu.p2p

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.hieu.p2p.data.MeshRuntime

class P2pApp : Application() {
    val mesh: MeshRuntime by lazy { MeshRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MESH, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_MESH = "mesh"
    }
}
