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

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.AACPManager
import java.time.Instant
import java.time.ZoneId

/**
 * Live heart rate from AirPods with a heart rate sensor (AirPods Pro 3). While measuring is on, the
 * AirPods report once a second. Readings are shown in the app and, if the user allows it, saved to
 * Health Connect in batches of up to a minute.
 */
class HeartRateMonitor(
    context: Context,
    private val aacpManager: AACPManager,
    private val firmwareVersion: () -> String?,
) : AACPManager.HeartRateListener {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _bpm = MutableStateFlow<Int?>(null)
    /** The latest reading, or null when there's none (not measuring, or not worn yet). */
    val bpm: StateFlow<Int?> = _bpm.asStateFlow()

    private val _measuring = MutableStateFlow(prefs.getBoolean(PREF_MEASURING, false))
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    private val _readings = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    /** Every reading as it arrives, including repeats of the same value (used by workouts). */
    val readings: SharedFlow<Int> = _readings

    private val pending = mutableListOf<HeartRateRecord.Sample>()

    init {
        aacpManager.heartRateListener = this
    }

    var saveToHealthConnect: Boolean
        get() = prefs.getBoolean(PREF_HEALTH_CONNECT, false)
        set(value) = prefs.edit { putBoolean(PREF_HEALTH_CONNECT, value) }

    /** Called once the AACP connection is set up; resumes measuring if it was on. */
    fun onConnected() {
        if (_measuring.value) sendInterval(INTERVAL_MICROS)
    }

    fun setMeasuring(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_MEASURING, enabled) }
        _measuring.value = enabled
        sendInterval(if (enabled) INTERVAL_MICROS else 0)
        if (!enabled) {
            _bpm.value = null
            flush()
        }
    }

    private fun sendInterval(intervalMicros: Long) {
        scope.launch {
            if (!aacpManager.sendHeartRateInterval(firmwareVersion(), intervalMicros)) {
                Log.w(TAG, "Couldn't send the heart rate interval ($intervalMicros µs)")
            }
        }
    }

    override fun onHeartRate(bpm: Int) {
        if (!_measuring.value) return
        _bpm.value = bpm
        _readings.tryEmit(bpm)
        val flushNow = synchronized(pending) {
            pending += HeartRateRecord.Sample(Instant.now(), bpm.toLong())
            pending.size >= BATCH_SIZE
        }
        if (flushNow) flush()
    }

    override fun onDisconnected() {
        _bpm.value = null
        flush()
    }

    private fun flush() {
        val samples = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (samples.isEmpty() || !saveToHealthConnect) return
        scope.launch {
            try {
                val client = healthConnectClient(appContext) ?: return@launch
                val granted = client.permissionController.getGrantedPermissions()
                if (WRITE_PERMISSION !in granted) {
                    Log.w(TAG, "Health Connect write permission not granted; dropping ${samples.size} samples")
                    return@launch
                }
                val start = samples.first().time
                // An interval record must end after it starts, even with a single sample.
                val end = maxOf(samples.last().time, start.plusSeconds(1))
                val zone = ZoneId.systemDefault().rules
                client.insertRecords(
                    listOf(
                        HeartRateRecord(
                            startTime = start,
                            startZoneOffset = zone.getOffset(start),
                            endTime = end,
                            endZoneOffset = zone.getOffset(end),
                            samples = samples,
                            metadata = Metadata.autoRecorded(Device(type = Device.TYPE_HEARABLE)),
                        )
                    )
                )
                Log.d(TAG, "Saved ${samples.size} heart rate samples to Health Connect")
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't save heart rate to Health Connect", e)
            }
        }
    }

    companion object {
        private const val TAG = "HeartRateMonitor"
        private const val PREF_MEASURING = "heart_rate_measuring"
        private const val PREF_HEALTH_CONNECT = "heart_rate_health_connect"
        private const val INTERVAL_MICROS = 1_000_000L
        private const val BATCH_SIZE = 60

        val WRITE_PERMISSION = HealthPermission.getWritePermission(HeartRateRecord::class)

        /** The Health Connect client, or null if Health Connect isn't available on this phone. */
        fun healthConnectClient(context: Context): HealthConnectClient? =
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
    }
}
