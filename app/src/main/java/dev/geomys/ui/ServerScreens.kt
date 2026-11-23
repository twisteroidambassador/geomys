package dev.geomys.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.geomys.data.ConnectionInfo
import dev.geomys.data.ServerConfig
import dev.geomys.data.TunnelProtocol
import dev.geomys.net.EndpointValidator

@Composable
fun ServerListScreen(
    configs: List<ServerConfig>,
    connections: Map<String, ConnectionInfo>,
    profileLabel: String,
    onProfileLabelClick: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ServerConfig) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ProfileLabelHeader(label = profileLabel, onClick = onProfileLabelClick)
        Spacer(Modifier.height(12.dp))
        if (configs.isEmpty()) {
            EmptyState(
                title = "No server configs",
                detail = "Add a target endpoint that apps in other profiles should reach.",
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAdd) { Text("Add server") }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(configs, key = { it.id }) { config ->
                    ServerRow(
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
private fun ServerRow(
    config: ServerConfig,
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
                    text = "${config.protocol.name} ${config.targetHost}:${config.targetPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun ServerDetailScreen(
    config: ServerConfig,
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
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "${config.protocol.name} ${config.targetHost}:${config.targetPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
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
            connections.forEach { ConnectionRow(it, showPeer = true) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete server config?") },
            text = { Text("Active connections for this config will be closed.") },
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
fun ServerEditScreen(
    existing: ServerConfig?,
    onSave: (String, TunnelProtocol, String, String, Boolean) -> String?,
    onCancel: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var protocol by remember(existing?.id) { mutableStateOf(existing?.protocol ?: TunnelProtocol.TCP) }
    var host by remember(existing?.id) { mutableStateOf(existing?.targetHost ?: "127.0.0.1") }
    var port by remember(existing?.id) { mutableStateOf((existing?.targetPort ?: 8080).toString()) }
    var enabled by remember(existing?.id) { mutableStateOf(existing?.enabled ?: true) }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    val dismissKeyboard = rememberKeyboardDismiss()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Protocol", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TunnelProtocol.entries.forEach { option ->
                FilterChip(
                    selected = protocol == option,
                    onClick = { protocol = option },
                    label = { Text(option.name) },
                )
            }
        }
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Target host") },
            supportingText = { Text("Numeric IPv4 or IPv6 only, e.g. 127.0.0.1 or ::1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (host.isNotBlank() && !EndpointValidator.isLoopback(host)) {
            Text(
                "This target is not loopback. Traffic leaves the device; make sure the endpoint is trusted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Target port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
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
                error = onSave(name, protocol, host, port, enabled)
            }) { Text("Save") }
        }
    }
}
