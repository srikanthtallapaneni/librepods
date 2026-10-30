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
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Workouts tracked with the AirPods heart rate sensor. Starting a workout turns on heart rate
 * measuring, and stopping it saves an exercise session, an active calories estimate and the heart
 * rate readings to Health Connect.
 *
 * Calories come from the Keytel et al. (2005) heart rate formula, using the age, weight and sex the
 * user enters. It's an estimate and won't match Apple's numbers.
 */
class WorkoutTracker(
    context: Context,
    private val heartRateMonitor: HeartRateMonitor,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Type(val healthConnectType: Int) {
        WALKING(ExerciseSessionRecord.EXERCISE_TYPE_WALKING),
        RUNNING(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING),
        CYCLING(ExerciseSessionRecord.EXERCISE_TYPE_BIKING),
        HIKING(ExerciseSessionRecord.EXERCISE_TYPE_HIKING),
        STRENGTH(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING),
        OTHER(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT),
    }

    enum class Sex { MALE, FEMALE, UNSET }

    data class State(
        val active: Boolean = false,
        val type: Type = Type.WALKING,
        val startedAt: Long = 0L,
        val endedAt: Long = 0L,
        val averageBpm: Int? = null,
        val maxBpm: Int? = null,
        val calories: Double = 0.0,
        /** Whether the last finished workout was saved to Health Connect, or null while unknown. */
        val saved: Boolean? = null,
    )

    private val _state = MutableStateFlow(State())
    /** The workout in progress, or the last finished one when [State.active] is false. */
    val state: StateFlow<State> = _state.asStateFlow()

    private val samples = mutableListOf<HeartRateRecord.Sample>()
    private var collectJob: Job? = null
    private var wasMeasuring = false

    var saveToHealthConnect: Boolean
        get() = prefs.getBoolean(PREF_HEALTH_CONNECT, false)
        set(value) = prefs.edit { putBoolean(PREF_HEALTH_CONNECT, value) }

    var age: Int
        get() = prefs.getInt(PREF_AGE, 30)
        set(value) = prefs.edit { putInt(PREF_AGE, value) }

    var weightKg: Int
        get() = prefs.getInt(PREF_WEIGHT, 70)
        set(value) = prefs.edit { putInt(PREF_WEIGHT, value) }

    var sex: Sex
        get() = runCatching { Sex.valueOf(prefs.getString(PREF_SEX, null)!!) }.getOrDefault(Sex.UNSET)
        set(value) = prefs.edit { putString(PREF_SEX, value.name) }

    fun start(type: Type) {
        if (_state.value.active) return
        synchronized(samples) { samples.clear() }
        _state.value = State(active = true, type = type, startedAt = System.currentTimeMillis())
        wasMeasuring = heartRateMonitor.measuring.value
        if (!wasMeasuring) heartRateMonitor.setMeasuring(true)
        collectJob = scope.launch {
            heartRateMonitor.readings.collect(::onReading)
        }
    }

    fun stop() {
        if (!_state.value.active) return
        collectJob?.cancel()
        collectJob = null
        if (!wasMeasuring) heartRateMonitor.setMeasuring(false)
        val finished = _state.updateAndGet { it.copy(active = false, endedAt = System.currentTimeMillis()) }
        val readings = synchronized(samples) { samples.toList() }
        save(finished, readings)
    }

    private fun onReading(bpm: Int) {
        val now = Instant.now()
        val (previous, all) = synchronized(samples) {
            val previous = samples.lastOrNull()
            samples += HeartRateRecord.Sample(now, bpm.toLong())
            previous to samples.map { it.beatsPerMinute }
        }
        // Count the time since the last reading, but not across gaps (buds out, disconnected).
        val seconds = previous?.let { (now.toEpochMilli() - it.time.toEpochMilli()) / 1000.0 } ?: 0.0
        val added = if (seconds in 0.0..MAX_GAP_SECONDS) caloriesPerMinute(bpm) * seconds / 60 else 0.0
        _state.update {
            it.copy(
                averageBpm = all.average().toInt(),
                maxBpm = all.max().toInt(),
                calories = it.calories + added,
            )
        }
    }

    /** Keytel et al. (2005), converted from kJ to kcal. Averages both formulas when sex isn't set. */
    private fun caloriesPerMinute(bpm: Int): Double {
        val male = (-55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * age) / 4.184
        val female = (-20.4022 + 0.4472 * bpm - 0.1263 * weightKg + 0.074 * age) / 4.184
        val kcal = when (sex) {
            Sex.MALE -> male
            Sex.FEMALE -> female
            Sex.UNSET -> (male + female) / 2
        }
        return kcal.coerceAtLeast(0.0)
    }

    private fun save(workout: State, readings: List<HeartRateRecord.Sample>) {
        if (!saveToHealthConnect) {
            _state.update { it.copy(saved = false) }
            return
        }
        scope.launch {
            val saved = try {
                val client = HeartRateMonitor.healthConnectClient(appContext)
                if (client == null || !client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) {
                    Log.w(TAG, "Health Connect isn't available or permissions are missing; workout not saved")
                    false
                } else {
                    client.insertRecords(records(workout, readings))
                    Log.d(TAG, "Saved a ${workout.type} workout with ${readings.size} heart rate readings")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't save the workout to Health Connect", e)
                false
            }
            _state.update { it.copy(saved = saved) }
        }
    }

    private fun records(workout: State, readings: List<HeartRateRecord.Sample>): List<Record> {
        val start = Instant.ofEpochMilli(workout.startedAt)
        val end = maxOf(Instant.ofEpochMilli(workout.endedAt), start.plusSeconds(1))
        val zone = ZoneId.systemDefault().rules
        val device = Device(type = Device.TYPE_HEARABLE)
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zone.getOffset(start),
                endTime = end,
                endZoneOffset = zone.getOffset(end),
                metadata = Metadata.activelyRecorded(device),
                exerciseType = workout.type.healthConnectType,
            ),
            ActiveCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = zone.getOffset(start),
                endTime = end,
                endZoneOffset = zone.getOffset(end),
                energy = Energy.kilocalories(workout.calories),
                metadata = Metadata.activelyRecorded(device),
            ),
        )
        // The heart rate screen's own Health Connect saving already stores these readings.
        if (!heartRateMonitor.saveToHealthConnect) {
            readings.chunked(HEART_RATE_CHUNK).forEach { chunk ->
                val chunkStart = chunk.first().time
                val chunkEnd = maxOf(chunk.last().time, chunkStart.plusSeconds(1))
                records += HeartRateRecord(
                    startTime = chunkStart,
                    startZoneOffset = zone.getOffset(chunkStart),
                    endTime = chunkEnd,
                    endZoneOffset = zone.getOffset(chunkEnd),
                    samples = chunk,
                    metadata = Metadata.activelyRecorded(device),
                )
            }
        }
        return records
    }

    companion object {
        private const val TAG = "WorkoutTracker"
        private const val PREF_HEALTH_CONNECT = "workout_health_connect"
        private const val PREF_AGE = "workout_age"
        private const val PREF_WEIGHT = "workout_weight_kg"
        private const val PREF_SEX = "workout_sex"
        private const val MAX_GAP_SECONDS = 10.0
        private const val HEART_RATE_CHUNK = 300

        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HeartRateMonitor.WRITE_PERMISSION,
        )
    }
}
