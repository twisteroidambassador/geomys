package dev.geomys.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import dev.geomys.data.ClientConfig
import dev.geomys.data.TunnelProtocol
import dev.geomys.data.TunnelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Client side foreground service. It listens on this profile's bind addresses and
 * forwards every accepted TCP connection / UDP mapping to the server profile.
 */
class TunnelClientService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peers by lazy { PeerRegistry(this) }
    private val listeners = mutableMapOf<String, ClientListener>()
    private var configSignature = ""
    private var notificationConfigCount = -1

    override fun onCreate() {
        super.onCreate()
        TunnelRepository.init(this)
        Notifications.createChannel(this)
        startForegroundCompat(0)
        scope.launch {
            TunnelRepository.clientConfigs.collect { configs ->
                val signature = signature(configs)
                if (signature != configSignature) {
                    configSignature = signature
                    withContext(Dispatchers.Main) { refresh(configs) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Notifications.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(TunnelRepository.clientConfigs.value.count { it.enabled })
        refresh(TunnelRepository.clientConfigs.value)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(configCount: Int) {
        val notification = Notifications.build(this, configCount)
        startForeground(
            Notifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun refresh(configs: List<ClientConfig>) {
        listeners.values.forEach { it.close() }
        listeners.clear()
        val enabled = configs.filter { it.enabled }
        if (enabled.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        enabled.forEach { config ->
            val listener: ClientListener = when (config.protocol) {
                TunnelProtocol.TCP -> TcpClientListener(this, config, peers, scope)
                TunnelProtocol.UDP -> UdpClientListener(this, config, peers, scope)
            }
            try {
                listener.start()
                listeners[config.id] = listener
                TunnelRepository.store.setClientError(config.id, null)
            } catch (e: Exception) {
                listener.close()
                TunnelRepository.store.setClientError(config.id, e.message ?: "Listener failed")
                TunnelRepository.addActivity(
                    "ERROR",
                    "Client ${config.displayName}: ${e.message ?: "listener failed"}",
                )
            }
        }
        updateNotification(enabled.size)
    }

    private fun updateNotification(count: Int) {
        if (count == notificationConfigCount) return
        notificationConfigCount = count
        NotificationManagerCompat.from(this)
            .notify(Notifications.NOTIFICATION_ID, Notifications.build(this, count))
    }

    override fun onDestroy() {
        listeners.values.forEach { it.close() }
        listeners.clear()
        peers.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun signature(configs: List<ClientConfig>): String =
        configs.filter { it.enabled }
            .joinToString("|") {
                "${it.id}:${it.protocol}:${it.bindHost}:${it.bindPort}:${it.serverProfileId}:${it.serverConfigId}:${it.udpTimeoutSeconds}"
            }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TunnelClientService::class.java),
                )
            } catch (e: Exception) {
                TunnelRepository.addActivity("ERROR", "Cannot start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TunnelClientService::class.java))
        }

        fun sync(context: Context) {
            TunnelRepository.init(context)
            if (TunnelRepository.clientConfigs.value.any { it.enabled }) {
                start(context)
            } else {
                stop(context)
            }
        }
    }
}
