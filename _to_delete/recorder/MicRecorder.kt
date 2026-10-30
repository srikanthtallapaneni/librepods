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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.audio.EldDecoder
import me.kavishdevar.librepods.audio.WavWriter
import me.kavishdevar.librepods.bluetooth.AACPManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Records the AirPods' high quality mic stream to WAV files, without switching the AirPods to the
 * headset profile, so music keeps playing at full quality. In-app only: Android has no way to offer
 * this stream to other apps as a microphone.
 */
class MicRecorder(
    context: Context,
    private val aacpManager: AACPManager,
    private val device: () -> BluetoothDevice?,
) {
    data class State(
        val isRecording: Boolean = false,
        val startedAt: Long = 0L,
        // Peak level of the latest audio, 0 to 1, for a level meter.
        val level: Float = 0f,
        val framesReceived: Long = 0L,
    )

    private val appContext = context.applicationContext
    // One thread, so frames are decoded and written in order and never during close.
    private val audioThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + audioThread)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _recordings = MutableStateFlow(listRecordings())
    /** Saved recordings, newest first. */
    val recordings: StateFlow<List<File>> = _recordings.asStateFlow()

    private var decoder: EldDecoder? = null
    private var writer: WavWriter? = null

    init {
        aacpManager.microphoneFrameListener = { frame -> scope.launch { onFrame(frame) } }
        aacpManager.disconnectListeners.add { stop() }
    }

    private val recordingsDir: File
        get() = (appContext.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
            ?: File(appContext.filesDir, "Recordings")).apply { mkdirs() }

    private fun listRecordings(): List<File> =
        recordingsDir.listFiles { file -> file.extension == "wav" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun start(): Boolean {
        if (_state.value.isRecording) return true
        val name = "AirPods ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())}.wav"
        val file = File(recordingsDir, name)
        try {
            decoder = EldDecoder()
            writer = WavWriter(file)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't set up recording", e)
            closeFiles()
            return false
        }
        if (!aacpManager.sendStartMicrophoneStream()) {
            closeFiles()
            file.delete()
            return false
        }
        _state.value = State(isRecording = true, startedAt = System.currentTimeMillis())
        return true
    }

    fun stop() {
        if (!_state.value.isRecording) return
        aacpManager.sendStopMicrophoneStream()
        _state.value = State()
        scope.launch {
            closeFiles()
            _recordings.value = listRecordings()
        }
        restartA2dp()
    }

    fun delete(file: File) {
        if (file.parentFile == recordingsDir && file.delete()) _recordings.value = listRecordings()
    }

    private fun onFrame(frame: ByteArray) {
        if (!_state.value.isRecording) return
        val writer = writer ?: return
        try {
            decoder?.decode(frame) { pcm ->
                writer.write(pcm)
                _state.update { it.copy(level = peak(pcm), framesReceived = it.framesReceived + 1) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't decode a mic frame: ${e.message}")
        }
    }

    private fun closeFiles() {
        val rate = decoder?.sampleRate ?: 48_000
        try {
            writer?.close(rate)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't finish the recording file", e)
        }
        try {
            decoder?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't release the decoder", e)
        }
        writer = null
        decoder = null
    }

    private fun peak(pcm: ByteArray): Float {
        var max = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            max = maxOf(max, abs(sample))
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }

    /**
     * Upstream found that playback can stay degraded after the mic stream ends, and reconnects
     * A2DP to fix it. connect/disconnect are hidden APIs, so this may be refused; it's best effort.
     */
    @SuppressLint("MissingPermission")
    private fun restartA2dp() {
        val target = device() ?: return
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                try {
                    val disconnect = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                    val connect = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    disconnect.invoke(proxy, target)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            connect.invoke(proxy, target)
                        } catch (e: Exception) {
                            Log.w(TAG, "Couldn't reconnect A2DP: ${e.message}")
                        }
                        adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy as BluetoothA2dp)
                    }, 500)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't restart A2DP: ${e.message}")
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    companion object {
        private const val TAG = "MicRecorder"
    }
}
