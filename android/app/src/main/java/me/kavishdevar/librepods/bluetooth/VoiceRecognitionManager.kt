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

package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "VoiceRecognitionManager"

// MediaRecorder.AudioSource.HOTWORD is a system API. Always-on "Hey Google" detection records with
// it, so it must not count as the assistant still listening.
private const val AUDIO_SOURCE_HOTWORD = 1999

// Give up if the assistant hasn't started recording by then (it didn't open, or it was blocked).
private const val NO_RECORDING_TIMEOUT_MS = 10_000L

// Upper bound so the SCO link can never stay open, and music stay in call quality, indefinitely.
private const val MAX_SESSION_MS = 5 * 60_000L

/**
 * Routes the digital assistant's microphone to the AirPods, the way Siri uses them on Apple
 * devices. Starting HFP voice recognition opens the SCO audio link, so whatever records next hears
 * the AirPods mic. Once the assistant stops recording, voice recognition is stopped again so A2DP
 * playback returns.
 */
class VoiceRecognitionManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var headset: BluetoothHeadset? = null
    private var activeDevice: BluetoothDevice? = null
    private var assistantRecording = false

    // Recordings already running when voice recognition started (a screen recorder, a VoIP app),
    // so they aren't mistaken for the assistant and don't hold the SCO link open.
    @Volatile
    private var preexistingSessions: Set<Int> = emptySet()

    private val stopRunnable = Runnable {
        Log.d(TAG, "Voice recognition timed out")
        stop()
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) headset = proxy as? BluetoothHeadset
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) headset = null
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val recording = configs.any {
                it.clientAudioSource != AUDIO_SOURCE_HOTWORD && it.clientAudioSessionId !in preexistingSessions
            }
            if (recording && !assistantRecording) {
                assistantRecording = true
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, MAX_SESSION_MS)
            } else if (!recording && assistantRecording) {
                Log.d(TAG, "Assistant stopped recording")
                stop()
            }
        }
    }

    init {
        // The proxy connects asynchronously, so get it now rather than on the first press.
        bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    fun start(device: BluetoothDevice): Boolean {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted, can't use the AirPods mic")
            return false
        }
        val proxy = headset ?: run {
            Log.w(TAG, "Headset profile not connected yet, can't use the AirPods mic")
            return false
        }
        stop()
        val started = try {
            proxy.startVoiceRecognition(device)
        } catch (e: SecurityException) {
            Log.w(TAG, "startVoiceRecognition denied: ${e.message}")
            false
        }
        if (!started) {
            // Fails during a call or when the AirPods aren't the HFP device; the assistant still
            // opens, just with the phone mic.
            Log.w(TAG, "startVoiceRecognition failed")
            return false
        }
        activeDevice = device
        assistantRecording = false
        preexistingSessions = audioManager.activeRecordingConfigurations
            .map { it.clientAudioSessionId }
            .toSet()
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)
        handler.postDelayed(stopRunnable, NO_RECORDING_TIMEOUT_MS)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val device = activeDevice ?: return
        activeDevice = null
        assistantRecording = false
        preexistingSessions = emptySet()
        handler.removeCallbacks(stopRunnable)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        try {
            headset?.stopVoiceRecognition(device)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopVoiceRecognition denied: ${e.message}")
        }
    }

    fun close() {
        stop()
        headset?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        headset = null
    }
}
