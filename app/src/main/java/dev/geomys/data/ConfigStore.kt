package dev.geomys.data

import android.content.Context
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("geomys", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _serverConfigs = MutableStateFlow(loadServers())
    val serverConfigs: StateFlow<List<ServerConfig>> = _serverConfigs.asStateFlow()

    private val _clientConfigs = MutableStateFlow(loadClients())
    val clientConfigs: StateFlow<List<ClientConfig>> = _clientConfigs.asStateFlow()

    private val _profileLabel = MutableStateFlow(
        prefs.getString(KEY_PROFILE_LABEL, null)?.takeIf { it.isNotBlank() } ?: defaultLabel()
    )
    val profileLabel: StateFlow<String> = _profileLabel.asStateFlow()

    private val _startOnBoot = MutableStateFlow(prefs.getBoolean(KEY_START_ON_BOOT, true))
    val startOnBoot: StateFlow<Boolean> = _startOnBoot.asStateFlow()

    private val _activity = MutableStateFlow(loadActivity())
    val activity: StateFlow<List<ActivityEvent>> = _activity.asStateFlow()

    fun defaultLabel(): String = "Profile ${Process.myUid() / 100000}"

    fun setProfileLabel(label: String) {
        val clean = label.trim().take(60).ifBlank { defaultLabel() }
        synchronized(lock) {
            prefs.edit().putString(KEY_PROFILE_LABEL, clean).apply()
            _profileLabel.value = clean
        }
    }

    fun resetProfileLabel() = setProfileLabel(defaultLabel())

    fun setStartOnBoot(enabled: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
            _startOnBoot.value = enabled
        }
    }

    fun saveServer(config: ServerConfig) {
        synchronized(lock) {
            val next = _serverConfigs.value.toMutableList()
            val index = next.indexOfFirst { it.id == config.id }
            if (index >= 0) next[index] = config else next.add(config)
            _serverConfigs.value = next
            persistServers(next)
        }
    }

    fun deleteServer(id: String) {
        synchronized(lock) {
            val next = _serverConfigs.value.filterNot { it.id == id }
            _serverConfigs.value = next
            persistServers(next)
        }
    }

    fun setServerEnabled(id: String, enabled: Boolean) {
        updateServer(id) { it.copy(enabled = enabled) }
    }

    fun setServerError(id: String, message: String?) {
        updateServer(id) {
            it.copy(lastError = message, lastErrorAt = if (message == null) 0L else System.currentTimeMillis())
        }
    }

    private fun updateServer(id: String, transform: (ServerConfig) -> ServerConfig) {
        synchronized(lock) {
            val next = _serverConfigs.value.map { if (it.id == id) transform(it) else it }
            _serverConfigs.value = next
            persistServers(next)
        }
    }

    fun saveClient(config: ClientConfig) {
        synchronized(lock) {
            val next = _clientConfigs.value.toMutableList()
            val index = next.indexOfFirst { it.id == config.id }
            if (index >= 0) next[index] = config else next.add(config)
            _clientConfigs.value = next
            persistClients(next)
        }
    }

    fun deleteClient(id: String) {
        synchronized(lock) {
            val next = _clientConfigs.value.filterNot { it.id == id }
            _clientConfigs.value = next
            persistClients(next)
        }
    }

    fun setClientEnabled(id: String, enabled: Boolean) {
        updateClient(id) { it.copy(enabled = enabled) }
    }

    fun setClientError(id: String, message: String?) {
        updateClient(id) {
            it.copy(lastError = message, lastErrorAt = if (message == null) 0L else System.currentTimeMillis())
        }
    }

    private fun updateClient(id: String, transform: (ClientConfig) -> ClientConfig) {
        synchronized(lock) {
            val next = _clientConfigs.value.map { if (it.id == id) transform(it) else it }
            _clientConfigs.value = next
            persistClients(next)
        }
    }

    fun addActivity(level: String, message: String) {
        synchronized(lock) {
            val event = ActivityEvent(System.currentTimeMillis(), level, message)
            val next = (listOf(event) + _activity.value).take(200)
            _activity.value = next
            persistActivity(next)
        }
    }

    fun clearActivity() {
        synchronized(lock) {
            _activity.value = emptyList()
            prefs.edit().remove(KEY_ACTIVITY).apply()
        }
    }

    private fun loadServers(): List<ServerConfig> = runCatching {
        val array = JSONArray(prefs.getString(KEY_SERVERS, "[]"))
        (0 until array.length()).map { ServerConfig.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadClients(): List<ClientConfig> = runCatching {
        val array = JSONArray(prefs.getString(KEY_CLIENTS, "[]"))
        (0 until array.length()).map { ClientConfig.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadActivity(): List<ActivityEvent> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ACTIVITY, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            ActivityEvent(
                timestamp = o.optLong("timestamp"),
                level = o.optString("level", "INFO"),
                message = o.optString("message"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persistServers(configs: List<ServerConfig>) {
        val array = JSONArray()
        configs.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun persistClients(configs: List<ClientConfig>) {
        val array = JSONArray()
        configs.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CLIENTS, array.toString()).apply()
    }

    private fun persistActivity(events: List<ActivityEvent>) {
        val array = JSONArray()
        events.forEach {
            array.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("level", it.level)
                put("message", it.message)
            })
        }
        prefs.edit().putString(KEY_ACTIVITY, array.toString()).apply()
    }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_CLIENTS = "clients"
        const val KEY_PROFILE_LABEL = "profile_label"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_ACTIVITY = "activity"
    }
}
