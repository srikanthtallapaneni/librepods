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

package me.kavishdevar.librepods.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

private const val TAG = "Announcer"

/**
 * Speaks short announcements (messages, callers) over the current audio route, ducking whatever
 * is playing while it talks.
 */
class Announcer(context: Context) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .build()

    // Utterance callbacks arrive on a TTS binder thread, and stop() is called from the AACP reader.
    // Tracking the IDs in flight means a late onStop for an utterance stop() already dropped can't
    // release audio focus while a newer announcement is playing.
    private val inFlight = mutableSetOf<String>()
    private val pending = mutableListOf<Pair<String, (() -> Unit)?>>()
    // Run when an utterance finishes playing (not when it's stopped or fails).
    private val onDoneCallbacks = mutableMapOf<String, () -> Unit>()
    @Volatile
    private var ready = false

    /** True once the TTS engine failed to start; the owner should drop this instance and retry. */
    @Volatile
    var failed = false
        private set

    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed: $status")
            synchronized(inFlight) {
                failed = true
                pending.clear()
            }
            return
        }
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId, completed = true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceFinished(utteranceId)
        })
        synchronized(inFlight) {
            ready = true
            pending.forEach { (text, onDone) -> speakNow(text, onDone) }
            pending.clear()
        }
    }

    /** Speaks [text]; [onDone] runs on a TTS thread once it has played to the end. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        synchronized(inFlight) {
            when {
                failed -> return
                ready -> speakNow(text, onDone)
                else -> pending.add(text to onDone)
            }
        }
    }

    fun stop() {
        synchronized(inFlight) {
            pending.clear()
            if (!ready) return
            // tts.stop() drops queued utterances without callbacks, so release focus here. Cleared
            // first, so the onStop that does arrive for the current utterance is ignored.
            inFlight.clear()
            onDoneCallbacks.clear()
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    // Called with the inFlight lock held.
    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        val utteranceId = "librepods_${System.nanoTime()}"
        if (inFlight.isEmpty()) audioManager.requestAudioFocus(focusRequest)
        inFlight.add(utteranceId)
        onDone?.let { onDoneCallbacks[utteranceId] = it }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun onUtteranceFinished(utteranceId: String?, completed: Boolean = false) {
        val onDone = synchronized(inFlight) {
            if (utteranceId == null || !inFlight.remove(utteranceId)) return
            if (inFlight.isEmpty()) audioManager.abandonAudioFocusRequest(focusRequest)
            onDoneCallbacks.remove(utteranceId)
        }
        // Outside the lock: the callback may start another announcement.
        if (completed) onDone?.invoke()
    }
}
