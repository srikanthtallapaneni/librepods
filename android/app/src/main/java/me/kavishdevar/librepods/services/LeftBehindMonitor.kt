/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.R
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * A local stand-in for iOS's "Notify When Left Behind", which uses Apple's Find My network. When
 * the AirPods disconnect, it saves where the phone is. If over the next few minutes the phone moves
 * away and the AirPods aren't seen over Bluetooth any more, it notifies with a link to that place.
 */
class LeftBehindMonitor(
    context: Context,
    private val isConnected: () -> Boolean,
    // When this phone last received a Bluetooth broadcast from these AirPods (BLE scan).
    private val lastSeenNearby: () -> Long,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.left_behind_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    fun onConnected() {
        watchJob?.cancel()
        watchJob = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /** Called on every disconnect path, possibly several times for one disconnect. */
    fun onDisconnected() {
        if (!prefs.getBoolean(PREF_ENABLED, false) || !hasLocationPermission(appContext)) return
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            val disconnectedAt = System.currentTimeMillis()
            val origin = currentLocation() ?: run {
                Log.w(TAG, "No location at disconnect, can't watch for left-behind AirPods")
                return@launch
            }
            prefs.edit {
                putFloat(PREF_LAST_LAT, origin.latitude.toFloat())
                putFloat(PREF_LAST_LNG, origin.longitude.toFloat())
                putLong(PREF_LAST_TIME, disconnectedAt)
            }
            for (wait in CHECK_DELAYS_MS) {
                delay(wait)
                if (isConnected()) return@launch
                // Still hearing the AirPods: they're with us (in a pocket, in the case).
                if (System.currentTimeMillis() - lastSeenNearby() < NEARBY_WINDOW_MS) continue
                val here = currentLocation() ?: continue
                val distance = here.distanceTo(origin)
                Log.d(TAG, "Moved ${distance.toInt()} m since the AirPods disconnected")
                if (distance >= ALERT_DISTANCE_M) {
                    notifyLeftBehind(origin)
                    return@launch
                }
            }
        }
    }

    private fun notifyLeftBehind(origin: Location) {
        val map = Intent(Intent.ACTION_VIEW, mapUri(origin.latitude, origin.longitude))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingMap = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID, map, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle(appContext.getString(R.string.left_behind_title))
            .setContentText(appContext.getString(R.string.left_behind_text))
            .setContentIntent(pendingMap)
            .addAction(0, appContext.getString(R.string.left_behind_show_map), pendingMap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission") // checked in onDisconnected
    private suspend fun currentLocation(): Location? {
        if (!hasLocationPermission(appContext)) return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { locationManager.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                locationManager.getCurrentLocation(provider, cancel, executor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        }
    }

    companion object {
        private const val TAG = "LeftBehindMonitor"
        private const val CHANNEL_ID = "left_behind"
        private const val NOTIFICATION_ID = 120
        const val PREF_ENABLED = "left_behind_alerts"
        private const val PREF_LAST_LAT = "last_seen_lat"
        private const val PREF_LAST_LNG = "last_seen_lng"
        const val PREF_LAST_TIME = "last_seen_time"

        // Checked at 1, 3, 6 and 10 minutes after the disconnect.
        private val CHECK_DELAYS_MS = listOf(60_000L, 120_000L, 180_000L, 240_000L)
        private const val NEARBY_WINDOW_MS = 60_000L
        private const val ALERT_DISTANCE_M = 150f
        private const val LOCATION_TIMEOUT_MS = 30_000L

        private val executor = Executors.newSingleThreadExecutor()

        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        /** Foreground location plus "Allow all the time", since the check runs in the background. */
        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Where the AirPods were when they last disconnected, as a map link, or null if unknown. */
        fun lastSeenMapUri(context: Context): Uri? {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.contains(PREF_LAST_LAT)) return null
            return mapUri(prefs.getFloat(PREF_LAST_LAT, 0f).toDouble(), prefs.getFloat(PREF_LAST_LNG, 0f).toDouble())
        }

        private fun mapUri(lat: Double, lng: Double): Uri =
            Uri.parse("geo:$lat,$lng?q=$lat,$lng(AirPods)")
    }
}
