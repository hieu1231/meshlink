package com.hieu.p2p.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hieu.p2p.mesh.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This phone's position from the platform LocationManager: GPS works with no Internet at all,
 * the network provider fills in indoors when it can. No Play Services dependency.
 */
class LocationTracker(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val battery = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var active = false

    private val _position = MutableStateFlow<GeoPoint?>(null)
    /** Latest usable fix, regardless of whether the user shares it. */
    val position: StateFlow<GeoPoint?> = _position.asStateFlow()

    var onUpdate: () -> Unit = {}

    private val listener = LocationListener { accept(it) }

    val batteryPercent: Int
        get() = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1

    fun hasPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (active || !hasPermission()) return
        active = true
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.allProviders.contains(provider)) continue
            runCatching { manager.getLastKnownLocation(provider)?.let(::accept) }
            runCatching {
                manager.requestLocationUpdates(provider, UPDATE_MS, UPDATE_METRES, listener, Looper.getMainLooper())
            }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        manager.removeUpdates(listener)
    }

    /** Keep the better of the current and the new fix: fresher wins unless much less accurate. */
    private fun accept(location: Location) {
        val fix = GeoPoint.of(location.latitude, location.longitude, location.accuracy, location.time)
        val current = _position.value
        val better = current == null ||
            fix.fixedAt - current.fixedAt > STALE_MS ||
            (fix.fixedAt >= current.fixedAt && fix.accuracyM <= current.accuracyM * 2 + 10)
        if (better) {
            _position.value = fix
            onUpdate()
        }
    }

    companion object {
        private const val UPDATE_MS = 4_000L
        private const val UPDATE_METRES = 3f
        private const val STALE_MS = 30_000L
    }
}
