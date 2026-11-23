package dev.geomys.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TunnelProtocol { TCP, UDP }

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: TunnelProtocol = TunnelProtocol.TCP,
    val targetHost: String = "127.0.0.1",
    val targetPort: Int = 8080,
    val enabled: Boolean = true,
    val lastError: String? = null,
    val lastErrorAt: Long = 0L,
) {
    val displayName: String
        get() = name.ifBlank { "${protocol.name} $targetHost:$targetPort" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.name)
        put("targetHost", targetHost)
        put("targetPort", targetPort)
        put("enabled", enabled)
        put("lastError", lastError ?: "")
        put("lastErrorAt", lastErrorAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ServerConfig = ServerConfig(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            protocol = runCatching { TunnelProtocol.valueOf(o.optString("protocol", "TCP")) }
                .getOrDefault(TunnelProtocol.TCP),
            targetHost = o.optString("targetHost", "127.0.0.1"),
            targetPort = o.optInt("targetPort", 8080),
            enabled = o.optBoolean("enabled", true),
            lastError = o.optString("lastError").ifBlank { null },
            lastErrorAt = o.optLong("lastErrorAt", 0L),
        )
    }
}

data class ClientConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val serverProfileId: Int = 0,
    val serverConfigId: String = "",
    val serverLabel: String = "",
    val serverName: String = "",
    val protocol: TunnelProtocol = TunnelProtocol.TCP,
    val targetHost: String = "",
    val targetPort: Int = 0,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int = 8080,
    val udpTimeoutSeconds: Int = 60,
    val enabled: Boolean = true,
    val lastError: String? = null,
    val lastErrorAt: Long = 0L,
) {
    val displayName: String
        get() = name.ifBlank { "${protocol.name} $bindHost:$bindPort" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("serverProfileId", serverProfileId)
        put("serverConfigId", serverConfigId)
        put("serverLabel", serverLabel)
        put("serverName", serverName)
        put("protocol", protocol.name)
        put("targetHost", targetHost)
        put("targetPort", targetPort)
        put("bindHost", bindHost)
        put("bindPort", bindPort)
        put("udpTimeoutSeconds", udpTimeoutSeconds)
        put("enabled", enabled)
        put("lastError", lastError ?: "")
        put("lastErrorAt", lastErrorAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ClientConfig = ClientConfig(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            serverProfileId = o.optInt("serverProfileId", 0),
            serverConfigId = o.optString("serverConfigId"),
            serverLabel = o.optString("serverLabel"),
            serverName = o.optString("serverName"),
            protocol = runCatching { TunnelProtocol.valueOf(o.optString("protocol", "TCP")) }
                .getOrDefault(TunnelProtocol.TCP),
            targetHost = o.optString("targetHost"),
            targetPort = o.optInt("targetPort", 0),
            bindHost = o.optString("bindHost", "127.0.0.1"),
            bindPort = o.optInt("bindPort", 8080),
            udpTimeoutSeconds = o.optInt("udpTimeoutSeconds", 60).coerceIn(5, 3600),
            enabled = o.optBoolean("enabled", true),
            lastError = o.optString("lastError").ifBlank { null },
            lastErrorAt = o.optLong("lastErrorAt", 0L),
        )
    }
}

data class ProfileInfo(
    val userId: Int,
    val type: String,
    val label: String,
    val protocolVersion: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("userId", userId)
        put("type", type)
        put("label", label)
        put("protocolVersion", protocolVersion)
    }

    companion object {
        fun fromJson(o: JSONObject): ProfileInfo = ProfileInfo(
            userId = o.optInt("userId", 0),
            type = o.optString("type", "secondary"),
            label = o.optString("label", "Profile ${o.optInt("userId", 0)}"),
            protocolVersion = o.optInt("protocolVersion", 1),
        )
    }
}

data class DiscoveredProfile(
    val info: ProfileInfo,
    val serverConfigs: List<ServerConfig>,
)

data class ConnectionInfo(
    val id: String,
    val configId: String,
    val configName: String,
    val protocol: TunnelProtocol,
    val role: String,
    val peerUserId: Int,
    val peerLabel: String,
    val source: String,
    val target: String,
    val startedAt: Long,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val error: String? = null,
)

data class ActivityEvent(
    val timestamp: Long,
    val level: String,
    val message: String,
)

fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }
