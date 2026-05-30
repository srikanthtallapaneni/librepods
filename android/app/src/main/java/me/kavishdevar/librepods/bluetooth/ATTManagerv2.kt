package me.kavishdevar.librepods.bluetooth

import android.util.Log

private const val TAG = "ATTManagerv2"

// the random disconnects were because of ATT, apparently. seems like we will have to accept no notifications for external changes (mainly amplification in hearing aid)
object ATTManagerv2 {
     fun readCharacteristic(handle: ATTHandles): ByteArray? {
        val socket = BluetoothConnectionManager.getATTSocket()?: return null
        try {
//            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream

            val pdu = byteArrayOf(0x0A, handle.value.toByte(), 0x00)
            output.write(pdu)
            output.flush()
            Log.d(TAG, "writeRaw: ${pdu.joinToString(" ") { String.format("%02X", it) }}")
            val buffer = ByteArray(512)
            val len = input.read(buffer)
            if (len == -1) {
                throw IllegalStateException("End of stream reached")
            }
            val data = buffer.copyOfRange(0, len)
//            socket.close()
            if (data[0] != 0x0B.toByte()) {
                throw IllegalStateException("Invalid response: ${data.joinToString(" ") { String.format("%02X", it) }}")
            }
            Log.d(TAG, "readPDU: ${data.joinToString(" ") { String.format("%02X", it) }}")
            return data.copyOfRange(1, data.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading characteristic: ${e.message}")
            return null
        }
    }

    fun writeCharacteristic(handle: ATTHandles, data: ByteArray) {
        val socket = BluetoothConnectionManager.getATTSocket()?: return
        try {
//            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val pdu = byteArrayOf(0x12, handle.value.toByte(), 0x00) + data // 0x0 because LE
            output.write(pdu)
            output.flush()
            Log.d(TAG, "writeRaw: ${pdu.joinToString(" ") { String.format("%02X", it) }}")
            val buffer = ByteArray(512)
            val len = input.read(buffer)
            if (len == -1) {
                throw IllegalStateException("End of stream reached")
            }
            val resp = buffer.copyOfRange(0, len)
//            socket.close()
            if (!resp.contentEquals(byteArrayOf(0x13))) {
                throw IllegalStateException("Invalid response: ${resp.joinToString(" ") { String.format("%02X", it) }}")
            }
            Log.d(TAG, "readPDU: ${resp.joinToString(" ") { String.format("%02X", it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing characteristic: ${e.message}")
        }
    }
}
