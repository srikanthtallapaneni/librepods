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

package me.kavishdevar.librepods.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Decodes the AirPods' high quality mic stream (mono AAC-ELD) to 16-bit PCM with Android's own
 * AAC decoder. Config from upstream LibrePods (android/rewrite).
 */
class EldDecoder : AutoCloseable {
    companion object {
        // AudioSpecificConfig: AAC-ELD, 48 kHz, mono.
        private val ASC = byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30, 0x00)
        private const val DEFAULT_SAMPLE_RATE = 48_000
    }

    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()

    /** The decoder's output rate, known once it reports its output format. */
    var sampleRate = DEFAULT_SAMPLE_RATE
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, DEFAULT_SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(ASC))
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun decode(accessUnit: ByteArray, onPcm: (ByteArray) -> Unit) {
        val input = codec.dequeueInputBuffer(10_000)
        if (input >= 0) {
            codec.getInputBuffer(input)?.apply {
                clear()
                put(accessUnit)
            }
            codec.queueInputBuffer(input, 0, accessUnit.size, 0, 0)
        }
        while (true) {
            val output = codec.dequeueOutputBuffer(info, 0)
            when {
                output >= 0 -> {
                    val buffer = codec.getOutputBuffer(output) ?: run {
                        codec.releaseOutputBuffer(output, false)
                        return
                    }
                    val pcm = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(pcm)
                    codec.releaseOutputBuffer(output, false)
                    onPcm(pcm)
                }
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                else -> return
            }
        }
    }

    override fun close() {
        codec.stop()
        codec.release()
    }
}
