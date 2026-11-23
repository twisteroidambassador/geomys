package dev.geomys.data

import android.content.Context
import android.os.Process
import android.os.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TunnelRepository {
    lateinit var appContext: Context
        private set
    lateinit var store: ConfigStore
        private set

    private val _connections = MutableStateFlow<Map<String, ConnectionInfo>>(emptyMap())
    val connections: StateFlow<Map<String, ConnectionInfo>> = _connections.asStateFlow()

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            store = ConfigStore(appContext)
        }
    }

    val serverConfigs: StateFlow<List<ServerConfig>> get() = store.serverConfigs
    val clientConfigs: StateFlow<List<ClientConfig>> get() = store.clientConfigs
    val profileLabel: StateFlow<String> get() = store.profileLabel
    val startOnBoot: StateFlow<Boolean> get() = store.startOnBoot
    val activity: StateFlow<List<ActivityEvent>> get() = store.activity

    fun myUserId(): Int = Process.myUid() / 100000

    fun profileType(): String {
        val um = appContext.getSystemService(UserManager::class.java) ?: return "secondary"
        return when {
            um.isManagedProfile -> "work"
            um.isProfile -> "private"
            myUserId() == 0 -> "owner"
            else -> "secondary"
        }
    }

    fun profileInfo(): ProfileInfo = ProfileInfo(
        userId = myUserId(),
        type = profileType(),
        label = profileLabel.value,
        protocolVersion = PROTOCOL_VERSION,
    )

    fun serverConfig(id: String): ServerConfig? = serverConfigs.value.firstOrNull { it.id == id }
    fun clientConfig(id: String): ClientConfig? = clientConfigs.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertConnection(info: ConnectionInfo) {
        _connections.value = _connections.value + (info.id to info)
    }

    @Synchronized
    fun updateConnection(id: String, transform: (ConnectionInfo) -> ConnectionInfo) {
        val current = _connections.value[id] ?: return
        _connections.value = _connections.value + (id to transform(current))
    }

    @Synchronized
    fun removeConnection(id: String) {
        if (_connections.value.containsKey(id)) {
            _connections.value = _connections.value - id
        }
    }

    fun addActivity(level: String, message: String) = store.addActivity(level, message)
    fun clearActivity() = store.clearActivity()

    const val PROTOCOL_VERSION = 1
}
