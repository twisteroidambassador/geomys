package dev.geomys.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointValidatorTest {
    @Test
    fun parsesIpv4AndIpv6() {
        assertTrue(EndpointValidator.parseHost("127.0.0.1").isLoopbackAddress)
        assertTrue(EndpointValidator.parseHost("::1").isLoopbackAddress)
        assertTrue(EndpointValidator.isLoopback("127.0.0.1"))
        assertFalse(EndpointValidator.isLoopback("10.1.2.3"))
        assertEquals("127.0.0.1", EndpointValidator.hostString(EndpointValidator.parseHost("127.0.0.1")))
    }

    @Test
    fun acceptsWildcardAddresses() {
        assertEquals("0.0.0.0", EndpointValidator.hostString(EndpointValidator.parseHost("0.0.0.0")))
        assertTrue(EndpointValidator.parseHost("::").isAnyLocalAddress)
    }

    @Test
    fun rejectsHostnames() {
        assertThrows(IllegalArgumentException::class.java) { EndpointValidator.parseHost("localhost") }
        assertThrows(IllegalArgumentException::class.java) { EndpointValidator.parseHost("example.com") }
    }

    @Test
    fun rejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { EndpointValidator.parseHost("") }
        assertThrows(IllegalArgumentException::class.java) { EndpointValidator.parseHost("999.1.1.1") }
        assertThrows(IllegalArgumentException::class.java) { EndpointValidator.parseHost("1.2.3") }
    }

    @Test
    fun validatesPortRange() {
        assertNull(EndpointValidator.validatePort(1))
        assertNull(EndpointValidator.validatePort(65535))
        assertEquals("Port must be between 1 and 65535", EndpointValidator.validatePort(0))
        assertEquals("Port must be between 1 and 65535", EndpointValidator.validatePort(65536))
    }
}
