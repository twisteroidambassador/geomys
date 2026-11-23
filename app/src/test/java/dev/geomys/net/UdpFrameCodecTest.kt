package dev.geomys.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class UdpFrameCodecTest {
    @Test
    fun roundTrip() {
        val payload = byteArrayOf(0, 1, 2, 127, -1, 42)
        val out = ByteArrayOutputStream()
        UdpFrameCodec.encode(payload, out)
        val decoded = UdpFrameCodec.decode(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(payload, decoded)
        assertNull(UdpFrameCodec.decode(ByteArrayInputStream(out.toByteArray()).also { it.skip(out.size().toLong()) }))
    }

    @Test
    fun zeroLengthDatagram() {
        val out = ByteArrayOutputStream()
        UdpFrameCodec.encode(ByteArray(0), out)
        assertEquals(2, out.size())
        assertArrayEquals(ByteArray(0), UdpFrameCodec.decode(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun maxPayload() {
        val payload = ByteArray(UdpFrameCodec.MAX_PAYLOAD) { (it % 251).toByte() }
        val encoded = UdpFrameCodec.encodeToByteArray(payload)
        assertArrayEquals(payload, UdpFrameCodec.decode(ByteArrayInputStream(encoded)))
    }

    @Test
    fun oversizedPayloadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UdpFrameCodec.encodeToByteArray(ByteArray(UdpFrameCodec.MAX_PAYLOAD + 1))
        }
    }

    @Test
    fun truncatedLengthIsRejected() {
        assertThrows(EOFException::class.java) {
            UdpFrameCodec.decode(ByteArrayInputStream(byteArrayOf(0)))
        }
    }

    @Test
    fun truncatedPayloadIsRejected() {
        assertThrows(EOFException::class.java) {
            UdpFrameCodec.decode(ByteArrayInputStream(byteArrayOf(0, 5, 1, 2)))
        }
    }

    @Test
    fun oversizedFrameIsRejected() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertThrows(IOException::class.java) {
            UdpFrameCodec.decode(ByteArrayInputStream(bytes))
        }
    }
}
