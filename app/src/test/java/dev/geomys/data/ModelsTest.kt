package dev.geomys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun serverConfigRoundTrip() {
        val original = ServerConfig(
            id = "server-1",
            name = "echo",
            protocol = TunnelProtocol.UDP,
            targetHost = "::1",
            targetPort = 9000,
            enabled = false,
            lastError = "nope",
            lastErrorAt = 12,
        )
        val restored = ServerConfig.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun clientConfigRoundTrip() {
        val original = ClientConfig(
            id = "client-1",
            name = "listener",
            serverProfileId = 10,
            serverConfigId = "server-1",
            serverLabel = "Profile 10",
            serverName = "echo",
            protocol = TunnelProtocol.TCP,
            targetHost = "127.0.0.1",
            targetPort = 9000,
            bindHost = "127.0.0.1",
            bindPort = 9001,
            udpTimeoutSeconds = 120,
            enabled = true,
        )
        val restored = ClientConfig.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun clientConfigCoercesTimeoutAndDefaults() {
        val json = ClientConfig().toJson().apply {
            put("udpTimeoutSeconds", 1)
            put("enabled", false)
        }
        val restored = ClientConfig.fromJson(json)
        assertEquals(5, restored.udpTimeoutSeconds)
        assertEquals(false, restored.enabled)
        assertTrue(restored.protocol == TunnelProtocol.TCP)
    }

    @Test
    fun missingOptionalErrorBecomesNull() {
        val json = ServerConfig().toJson().apply { put("lastError", "") }
        assertNull(ServerConfig.fromJson(json).lastError)
    }

    @Test
    fun profileInfoRoundTrip() {
        val original = ProfileInfo(10, "secondary", "Profile 10", 1)
        assertEquals(original, ProfileInfo.fromJson(original.toJson()))
    }
}
