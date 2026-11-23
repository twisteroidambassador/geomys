package dev.geomys.service

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.geomys.data.ClientConfig
import dev.geomys.data.ConnectionInfo
import dev.geomys.data.TunnelProtocol
import dev.geomys.data.TunnelRepository
import dev.geomys.net.EndpointValidator
import dev.geomys.net.TcpRelay
import dev.geomys.net.UdpClientMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

interface ClientListener {
    fun start()
    fun close()
}

class PeerRegistry(private val context: Context) {
    private val peers = ConcurrentHashMap<Int, PeerBinder>()

    fun get(profileId: Int): PeerBinder =
        peers.getOrPut(profileId) { PeerBinder(context, profileId) }

    fun close() {
        peers.values.forEach { it.close() }
        peers.clear()
    }
}

class TcpClientListener(
    private val context: Context,
    private val config: ClientConfig,
    private val peers: PeerRegistry,
    private val scope: CoroutineScope,
) : ClientListener {
    private val serverSocket = ServerSocket()
    private val connections = ConcurrentHashMap<String, TcpRelay>()
    private val slots = Semaphore(128)
    private var acceptJob: Job? = null

    override fun start() {
        val address = EndpointValidator.parseHost(config.bindHost)
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(address, config.bindPort), 50)
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch(Dispatchers.IO) { handle(socket) }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        if (!slots.tryAcquire()) {
            runCatching { socket.close() }
            report("Too many connections")
            return
        }
        var pfd: ParcelFileDescriptor? = null
        try {
            socket.tcpNoDelay = true
            val connectionId = UUID.randomUUID().toString()
            val tunnel = peers.get(config.serverProfileId).connect()
                ?: throw IOException("Profile ${config.serverProfileId} unavailable")
            val stream = tunnel.openTcpStream(
                config.serverConfigId,
                TunnelRepository.myUserId(),
                TunnelRepository.profileLabel.value,
                connectionId,
            ) ?: throw IOException("Server refused the stream")
            pfd = stream

            val connection = ConnectionInfo(
                id = connectionId,
                configId = config.id,
                configName = config.displayName,
                protocol = TunnelProtocol.TCP,
                role = "client",
                peerUserId = config.serverProfileId,
                peerLabel = config.serverLabel,
                source = socket.remoteSocketAddress?.toString().orEmpty(),
                target = "${config.targetHost}:${config.targetPort}",
                startedAt = System.currentTimeMillis(),
            )
            TunnelRepository.upsertConnection(connection)
            TunnelRepository.store.setClientError(config.id, null)
            TunnelRepository.addActivity(
                "INFO",
                "TCP ${config.displayName} -> ${config.serverLabel}",
            )

            val relay = TcpRelay(
                scope = scope,
                pfd = stream,
                socket = socket,
                onToPeer = { bytes -> addBytes(connectionId, outbound = bytes) },
                onFromPeer = { bytes -> addBytes(connectionId, inbound = bytes) },
                onClosed = {
                    connections.remove(connectionId)
                    TunnelRepository.removeConnection(connectionId)
                    TunnelRepository.addActivity(
                        "INFO",
                        "TCP closed ${config.displayName} (${config.serverLabel})",
                    )
                },
            )
            connections[connectionId] = relay
            relay.start()
        } catch (e: Exception) {
            runCatching { pfd?.close() }
            runCatching { socket.close() }
            report(e.message ?: "Connection failed")
        } finally {
            slots.release()
        }
    }

    private fun addBytes(connectionId: String, inbound: Int = 0, outbound: Int = 0) {
        if (inbound == 0 && outbound == 0) return
        TunnelRepository.updateConnection(connectionId) {
            it.copy(bytesIn = it.bytesIn + inbound, bytesOut = it.bytesOut + outbound)
        }
    }

    private fun report(message: String) {
        TunnelRepository.store.setClientError(config.id, message)
        TunnelRepository.addActivity("ERROR", "Client ${config.displayName}: $message")
    }

    override fun close() {
        runCatching { serverSocket.close() }
        acceptJob?.cancel()
        connections.values.forEach { it.close() }
        connections.clear()
    }
}

