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

import java.io.ByteArrayOutputStream

/**
 * The AirPods' sensor channel (RTBuddy descriptor SENSOR_DATA_WX), which carries heart rate and
 * motion data. It rides on AACP opcode 0x17, the same opcode as the head tracking packets, and its
 * body is a small protobuf message (`SensorDataWX`). Only the few fields we need are encoded and
 * decoded here, so the app doesn't need a protobuf library. Field numbers follow upstream
 * LibrePods' `rtbuddy.proto` (android/rewrite branch).
 */
object SensorData {
    const val DESCRIPTOR_SENSOR_DATA_WX = 0x00100000

    // SensorServiceType values from rtbuddy.proto.
    const val SERVICE_HEARTRATE = 19
    const val SERVICE_HEARTRATE_V2 = 20
    const val SERVICE_HEARTRATE_COMMAND = 84

    // SensorDataWX fields.
    private const val FIELD_SEQ = 1
    private const val FIELD_COMMAND = 7
    private const val FIELD_SERVICE_SETTINGS = 8

    // SensorCommand / SensorServiceSetting fields.
    private const val FIELD_SERVICE = 1
    private const val FIELD_SETTING = 2
    private const val FIELD_PAYLOAD = 3

    private var sequence = 0

    /**
     * The full AACP data (opcode first, without the 04 00 04 00 header) that sets how often [service]
     * reports, in microseconds. An interval of 0 stops it.
     */
    fun createReportIntervalPacket(service: Int, intervalMicros: Long): ByteArray {
        val configuration = byteArrayOf(
            0x01,
            intervalMicros.toByte(),
            (intervalMicros shr 8).toByte(),
            (intervalMicros shr 16).toByte(),
            (intervalMicros shr 24).toByte()
        )
        val setting = ProtoWriter()
            .varint(FIELD_SERVICE, service.toLong())
            .varint(FIELD_SETTING, 2)
            .bytes(FIELD_PAYLOAD, configuration)
            .toByteArray()
        val seq = synchronized(this) { sequence.also { sequence = (sequence + 1) and 0x7FFFFFFF } }
        val message = ProtoWriter()
            .varint(FIELD_SEQ, seq.toLong())
            .bytes(FIELD_SERVICE_SETTINGS, setting)
            .toByteArray()
        return byteArrayOf(AACPManager.Companion.Opcodes.HEADTRACKING, 0x00) +
            le32(DESCRIPTOR_SENSOR_DATA_WX) +
            byteArrayOf((message.size and 0xFF).toByte(), ((message.size shr 8) and 0xFF).toByte()) +
            message
    }

    /** A sensor command from the AirPods: which service sent it, and its raw payload. */
    data class Command(val service: Int, val payload: ByteArray)

    /**
     * The sensor command in [packet] (a full AACP packet with the 04 00 04 00 header), or null if
     * it isn't a SENSOR_DATA_WX packet carrying one.
     */
    fun parseCommand(packet: ByteArray): Command? {
        if (packet.size < 12) return null
        if (packet[4] != AACPManager.Companion.Opcodes.HEADTRACKING) return null
        val descriptor = (packet[6].toInt() and 0xFF) or
            ((packet[7].toInt() and 0xFF) shl 8) or
            ((packet[8].toInt() and 0xFF) shl 16) or
            ((packet[9].toInt() and 0xFF) shl 24)
        if (descriptor != DESCRIPTOR_SENSOR_DATA_WX) return null
        val length = (packet[10].toInt() and 0xFF) or ((packet[11].toInt() and 0xFF) shl 8)
        if (packet.size < 12 + length) return null
        val message = packet.copyOfRange(12, 12 + length)
        val command = ProtoReader(message).fields()[FIELD_COMMAND] as? ByteArray ?: return null
        val fields = ProtoReader(command).fields()
        val service = (fields[FIELD_SERVICE] as? Long)?.toInt() ?: return null
        val payload = fields[FIELD_PAYLOAD] as? ByteArray ?: return null
        return Command(service, payload)
    }

    /**
     * Beats per minute from a heart rate command, or null if it isn't one or the AirPods flagged
     * the reading as not valid. Format from upstream: 18 bytes, BPM in byte 1, byte 17 non-zero
     * for a reading to skip.
     */
    fun parseHeartRate(command: Command): Int? {
        if (command.service != SERVICE_HEARTRATE && command.service != SERVICE_HEARTRATE_V2) return null
        val payload = command.payload
        if (payload.size != 18 || payload[17].toInt() != 0) return null
        return (payload[1].toInt() and 0xFF).takeIf { it > 0 }
    }

    private fun le32(value: Int) = byteArrayOf(
        value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()
    )

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) = apply {
            writeVarint((field shl 3).toLong())
            writeVarint(value)
        }

        fun bytes(field: Int, value: ByteArray) = apply {
            writeVarint(((field shl 3) or 2).toLong())
            writeVarint(value.size.toLong())
            out.write(value)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeVarint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
    }

    /** Reads the top-level fields of a message: varints as Long, length-delimited as ByteArray. */
    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0

        fun fields(): Map<Int, Any> {
            val result = mutableMapOf<Int, Any>()
            try {
                while (pos < data.size) {
                    val key = readVarint()
                    val field = (key ushr 3).toInt()
                    when ((key and 0x7).toInt()) {
                        0 -> result[field] = readVarint()
                        1 -> pos += 8
                        2 -> {
                            val length = readVarint().toInt()
                            if (length < 0 || pos + length > data.size) return result
                            result[field] = data.copyOfRange(pos, pos + length)
                            pos += length
                        }
                        5 -> pos += 4
                        else -> return result
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                // A truncated message: keep what was read.
            }
            return result
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IndexOutOfBoundsException()
            }
        }
    }
}
