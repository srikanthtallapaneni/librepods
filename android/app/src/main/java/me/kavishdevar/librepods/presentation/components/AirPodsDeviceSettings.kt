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

package me.kavishdevar.librepods.presentation.components

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.AirPodsPro3
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState

/**
 * Per-device AirPods settings, shown at the top of the Settings screen so the home screen can stay
 * focused on battery and listening mode.
 */
@Composable
fun AirPodsDeviceSettings(
    state: AirPodsUiState,

    setControlCommandBoolean: (ControlCommandIdentifiers, Boolean) -> Unit,
    setATTCharacteristicValue: (ATTHandles, ByteArray) -> Unit,
    onAutomaticEarDetectionChanged: (Boolean) -> Unit,
    onAutomaticConnectionChanged: (Boolean) -> Unit,
    setDynamicEndOfCharge: (Boolean) -> Unit,

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

    // Phone-side settings that belong with the AirPods but live in AppSettingsViewModel.
    afterName: @Composable () -> Unit = {},
    afterAudio: @Composable () -> Unit = {},
    beforeAccessibility: @Composable () -> Unit = {},
) {
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE)
    var deviceName by remember {
        mutableStateOf(sharedPreferences.getString("name", state.deviceName).toString())
    }

    val nameChangeListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "name") {
                deviceName = sharedPreferences.getString("name", "AirPods Pro").toString()
            }
        }
    }

    DisposableEffect(Unit) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(nameChangeListener)
        onDispose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(nameChangeListener)
        }
    }

    val capabilities = state.capabilities

    Column {
        StyledListItem(
            name = stringResource(R.string.name),
            description = deviceName,
            onClick = navigateToRename,
        )
        afterName()

        val hasHearingAidCapability =
            state.instance?.model?.capabilities?.contains(Capability.HEARING_AID) == true
        val hasPPECapability =
            state.instance?.model?.capabilities?.contains(Capability.PPE) == true

        if (hasHearingAidCapability || hasPPECapability) {
            if (hasPPECapability || state.vendorIdHook) {
                Spacer(modifier = Modifier.height(24.dp))
            }
            HearingHealthSettings(
                hasPPECapability = hasPPECapability,
                hasHearingAidCapability = hasHearingAidCapability,
                vendorIdHook = state.vendorIdHook,
                navigateToHearingProtection = navigateToHearingProtection,
                navigateToHearingAid = navigateToHearingAid
            )
        }

        if (capabilities.contains(Capability.STEM_CONFIG)) {
            Spacer(modifier = Modifier.height(16.dp))
            PressAndHoldSettings(
                leftAction = state.leftAction,
                rightAction = state.rightAction,
                navigateToLeftLongPress = navigateToLeftLongPress,
                navigateToRightLongPress = navigateToRightLongPress
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        val callBytes = state.controlStates[ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]
            ?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
        CallControlSettings(
            flipped = callBytes.getOrNull(1) == 0x02.toByte(),
            navigateToCallControlScreen = navigateToCallControlScreen
        )

        Spacer(modifier = Modifier.height(16.dp))
        val model = state.instance?.model ?: AirPodsPro3()
        AudioSettings(
            adaptiveVolumeCapability = model.capabilities.contains(Capability.ADAPTIVE_VOLUME),
            conversationAwarenessCapability = model.capabilities.contains(Capability.CONVERSATION_AWARENESS),
            loudSoundReductionCapability = model.capabilities.contains(Capability.LOUD_SOUND_REDUCTION),
            adaptiveAudioCapability = model.capabilities.contains(Capability.ADAPTIVE_VOLUME),
            customEqCapability = true,
            adaptiveVolumeChecked = state.controlStates[ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]
                ?.getOrNull(0) == 0x01.toByte(),
            onAdaptiveVolumeCheckedChange = { checked ->
                setControlCommandBoolean(ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG, checked)
            },
            conversationAwarenessChecked = state.controlStates[ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]
                ?.getOrNull(0) == 0x01.toByte(),
            onConversationAwarenessCheckedChange = { checked ->
                setControlCommandBoolean(ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, checked)
            },
            loudSoundReductionChecked = state.loudSoundReductionEnabled,
            onLoudSoundReductionCheckedChange = { checked ->
                setATTCharacteristicValue(
                    ATTHandles.LOUD_SOUND_REDUCTION,
                    byteArrayOf(if (checked) 0x01.toByte() else 0x00.toByte())
                )
            },
            navigateToAdaptiveStrength = navigateToAdaptiveStrength,
            navigateToEqualizer = navigateToEqualizer,
            vendorIdHook = state.vendorIdHook,
            isPremium = true
        )
        afterAudio()

        Spacer(modifier = Modifier.height(16.dp))
        ConnectionSettings(
            automaticEarDetectionEnabled = state.automaticEarDetectionEnabled,
            onAutomaticEarDetectionChanged = onAutomaticEarDetectionChanged,
            automaticConnectionEnabled = state.automaticConnectionEnabled,
            onAutomaticConnectionChanged = onAutomaticConnectionChanged
        )

        Spacer(modifier = Modifier.height(16.dp))
        StyledListItem(
            name = stringResource(R.string.microphone_mode),
            description = when (state.controlStates[ControlCommandIdentifiers.MIC_MODE]?.getOrNull(0)) {
                0x01.toByte() -> stringResource(R.string.microphone_always_right)
                0x02.toByte() -> stringResource(R.string.microphone_always_left)
                else -> stringResource(R.string.microphone_automatic)
            },
            onClick = navigateToMicrophoneSettings
        )

        if (capabilities.contains(Capability.SLEEP_DETECTION)) {
            Spacer(modifier = Modifier.height(16.dp))
            StyledToggle(
                label = stringResource(R.string.sleep_detection),
                checked = state.controlStates[ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG]
                    ?.getOrNull(0) == 0x01.toByte(),
                onCheckedChange = {
                    setControlCommandBoolean(ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG, it)
                }
            )
        }

        if (capabilities.contains(Capability.HEAD_GESTURES)) {
            Spacer(modifier = Modifier.height(16.dp))
            StyledListItem(
                name = stringResource(R.string.head_gestures),
                description = if (sharedPreferences.getBoolean("head_gestures", false)) {
                    stringResource(R.string.on)
                } else {
                    stringResource(R.string.off)
                },
                onClick = navigateToHeadTracking
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        StyledToggle(
            label = stringResource(R.string.optimized_charging),
            description = stringResource(R.string.optimized_charging_description),
            checked = state.dynamicEndOfCharge,
            onCheckedChange = setDynamicEndOfCharge
        )

        if (capabilities.contains(Capability.CASE_SOUNDS)) {
            Spacer(modifier = Modifier.height(16.dp))
            StyledToggle(
                label = stringResource(R.string.case_sounds),
                description = stringResource(R.string.case_sounds_description),
                checked = state.controlStates[ControlCommandIdentifiers.IN_CASE_TONE_CONFIG]
                    ?.getOrNull(0) != 0x02.toByte(),
                onCheckedChange = {
                    setControlCommandBoolean(ControlCommandIdentifiers.IN_CASE_TONE_CONFIG, it)
                }
            )
        }

        beforeAccessibility()

        Spacer(modifier = Modifier.height(16.dp))
        StyledListItem(
            name = stringResource(R.string.accessibility),
            onClick = navigateToAccessibility
        )

        Spacer(modifier = Modifier.height(32.dp))
        AboutCard(
            modelName = state.modelName,
            actualModel = state.actualModel,
            serialNumbers = state.serialNumbers,
            version = state.version3,
            navigateToVersion = navigateToVersion
        )
    }
}