class UdpClientListener(
    private val context: Context,
    private val config: ClientConfig,
    private val peers: PeerRegistry,
    private val scope: CoroutineScope,
) : ClientListener {
    private val socket = DatagramSocket(null)
    private val mappings = ConcurrentHashMap<SocketAddress, UdpClientMapping>()
    private var receiveJob: Job? = null
    private var sweepJob: Job? = null

    override fun start() {
        val address = EndpointValidator.parseHost(config.bindHost)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, config.bindPort))
        socket.soTimeout = 1000
        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                val source = InetSocketAddress(packet.address, packet.port)
                val payload = packet.data.copyOf(packet.length)
                var mapping = mappings[source]
                if (mapping == null) {
                    if (mappings.size >= MAX_MAPPINGS) {
                        report("Too many UDP mappings")
                        continue
                    }
                    mapping = createMapping(source) ?: continue
                    mappings[source] = mapping
                }
                mapping.send(payload)
            }
        }
        sweepJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                mappings.entries
                    .filter { it.value.isIdle(now) }
                    .forEach { entry ->
                        mappings.remove(entry.key)
                        entry.value.close()
                    }
            }
        }
    }

    private suspend fun createMapping(source: SocketAddress): UdpClientMapping? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            val connectionId = UUID.randomUUID().toString()
            val tunnel = peers.get(config.serverProfileId).connect()
                ?: throw IOException("Profile ${config.serverProfileId} unavailable")
            val stream = tunnel.openUdpStream(
                config.serverConfigId,
                TunnelRepository.myUserId(),
                TunnelRepository.profileLabel.value,
                connectionId,
            ) ?: throw IOException("Server refused the UDP stream")
            pfd = stream

            val mapping = UdpClientMapping(
                scope = scope,
                listener = socket,
                source = source,
                pfd = stream,
                timeoutMillis = config.udpTimeoutSeconds * 1000L,
                onToPeer = { bytes -> addBytes(connectionId, outbound = bytes) },
                onFromPeer = { bytes -> addBytes(connectionId, inbound = bytes) },
                onClosed = {
                    mappings.remove(source)
                    TunnelRepository.removeConnection(connectionId)
                    TunnelRepository.addActivity(
                        "INFO",
                        "UDP closed ${config.displayName} (${source})",
                    )
                },
            )
            val connection = ConnectionInfo(
                id = connectionId,
                configId = config.id,
                configName = config.displayName,
                protocol = TunnelProtocol.UDP,
                role = "client",
                peerUserId = config.serverProfileId,
                peerLabel = config.serverLabel,
                source = source.toString(),
                target = "${config.targetHost}:${config.targetPort}",
                startedAt = System.currentTimeMillis(),
            )
            TunnelRepository.upsertConnection(connection)
            TunnelRepository.store.setClientError(config.id, null)
            TunnelRepository.addActivity(
                "INFO",
                "UDP ${config.displayName} -> ${config.serverLabel} ($source)",
            )
            mapping.start()
            mapping
        } catch (e: Exception) {
            runCatching { pfd?.close() }
            report(e.message ?: "UDP mapping failed")
            null
        }
    }

    private fun addBytes(connectionId: String, inbound: Int = 0, outbound: Int = 0) {
        if (inbound == 0 && outbound == 0) return
        TunnelRepository.updateConnection(connectionId) {
            it.copy(bytesIn = it.bytesIn + inbound, bytesOut = it.bytesOut + outbound)
        }
    }

    private fun report(message: String) {
        TunnelRepository.store.setClientError(config.id, message)
        TunnelRepository.addActivity("ERROR", "Client ${config.displayName}: $message")
    }

    override fun close() {
        receiveJob?.cancel()
        sweepJob?.cancel()
        mappings.values.forEach { it.close() }
        mappings.clear()
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_MAPPINGS = 256
    }
}
