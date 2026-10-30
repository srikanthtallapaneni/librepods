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

package me.kavishdevar.librepods.presentation.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.HeartRateMonitor
import me.kavishdevar.librepods.services.WorkoutTracker
import kotlin.math.roundToInt

@Composable
fun WorkoutScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val tracker = viewModel.workoutTracker
    val state by viewModel.uiState.collectAsState()
    val workout by tracker.state.collectAsState()
    val bpm by viewModel.heartRate.collectAsState()

    val sensorOn = state.controlStates[ControlCommandIdentifiers.HRM_STATE]?.getOrNull(0) == 0x01.toByte()
    val healthConnect = remember { HeartRateMonitor.healthConnectClient(context) }
    var saveToHealthConnect by remember { mutableStateOf(tracker.saveToHealthConnect) }
    var type by remember { mutableStateOf(workout.type) }
    var sex by remember { mutableStateOf(tracker.sex) }
    var age by remember { mutableFloatStateOf(tracker.age.toFloat()) }
    var weight by remember { mutableFloatStateOf(tracker.weightKg.toFloat()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val allowed = granted.containsAll(WorkoutTracker.PERMISSIONS)
        tracker.saveToHealthConnect = allowed
        saveToHealthConnect = allowed
    }

    // Ticks the timer during a workout.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout.active) {
        while (workout.active) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .layerBackdrop(backdrop)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        val finished = !workout.active && workout.endedAt > 0
        if (workout.active || finished) {
            if (finished) {
                Text(
                    text = stringResource(R.string.workout_last),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )
            }
            val end = if (workout.active) now else workout.endedAt
            Text(
                text = DateUtils.formatElapsedTime((end - workout.startedAt).coerceAtLeast(0) / 1000),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                if (workout.active) Stat(stringResource(R.string.workout_now), bpm?.toString())
                Stat(stringResource(R.string.workout_average), workout.averageBpm?.toString())
                Stat(stringResource(R.string.workout_max), workout.maxBpm?.toString())
                Stat(
                    stringResource(R.string.workout_calories),
                    stringResource(R.string.workout_kcal, workout.calories.roundToInt())
                )
            }
            if (finished && workout.saved != null) {
                Text(
                    text = stringResource(if (workout.saved == true) R.string.workout_saved else R.string.workout_not_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        } else {
            Text(
                text = stringResource(if (sensorOn) R.string.workout_description else R.string.workout_sensor_off),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
            )
        }

        if (!workout.active) {
            StyledList(title = stringResource(R.string.workout_type)) {
                WorkoutTracker.Type.entries.forEach { option ->
                    StyledListItem(
                        name = stringResource(option.label),
                        selected = type == option,
                        onClick = { type = option }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        StyledButton(
            onClick = { if (workout.active) tracker.stop() else tracker.start(type) },
            backdrop = backdrop,
            enabled = workout.active || sensorOn,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(if (workout.active) R.string.workout_stop else R.string.workout_start),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        StyledToggle(
            label = stringResource(R.string.heart_rate_health_connect),
            description = stringResource(
                if (healthConnect != null) R.string.workout_health_connect_description
                else R.string.heart_rate_health_connect_unavailable
            ),
            checked = saveToHealthConnect && healthConnect != null,
            enabled = healthConnect != null,
            onCheckedChange = { enabled ->
                if (!enabled || healthConnect == null) {
                    tracker.saveToHealthConnect = false
                    saveToHealthConnect = false
                    return@StyledToggle
                }
                scope.launch {
                    val granted = healthConnect.permissionController.getGrantedPermissions()
                    if (granted.containsAll(WorkoutTracker.PERMISSIONS)) {
                        tracker.saveToHealthConnect = true
                        saveToHealthConnect = true
                    } else {
                        permissionLauncher.launch(WorkoutTracker.PERMISSIONS)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        StyledList(
            title = stringResource(R.string.workout_about_you),
            description = stringResource(R.string.workout_about_you_description)
        ) {
            listOf(
                WorkoutTracker.Sex.MALE to R.string.workout_male,
                WorkoutTracker.Sex.FEMALE to R.string.workout_female,
                WorkoutTracker.Sex.UNSET to R.string.workout_unset,
            ).forEach { (option, label) ->
                StyledListItem(
                    name = stringResource(label),
                    selected = sex == option,
                    onClick = {
                        sex = option
                        tracker.sex = option
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        StyledSlider(
            label = stringResource(R.string.workout_age, age.roundToInt()),
            value = age,
            onValueChange = {
                age = it
                tracker.age = it.roundToInt()
            },
            valueRange = 13f..90f,
            independent = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        StyledSlider(
            label = stringResource(R.string.workout_weight, weight.roundToInt()),
            value = weight,
            onValueChange = {
                weight = it
                tracker.weightKg = it.roundToInt()
            },
            valueRange = 30f..200f,
            independent = true
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String?) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = value ?: "–",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val WorkoutTracker.Type.label: Int
    get() = when (this) {
        WorkoutTracker.Type.WALKING -> R.string.workout_walking
        WorkoutTracker.Type.RUNNING -> R.string.workout_running
        WorkoutTracker.Type.CYCLING -> R.string.workout_cycling
        WorkoutTracker.Type.HIKING -> R.string.workout_hiking
        WorkoutTracker.Type.STRENGTH -> R.string.workout_strength
        WorkoutTracker.Type.OTHER -> R.string.workout_other
    }
