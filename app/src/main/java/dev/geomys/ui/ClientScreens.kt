package dev.geomys.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.geomys.data.ClientConfig
import dev.geomys.data.ConnectionInfo
import dev.geomys.data.DiscoveredProfile
import dev.geomys.data.ProfileInfo
import dev.geomys.data.ServerConfig
import dev.geomys.data.TunnelProtocol
import dev.geomys.net.EndpointValidator

@Composable
fun ClientListScreen(
    configs: List<ClientConfig>,
    connections: Map<String, ConnectionInfo>,
    onAdd: () -> Unit,
    onOpen: (ClientConfig) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (configs.isEmpty()) {
            EmptyState(
                title = "No client configs",
                detail = "Listen on a local port and forward it to a server config in another profile.",
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAdd) { Text("Add client") }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(configs, key = { it.id }) { config ->
                    ClientRow(
                        config = config,
                        activeConnections = connections.values.count { it.configId == config.id },
                        onOpen = { onOpen(config) },
                        onToggle = { onToggle(config.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClientRow(
    config: ClientConfig,
    activeConnections: Int,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${config.protocol.name} ${config.bindHost}:${config.bindPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${config.serverLabel} · ${config.serverName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activeConnections > 0) {
                    Text(
                        text = "$activeConnections active connection${if (activeConnections == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ErrorText(config.lastError)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun ClientDetailScreen(
    config: ClientConfig,
    connections: List<ConnectionInfo>,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(config.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${config.protocol.name} ${config.bindHost}:${config.bindPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
        Text(
            "Server: ${config.serverLabel} · ${config.serverName}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Target: ${config.targetHost}:${config.targetPort}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (config.protocol == TunnelProtocol.UDP) {
            Text(
                "UDP timeout: ${config.udpTimeoutSeconds}s (either direction resets it)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ErrorText(config.lastError)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        HorizontalDivider()
        Text("Connections", style = MaterialTheme.typography.titleMedium)
        if (connections.isEmpty()) {
            Text(
                "No active connections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            connections.forEach { ConnectionRow(it, showPeer = false) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete client config?") },
            text = { Text("The listener and all active connections will be closed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun ClientEditScreen(
    existing: ClientConfig?,
    discovery: DiscoveryUiState,
    onRefreshProfiles: () -> Unit,
    onSave: (
        String,
        DiscoveredProfile?,
        ServerConfig?,
        String,
        String,
        String,
        Boolean,
    ) -> String?,
    onCancel: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var selectedProfileId by remember(existing?.id) {
        mutableIntStateOf(existing?.serverProfileId ?: -1)
    }
    var selectedServerConfigId by remember(existing?.id) {
        mutableStateOf(existing?.serverConfigId.orEmpty())
    }
    var bindHost by remember(existing?.id) { mutableStateOf(existing?.bindHost ?: "127.0.0.1") }
    var bindPort by remember(existing?.id) { mutableStateOf((existing?.bindPort ?: 8080).toString()) }
    var udpTimeout by remember(existing?.id) {
        mutableStateOf((existing?.udpTimeoutSeconds ?: 60).toString())
    }
    var enabled by remember(existing?.id) { mutableStateOf(existing?.enabled ?: true) }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    var profileMenu by remember { mutableStateOf(false) }
    val dismissKeyboard = rememberKeyboardDismiss()
    var endpointMenu by remember { mutableStateOf(false) }

    // Refresh on every entry to this screen, even when cached profiles exist.
    LaunchedEffect(Unit) {
        onRefreshProfiles()
    }

    val selectedProfile = discovery.profiles.firstOrNull { it.info.userId == selectedProfileId }
    val availableEndpoints = selectedProfile?.serverConfigs?.filter { it.enabled }.orEmpty()
    val selectedEndpoint = availableEndpoints.firstOrNull { it.id == selectedServerConfigId }

    // If the saved server profile is no longer discoverable, keep its saved
    // reference so the user can still edit local bind settings.
    val fallbackProfile = existing?.let {
        DiscoveredProfile(
            ProfileInfo(it.serverProfileId, "saved", it.serverLabel),
            emptyList(),
        )
    }
    val fallbackEndpoint = existing?.let {
        ServerConfig(
            id = it.serverConfigId,
            name = it.serverName,
            protocol = it.protocol,
            targetHost = it.targetHost,
            targetPort = it.targetPort,
            enabled = true,
        )
    }
    val fallbackAllowed = existing != null &&
        (selectedProfile == null || selectedProfile.info.userId == existing.serverProfileId)
    val effectiveProfile = selectedProfile ?: fallbackProfile
    val effectiveEndpoint = selectedEndpoint ?: if (fallbackAllowed) fallbackEndpoint else null
    val protocol = effectiveEndpoint?.protocol ?: TunnelProtocol.TCP

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (discovery.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Looking for tunneler installations in other profiles…",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (discovery.error != null) {
            ErrorText(discovery.error)
            OutlinedButton(onClick = onRefreshProfiles) { Text("Retry profile scan") }
        }
        if (discovery.profiles.isEmpty() && !discovery.loading) {
            Text(
                "No tunneler profiles responded. Install the app in the target profile, grant INTERACT_ACROSS_USERS, and retry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRefreshProfiles) { Text("Refresh profiles") }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Server profile",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = onRefreshProfiles,
                enabled = !discovery.loading,
            ) {
                Text(if (discovery.loading) "Refreshing…" else "Refresh")
            }
        }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    dismissKeyboard()
                    profileMenu = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = discovery.profiles.isNotEmpty(),
            ) {
                Text(
                    selectedProfile?.info?.label
                        ?: fallbackProfile?.info?.label
                        ?: "Choose a profile",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                discovery.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(profile.info.label)
                                Text(
                                    "Profile ${profile.info.userId} · ${profile.info.type}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            profileMenu = false
                            selectedProfileId = profile.info.userId
                            selectedServerConfigId = ""
                        },
                    )
                }
            }
        }

        Text("Server endpoint", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    dismissKeyboard()
                    endpointMenu = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = availableEndpoints.isNotEmpty(),
            ) {
                Text(
                    selectedEndpoint?.displayName
                        ?: fallbackEndpoint?.displayName?.let { "Saved: $it" }
                        ?: "Choose a server endpoint",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = endpointMenu, onDismissRequest = { endpointMenu = false }) {
                availableEndpoints.forEach { endpoint ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(endpoint.displayName)
                                Text(
                                    "${endpoint.protocol.name} ${endpoint.targetHost}:${endpoint.targetPort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            endpointMenu = false
                            selectedServerConfigId = endpoint.id
                        },
                    )
                }
            }
        }

        if (effectiveEndpoint != null) {
            Text(
                "${protocol.name} ${effectiveEndpoint.targetHost}:${effectiveEndpoint.targetPort}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = bindHost,
            onValueChange = { bindHost = it },
            label = { Text("Bind host") },
            supportingText = { Text("Numeric IPv4 or IPv6; 127.0.0.1 is the normal choice") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (bindHost.isNotBlank() && !EndpointValidator.isLoopback(bindHost)) {
            Text(
                "This listener is reachable from the local network. Use 127.0.0.1 unless another device must connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = bindPort,
            onValueChange = { bindPort = it.filter(Char::isDigit).take(5) },
            label = { Text("Bind port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (protocol == TunnelProtocol.UDP) {
            OutlinedTextField(
                value = udpTimeout,
                onValueChange = { udpTimeout = it.filter(Char::isDigit).take(4) },
                label = { Text("UDP mapping timeout (seconds)") },
                supportingText = { Text("Any datagram in either direction resets the timer.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        ErrorText(error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                dismissKeyboard()
                onCancel()
            }) { Text("Cancel") }
            Button(onClick = {
                dismissKeyboard()
                error = onSave(
                    name,
                    effectiveProfile,
                    effectiveEndpoint,
                    bindHost,
                    bindPort,
                    udpTimeout,
                    enabled,
                )
            }) { Text("Save") }
        }
    }
}
