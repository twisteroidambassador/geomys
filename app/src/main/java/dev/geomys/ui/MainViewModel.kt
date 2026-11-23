package dev.geomys.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.geomys.data.ClientConfig
import dev.geomys.data.DiscoveredProfile
import dev.geomys.data.ServerConfig
import dev.geomys.data.TunnelProtocol
import dev.geomys.data.TunnelRepository
import dev.geomys.net.EndpointValidator
import dev.geomys.profile.ProfileDiscovery
import dev.geomys.service.TunnelClientService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val loading: Boolean = false,
    val profiles: List<DiscoveredProfile> = emptyList(),
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = TunnelRepository

    val serverConfigs: StateFlow<List<ServerConfig>> = repo.serverConfigs
    val clientConfigs: StateFlow<List<ClientConfig>> = repo.clientConfigs
    val profileLabel: StateFlow<String> = repo.profileLabel
    val activity = repo.activity
    val connections = repo.connections
    val startOnBoot = repo.startOnBoot
    val myUserId: Int = repo.myUserId()
    val myProfileType: String = repo.profileType()

    private val _discovery = MutableStateFlow(DiscoveryUiState())
    val discovery: StateFlow<DiscoveryUiState> = _discovery.asStateFlow()

    fun loadDiscovery(force: Boolean = false) {
        if (_discovery.value.loading) return
        if (!force && _discovery.value.profiles.isNotEmpty()) return
        _discovery.value = _discovery.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = ProfileDiscovery(getApplication()).discover()
            _discovery.value = DiscoveryUiState(
                loading = false,
                profiles = result.profiles,
                error = result.error,
            )
        }
    }

    fun saveServer(
        id: String?,
        name: String,
        protocol: TunnelProtocol,
        host: String,
        portText: String,
        enabled: Boolean,
    ): String? {
        val port = portText.toIntOrNull() ?: return "Target port must be a number"
        EndpointValidator.validatePort(port, "Target port")?.let { return it }
        val address = try {
            EndpointValidator.parseHost(host)
        } catch (e: Exception) {
            return e.message ?: "Invalid target host"
        }
        val existing = id?.let { serverConfigs.value.firstOrNull { c -> c.id == it } }
        val config = (existing ?: ServerConfig()).copy(
            name = name.trim(),
            protocol = protocol,
            targetHost = EndpointValidator.hostString(address),
            targetPort = port,
            enabled = enabled,
            lastError = null,
            lastErrorAt = 0L,
        )
        repo.store.saveServer(config)
        return null
    }

    fun saveClient(
        id: String?,
        name: String,
        profile: DiscoveredProfile?,
        serverConfig: ServerConfig?,
        bindHost: String,
        bindPortText: String,
        udpTimeoutText: String,
        enabled: Boolean,
    ): String? {
        if (profile == null) return "Choose a server profile"
        if (serverConfig == null) return "Choose a server endpoint"
        val port = bindPortText.toIntOrNull() ?: return "Bind port must be a number"
        EndpointValidator.validatePort(port, "Bind port")?.let { return it }
        val address = try {
            EndpointValidator.parseHost(bindHost)
        } catch (e: Exception) {
            return e.message ?: "Invalid bind host"
        }
        val timeout = udpTimeoutText.toIntOrNull()?.coerceIn(5, 3600) ?: 60
        val existing = id?.let { clientConfigs.value.firstOrNull { c -> c.id == it } }
        val config = (existing ?: ClientConfig()).copy(
            name = name.trim(),
            serverProfileId = profile.info.userId,
            serverConfigId = serverConfig.id,
            serverLabel = profile.info.label,
            serverName = serverConfig.displayName,
            protocol = serverConfig.protocol,
            targetHost = serverConfig.targetHost,
            targetPort = serverConfig.targetPort,
            bindHost = EndpointValidator.hostString(address),
            bindPort = port,
            udpTimeoutSeconds = timeout,
            enabled = enabled,
            lastError = null,
            lastErrorAt = 0L,
        )
        repo.store.saveClient(config)
        TunnelClientService.sync(getApplication())
        return null
    }

    fun setServerEnabled(id: String, enabled: Boolean) = repo.store.setServerEnabled(id, enabled)

    fun setClientEnabled(id: String, enabled: Boolean) {
        repo.store.setClientEnabled(id, enabled)
        TunnelClientService.sync(getApplication())
    }

    fun deleteServer(id: String) = repo.store.deleteServer(id)

    fun deleteClient(id: String) {
        repo.store.deleteClient(id)
        TunnelClientService.sync(getApplication())
    }

    fun setProfileLabel(label: String) = repo.store.setProfileLabel(label)
    fun resetProfileLabel() = repo.store.resetProfileLabel()
    fun setStartOnBoot(enabled: Boolean) = repo.store.setStartOnBoot(enabled)
    fun clearActivity() = repo.store.clearActivity()

    fun syncService() = TunnelClientService.sync(getApplication())
}
