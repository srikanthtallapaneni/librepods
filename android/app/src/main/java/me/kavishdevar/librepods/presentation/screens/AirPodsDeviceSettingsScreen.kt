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

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.presentation.components.AirPodsDeviceSettings
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.services.NotificationAnnouncerService

@Composable
fun AirPodsDeviceSettingsScreen(
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navigateToEngraving: () -> Unit,
    navigateToRename: () -> Unit,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit,
    navigateToAdaptiveStrength: () -> Unit,
    navigateToEqualizer: () -> Unit,
    navigateToHeadTracking: () -> Unit,
    navigateToAccessibility: () -> Unit,
    navigateToVersion: () -> Unit,
    navigateToCallControlScreen: (action: String) -> Unit,
    navigateToMicrophoneSettings: () -> Unit,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()

    // These settings write to the AirPods, so leave once they disconnect (demo mode stays connected).
    LaunchedEffect(state.isLocallyConnected) {
        if (!state.isLocallyConnected) navigateBack()
    }

    // Notification access can be granted or revoked in system settings, so re-check on every resume.
    var hasNotificationAccess by remember { mutableStateOf(hasNotificationListenerAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasNotificationAccess = hasNotificationListenerAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

        AirPodsDeviceSettings(
            state = state,
            setControlCommandBoolean = viewModel::setControlCommandBoolean,
            setATTCharacteristicValue = viewModel::setATTCharacteristicValue,
            onAutomaticEarDetectionChanged = viewModel::setAutomaticEarDetectionEnabled,
            onAutomaticConnectionChanged = viewModel::setAutomaticConnectionEnabled,
            setDynamicEndOfCharge = viewModel::setDynamicEndOfCharge,
            navigateToRename = navigateToRename,
            navigateToHearingProtection = navigateToHearingProtection,
            navigateToHearingAid = navigateToHearingAid,
            navigateToLeftLongPress = navigateToLeftLongPress,
            navigateToRightLongPress = navigateToRightLongPress,
            navigateToAdaptiveStrength = navigateToAdaptiveStrength,
            navigateToEqualizer = navigateToEqualizer,
            navigateToHeadTracking = navigateToHeadTracking,
            navigateToAccessibility = navigateToAccessibility,
            navigateToVersion = navigateToVersion,
            navigateToCallControlScreen = navigateToCallControlScreen,
            navigateToMicrophoneSettings = navigateToMicrophoneSettings,
            afterName = {
                Spacer(modifier = Modifier.height(16.dp))
                StyledListItem(
                    name = stringResource(R.string.engraving),
                    description = appState.engraving.ifEmpty { stringResource(R.string.engraving_not_set) },
                    onClick = navigateToEngraving,
                )
            },
            afterAudio = {
                if (state.capabilities.contains(Capability.CONVERSATION_AWARENESS)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    StyledList(title = stringResource(R.string.conversation_awareness)) {
                        StyledToggle(
                            label = stringResource(R.string.conversation_awareness_pause_music),
                            description = stringResource(R.string.conversation_awareness_pause_music_description),
                            checked = appState.conversationAwarenessPauseMusicEnabled,
                            onCheckedChange = appSettingsViewModel::setConversationAwarenessPauseMusicEnabled
                        )

                        StyledToggle(
                            label = stringResource(R.string.relative_conversation_awareness_volume),
                            description = stringResource(R.string.relative_conversation_awareness_volume_description),
                            checked = appState.relativeConversationAwarenessVolumeEnabled,
                            onCheckedChange = appSettingsViewModel::setRelativeConversationAwarenessVolumeEnabled
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    StyledSlider(
                        label = stringResource(R.string.conversation_awareness_volume),
                        value = appState.conversationAwarenessVolume,
                        valueRange = 10f..85f,
                        snapPoints = listOf(44f),
                        startLabel = stringResource(R.string.percent_value, 10),
                        endLabel = stringResource(R.string.percent_value, 85),
                        onValueChange = appSettingsViewModel::setConversationAwarenessVolume,
                        independent = true,
                        enabled = true
                    )
                }
            },
            beforeAccessibility = {
                Spacer(modifier = Modifier.height(16.dp))
                StyledList(title = stringResource(R.string.announcements)) {
                    StyledToggle(
                        label = stringResource(R.string.battery_alerts),
                        description = stringResource(R.string.battery_alerts_description),
                        checked = appState.batteryAlerts,
                        onCheckedChange = appSettingsViewModel::setBatteryAlerts
                    )

                    // The notification listener is only in the FOSS build (Play restricts notification access).
                    if (!BuildConfig.PLAY_BUILD) {
                        // Shown on only when notification access is actually granted.
                        StyledToggle(
                            label = stringResource(R.string.announce_notifications),
                            description = stringResource(R.string.announce_notifications_description),
                            checked = appState.announceNotifications && hasNotificationAccess,
                            onCheckedChange = { enabled ->
                                appSettingsViewModel.setAnnounceNotifications(enabled)
                                if (enabled) requestNotificationListenerAccess(context)
                            }
                        )

                        StyledToggle(
                            label = stringResource(R.string.announce_calls),
                            description = stringResource(R.string.announce_calls_description),
                            checked = appState.announceCalls && hasNotificationAccess,
                            onCheckedChange = { enabled ->
                                appSettingsViewModel.setAnnounceCalls(enabled)
                                if (enabled) requestNotificationListenerAccess(context)
                            }
                        )
                    }
                }
            },
        )

        Spacer(modifier = Modifier.height(bottomPadding + 16.dp))
    }
}

private fun hasNotificationListenerAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun requestNotificationListenerAccess(context: Context) {
    if (hasNotificationListenerAccess(context)) return
    val component = ComponentName(context, NotificationAnnouncerService::class.java)
    try {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        )
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Log.w("AirPodsDeviceSettings", "No notification access settings screen on this device")
        }
    }
}
