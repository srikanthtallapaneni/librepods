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

package me.kavishdevar.librepods

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.services.ServiceManager

private const val TAG = "VoiceReplyActivity"

// Time for the AirPods mic (SCO) to open before listening, so the start of the reply isn't lost.
private const val MIC_WARMUP_MS = 700L

/**
 * Voice reply to an announced message, started by a nod. It's a small window rather than work in
 * the background because Android only lets an app use the microphone while it's visible.
 */
class VoiceReplyActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var status by mutableStateOf("")
    private var heard by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val action = intent.getParcelableExtra<Notification.Action>(EXTRA_ACTION)
        val sender = intent.getStringExtra(EXTRA_SENDER)
        val remoteInputs = action?.remoteInputs
        if (action == null || remoteInputs.isNullOrEmpty()) {
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) {
            Log.w(TAG, "Can't listen: no mic permission or no speech recognizer")
            ServiceManager.getService()?.announce(getString(R.string.voice_reply_unavailable))
            finish()
            return
        }

        status = sender?.let { getString(R.string.voice_reply_to, it) } ?: getString(R.string.voice_reply_listening)
        val m3eEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("m3e_enabled", true)
        setContent {
            LibrePodsTheme(m3eEnabled = m3eEnabled) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
                            .padding(24.dp)
                    ) {
                        Text(text = status, style = MaterialTheme.typography.titleMedium)
                        if (heard.isNotEmpty()) {
                            Text(
                                text = heard,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        ServiceManager.getService()?.startAirPodsMic()
        handler.postDelayed({ listen(action.actionIntent, remoteInputs) }, MIC_WARMUP_MS)
    }

    private fun listen(replyIntent: PendingIntent, remoteInputs: Array<RemoteInput>) {
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { heard = it }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (text.isNullOrEmpty()) {
                    fail()
                    return
                }
                heard = text
                val sent = sendReply(this@VoiceReplyActivity, replyIntent, remoteInputs, text)
                ServiceManager.getService()?.announce(
                    getString(if (sent) R.string.voice_reply_sent else R.string.voice_reply_failed)
                )
                status = getString(if (sent) R.string.voice_reply_sent else R.string.voice_reply_failed)
                handler.postDelayed({ finish() }, 1_000)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Speech recognition error $error")
                fail()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    private fun fail() {
        status = getString(R.string.voice_reply_not_heard)
        ServiceManager.getService()?.announce(getString(R.string.voice_reply_not_heard))
        handler.postDelayed({ finish() }, 1_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ACTION = "reply_action"
        private const val EXTRA_SENDER = "sender"

        fun intent(context: Context, action: Notification.Action, sender: String?): Intent =
            Intent(context, VoiceReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_SENDER, sender)

        /** Fills the notification's reply field with [text] and fires its reply action. */
        fun sendReply(
            context: Context,
            replyIntent: PendingIntent,
            remoteInputs: Array<RemoteInput>,
            text: String
        ): Boolean {
            val results = Bundle().apply { remoteInputs.forEach { putCharSequence(it.resultKey, text) } }
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
            return try {
                replyIntent.send(context, 0, fillIn)
                true
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Reply action was cancelled: ${e.message}")
                false
            }
        }
    }
}
