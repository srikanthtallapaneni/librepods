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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.HeartRateMonitor

@Composable
fun HeartRateScreen(viewModel: AirPodsViewModel, navigateToWorkout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val bpm by viewModel.heartRate.collectAsState()
    val measuring by viewModel.heartRateMeasuring.collectAsState()

    val sensorOn = state.controlStates[ControlCommandIdentifiers.HRM_STATE]?.getOrNull(0) == 0x01.toByte()
    val healthConnect = remember { HeartRateMonitor.healthConnectClient(context) }
    var saveToHealthConnect by remember { mutableStateOf(viewModel.saveHeartRateToHealthConnect) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val allowed = HeartRateMonitor.WRITE_PERMISSION in granted
        viewModel.saveHeartRateToHealthConnect = allowed
        saveToHealthConnect = allowed
    }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        if (measuring) {
            Text(
                text = bpm?.let { stringResource(R.string.heart_rate_bpm, it) }
                    ?: stringResource(R.string.heart_rate_waiting),
                style = if (bpm != null) MaterialTheme.typography.displayMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (bpm != null) FontWeight.Bold else null,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            )
        }

        StyledToggle(
            label = stringResource(R.string.heart_rate_sensor),
            description = stringResource(R.string.heart_rate_sensor_description),
            checked = sensorOn,
            onCheckedChange = {
                viewModel.setControlCommandBoolean(ControlCommandIdentifiers.HRM_STATE, it)
                if (!it && measuring) viewModel.setHeartRateMeasuring(false)
            }
        )

        StyledToggle(
            label = stringResource(R.string.heart_rate_measure),
            description = stringResource(R.string.heart_rate_measure_description),
            checked = measuring,
            enabled = sensorOn,
            onCheckedChange = viewModel::setHeartRateMeasuring
        )

        StyledToggle(
            label = stringResource(R.string.heart_rate_health_connect),
            description = stringResource(
                if (healthConnect != null) R.string.heart_rate_health_connect_description
                else R.string.heart_rate_health_connect_unavailable
            ),
            checked = saveToHealthConnect && healthConnect != null,
            enabled = healthConnect != null,
            onCheckedChange = { enabled ->
                if (!enabled || healthConnect == null) {
                    viewModel.saveHeartRateToHealthConnect = false
                    saveToHealthConnect = false
                    return@StyledToggle
                }
                scope.launch {
                    val granted = healthConnect.permissionController.getGrantedPermissions()
                    if (HeartRateMonitor.WRITE_PERMISSION in granted) {
                        viewModel.saveHeartRateToHealthConnect = true
                        saveToHealthConnect = true
                    } else {
                        permissionLauncher.launch(setOf(HeartRateMonitor.WRITE_PERMISSION))
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        StyledListItem(
            name = stringResource(R.string.workout),
            description = stringResource(R.string.workout_description),
            onClick = navigateToWorkout
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
