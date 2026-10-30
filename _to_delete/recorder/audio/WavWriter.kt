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

import java.io.File
import java.io.RandomAccessFile

/** Writes mono 16-bit PCM to a WAV file. The header's sizes and rate are filled in on [close]. */
class WavWriter(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataSize = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE))
    }

    fun write(pcm: ByteArray) {
        raf.write(pcm)
        dataSize += pcm.size
    }

    /** Finishes the file; [sampleRate] is the decoder's real output rate. */
    fun close(sampleRate: Int) {
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeIntLE((36 + dataSize).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1) // PCM
        raf.writeShortLE(CHANNELS)
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(sampleRate * CHANNELS * BYTES_PER_SAMPLE)
        raf.writeShortLE(CHANNELS * BYTES_PER_SAMPLE)
        raf.writeShortLE(BYTES_PER_SAMPLE * 8)
        raf.writeBytes("data")
        raf.writeIntLE(dataSize.toInt())
        raf.close()
    }

    override fun close() = close(48_000)

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private companion object {
        const val HEADER_SIZE = 44
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
    }
}
