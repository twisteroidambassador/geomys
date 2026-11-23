package dev.geomys.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.geomys.IProfileTunnel
import dev.geomys.data.ConnectionInfo
import dev.geomys.data.ServerConfig
import dev.geomys.data.TunnelProtocol
import dev.geomys.data.TunnelRepository
import dev.geomys.net.EndpointValidator
import dev.geomys.net.TcpRelay
import dev.geomys.net.UdpServerRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Server side. Runs in every profile that has the app installed.
 * Exposes a signature-protected AIDL service; clients bind from another profile,
 * then call openTcpStream/openUdpStream. The returned fd is one end of a
 * socketpair; the actual target connection is made here, in this profile.
 */
class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = ConcurrentHashMap<String, RelayHandle>()
    private val retained = ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()

    private val binder = object : IProfileTunnel.Stub() {
        override fun getProtocolVersion(): Int = TunnelRepository.PROTOCOL_VERSION

        override fun getProfileInfoJson(): String =
            TunnelRepository.profileInfo().toJson().toString()

        override fun getServerConfigsJson(): String {
            val array = JSONArray()
            TunnelRepository.serverConfigs.value.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        override fun openTcpStream(
            serverConfigId: String?,
            clientUserId: Int,
            clientLabel: String?,
            connectionId: String?,
        ): ParcelFileDescriptor = openTcp(
            serverConfigId ?: "",
            clientUserId,
            clientLabel.orEmpty(),
            connectionId ?: "",
        )

        override fun openUdpStream(
            serverConfigId: String?,
            clientUserId: Int,
            clientLabel: String?,
            connectionId: String?,
        ): ParcelFileDescriptor = openUdp(
            serverConfigId ?: "",
            clientUserId,
            clientLabel.orEmpty(),
            connectionId ?: "",
        )

        override fun closeStream(connectionId: String?) {
            relays.remove(connectionId)?.close?.invoke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        TunnelRepository.init(this)
        scope.launch {
            TunnelRepository.serverConfigs.collect { configs ->
                closeDisabled(configs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun openTcp(
        serverConfigId: String,
        clientUserId: Int,
        clientLabel: String,
        connectionId: String,
    ): ParcelFileDescriptor {
        val config = TunnelRepository.serverConfig(serverConfigId)
            ?: throw RemoteException("Server config not found")
        if (!config.enabled) throw RemoteException("Server config is disabled")
        if (config.protocol != TunnelProtocol.TCP) throw RemoteException("Server config is not TCP")
        if (connectionId.isBlank()) throw RemoteException("Missing connection id")

        val address = try {
            EndpointValidator.parseHost(config.targetHost)
        } catch (e: Exception) {
            reportServerError(config.id, "Invalid target: ${e.message}")
            throw RemoteException("Invalid target: ${e.message}")
        }
        if (EndpointValidator.validatePort(config.targetPort) != null) {
            reportServerError(config.id, "Invalid target port")
            throw RemoteException("Invalid target port")
        }

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, config.targetPort), 10_000)
        } catch (e: Exception) {
            runCatching { socket.close() }
            reportServerError(config.id, "Target connect failed: ${e.message}")
            throw RemoteException("Target connect failed: ${e.message}")
        }

        val pair = try {
            ParcelFileDescriptor.createSocketPair()
        } catch (e: IOException) {
            runCatching { socket.close() }
            reportServerError(config.id, "socketpair failed: ${e.message}")
            throw RemoteException("socketpair failed: ${e.message}")
        }

        val connection = ConnectionInfo(
            id = connectionId,
            configId = config.id,
            configName = config.displayName,
            protocol = TunnelProtocol.TCP,
            role = "server",
            peerUserId = clientUserId,
            peerLabel = clientLabel.ifBlank { "Profile $clientUserId" },
            source = clientLabel.ifBlank { "Profile $clientUserId" },
            target = "${config.targetHost}:${config.targetPort}",
            startedAt = System.currentTimeMillis(),
        )
        TunnelRepository.upsertConnection(connection)
        TunnelRepository.store.setServerError(config.id, null)

        val relay = TcpRelay(
            scope = scope,
            pfd = pair[0],
            socket = socket,
            onToPeer = { bytes -> addBytes(connectionId, inbound = bytes) },
            onFromPeer = { bytes -> addBytes(connectionId, outbound = bytes) },
            onClosed = {
                relays.remove(connectionId)
                TunnelRepository.removeConnection(connectionId)
                TunnelRepository.addActivity(
                    "INFO",
                    "TCP closed ${config.displayName} (${connection.peerLabel})",
                )
            },
        )
        relays[connectionId] = RelayHandle(config.id, relay::close)
        relay.start()
        TunnelRepository.addActivity(
            "INFO",
            "TCP ${config.displayName} -> ${connection.peerLabel}",
        )
        retain(pair[1])
        return pair[1]
    }

    private fun openUdp(
        serverConfigId: String,
        clientUserId: Int,
        clientLabel: String,
        connectionId: String,
    ): ParcelFileDescriptor {
        val config = TunnelRepository.serverConfig(serverConfigId)
            ?: throw RemoteException("Server config not found")
        if (!config.enabled) throw RemoteException("Server config is disabled")
        if (config.protocol != TunnelProtocol.UDP) throw RemoteException("Server config is not UDP")
        if (connectionId.isBlank()) throw RemoteException("Missing connection id")

        val address = try {
            EndpointValidator.parseHost(config.targetHost)
        } catch (e: Exception) {
            reportServerError(config.id, "Invalid target: ${e.message}")
            throw RemoteException("Invalid target: ${e.message}")
        }
        if (EndpointValidator.validatePort(config.targetPort) != null) {
            reportServerError(config.id, "Invalid target port")
            throw RemoteException("Invalid target port")
        }

        val target = InetSocketAddress(address, config.targetPort)
        val socket = try {
            DatagramSocket().apply { connect(target) }
        } catch (e: Exception) {
            reportServerError(config.id, "Target UDP socket failed: ${e.message}")
            throw RemoteException("Target UDP socket failed: ${e.message}")
        }

        val pair = try {
            ParcelFileDescriptor.createSocketPair()
        } catch (e: IOException) {
            runCatching { socket.close() }
            reportServerError(config.id, "socketpair failed: ${e.message}")
            throw RemoteException("socketpair failed: ${e.message}")
        }

        val connection = ConnectionInfo(
            id = connectionId,
            configId = config.id,
            configName = config.displayName,
            protocol = TunnelProtocol.UDP,
            role = "server",
            peerUserId = clientUserId,
            peerLabel = clientLabel.ifBlank { "Profile $clientUserId" },
            source = clientLabel.ifBlank { "Profile $clientUserId" },
            target = "${config.targetHost}:${config.targetPort}",
            startedAt = System.currentTimeMillis(),
        )
        TunnelRepository.upsertConnection(connection)
        TunnelRepository.store.setServerError(config.id, null)

        val relay = UdpServerRelay(
            scope = scope,
            pfd = pair[0],
            target = target,
            onToPeer = { bytes -> addBytes(connectionId, inbound = bytes) },
            onFromPeer = { bytes -> addBytes(connectionId, outbound = bytes) },
            onClosed = {
                relays.remove(connectionId)
                TunnelRepository.removeConnection(connectionId)
                TunnelRepository.addActivity(
                    "INFO",
                    "UDP closed ${config.displayName} (${connection.peerLabel})",
                )
            },
        )
        relays[connectionId] = RelayHandle(config.id, relay::close)
        relay.start()
        TunnelRepository.addActivity(
            "INFO",
            "UDP ${config.displayName} -> ${connection.peerLabel}",
        )
        retain(pair[1])
        return pair[1]
    }

    private fun addBytes(connectionId: String, inbound: Int = 0, outbound: Int = 0) {
        if (inbound == 0 && outbound == 0) return
        TunnelRepository.updateConnection(connectionId) {
            it.copy(
                bytesIn = it.bytesIn + inbound,
                bytesOut = it.bytesOut + outbound,
            )
        }
    }

    private fun reportServerError(configId: String, message: String) {
        TunnelRepository.store.setServerError(configId, message)
        TunnelRepository.addActivity("ERROR", message)
    }

    private fun retain(pfd: ParcelFileDescriptor) {
        retained.add(pfd)
        scope.launch {
            delay(10_000)
            retained.remove(pfd)
            runCatching { pfd.close() }
        }
    }

    private fun closeDisabled(configs: List<ServerConfig>) {
        val enabled = configs.filter { it.enabled }.map { it.id }.toSet()
        relays.entries
            .filter { it.value.configId !in enabled }
            .forEach { entry ->
                relays.remove(entry.key)
                entry.value.close.invoke()
            }
    }

    override fun onDestroy() {
        relays.values.forEach { runCatching { it.close.invoke() } }
        relays.clear()
        retained.forEach { runCatching { it.close() } }
        retained.clear()
        scope.cancel()
        super.onDestroy()
    }

    private class RelayHandle(val configId: String, val close: () -> Unit)
}
