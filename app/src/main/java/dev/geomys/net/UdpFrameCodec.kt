package dev.geomys.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * UDP datagrams are carried over the AF_UNIX byte stream as:
 *   uint16 big-endian payload length, followed by the payload.
 */
object UdpFrameCodec {
    const val MAX_PAYLOAD = 65507

    fun encode(payload: ByteArray, out: OutputStream) {
        require(payload.size <= MAX_PAYLOAD) { "Datagram is too large" }
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        out.flush()
    }

    fun encodeToByteArray(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "Datagram is too large" }
        val result = ByteArray(payload.size + 2)
        result[0] = ((payload.size ushr 8) and 0xFF).toByte()
        result[1] = (payload.size and 0xFF).toByte()
        payload.copyInto(result, 2)
        return result
    }

    /**
     * Returns null on a clean EOF before a new frame starts.
     */
    fun decode(input: InputStream): ByteArray? {
        val high = input.read()
        if (high < 0) return null
        val low = input.read()
        if (low < 0) throw EOFException("Truncated UDP frame length")
        val length = (high shl 8) or low
        if (length > MAX_PAYLOAD) throw IOException("UDP frame too large: $length")
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) throw EOFException("Truncated UDP frame payload")
            offset += read
        }
        return payload
    }
}
