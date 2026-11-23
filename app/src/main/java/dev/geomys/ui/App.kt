package dev.geomys.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.geomys.data.ClientConfig
import dev.geomys.data.ServerConfig
import dev.geomys.service.TunnelClientService

enum class AppTab { Server, Client, Settings }
enum class SettingsPage { Main, Activity, About }

sealed interface ServerRoute {
    data object List : ServerRoute
    data class Detail(val id: String) : ServerRoute
    data class Edit(val id: String?) : ServerRoute
}

sealed interface ClientRoute {
    data object List : ClientRoute
    data class Detail(val id: String) : ClientRoute
    data class Edit(val id: String?) : ClientRoute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeomysApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val serverConfigs by vm.serverConfigs.collectAsStateWithLifecycle()
    val clientConfigs by vm.clientConfigs.collectAsStateWithLifecycle()
    val profileLabel by vm.profileLabel.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val discovery by vm.discovery.collectAsStateWithLifecycle()
    val startOnBoot by vm.startOnBoot.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(AppTab.Server) }
    var serverRoute by remember { mutableStateOf<ServerRoute>(ServerRoute.List) }
    var clientRoute by remember { mutableStateOf<ClientRoute>(ClientRoute.List) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Main) }

    LaunchedEffect(Unit) { TunnelClientService.sync(context) }

    val canGoBack = when (tab) {
        AppTab.Server -> serverRoute !is ServerRoute.List
        AppTab.Client -> clientRoute !is ClientRoute.List
        AppTab.Settings -> settingsPage != SettingsPage.Main
    }

    fun goBack() {
        when (tab) {
            AppTab.Server -> serverRoute = ServerRoute.List
            AppTab.Client -> clientRoute = ClientRoute.List
            AppTab.Settings -> settingsPage = SettingsPage.Main
        }
    }

    BackHandler(enabled = canGoBack) { goBack() }

    val dismissKeyboard = rememberKeyboardDismiss()

    val title = when (tab) {
        AppTab.Server -> when (val route = serverRoute) {
            ServerRoute.List -> "Server"
            is ServerRoute.Detail -> serverConfigs.firstOrNull { it.id == route.id }?.displayName ?: "Server"
            is ServerRoute.Edit -> if (route.id == null) "Add server" else "Edit server"
        }
        AppTab.Client -> when (val route = clientRoute) {
            ClientRoute.List -> "Client"
            is ClientRoute.Detail -> clientConfigs.firstOrNull { it.id == route.id }?.displayName ?: "Client"
            is ClientRoute.Edit -> if (route.id == null) "Add client" else "Edit client"
        }
        AppTab.Settings -> when (settingsPage) {
            SettingsPage.Main -> "Settings"
            SettingsPage.Activity -> "Activity"
            SettingsPage.About -> "About"
        }
    }

    BoxWithConstraints {
        val expanded = maxWidth >= 600.dp
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars,
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = { goBack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!expanded) {
                    NavigationBar {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = {
                                    dismissKeyboard()
                                    tab = item
                                },
                                icon = { Icon(item.icon(), contentDescription = null) },
                                label = { Text(item.label()) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                when {
                    tab == AppTab.Server && serverRoute is ServerRoute.List -> {
                        FloatingActionButton(onClick = { serverRoute = ServerRoute.Edit(null) }) {
                            Icon(Icons.Default.Add, contentDescription = "Add server")
                        }
                    }
                    tab == AppTab.Client && clientRoute is ClientRoute.List -> {
                        FloatingActionButton(onClick = { clientRoute = ClientRoute.Edit(null) }) {
                            Icon(Icons.Default.Add, contentDescription = "Add client")
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (expanded) {
                    NavigationRail {
                        AppTab.entries.forEach { item ->
                            NavigationRailItem(
                                selected = tab == item,
                                onClick = {
                                    dismissKeyboard()
                                    tab = item
                                },
                                icon = { Icon(item.icon(), contentDescription = null) },
                                label = { Text(item.label()) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        AppTab.Server -> ServerContent(
                            route = serverRoute,
                            configs = serverConfigs,
                            connections = connections,
                            profileLabel = profileLabel,
                            onProfileLabelClick = {
                                tab = AppTab.Settings
                                settingsPage = SettingsPage.Main
                            },
                            onRoute = { serverRoute = it },
                            onToggle = vm::setServerEnabled,
                            onDelete = vm::deleteServer,
                            onSave = vm::saveServer,
                        )
                        AppTab.Client -> ClientContent(
                            route = clientRoute,
                            configs = clientConfigs,
                            connections = connections,
                            discovery = discovery,
                            onRoute = { clientRoute = it },
                            onToggle = vm::setClientEnabled,
                            onDelete = vm::deleteClient,
                            onSave = vm::saveClient,
                            onRefreshProfiles = { vm.loadDiscovery(force = true) },
                        )
                        AppTab.Settings -> SettingsContent(
                            page = settingsPage,
                            vm = vm,
                            activity = activity,
                            profileLabel = profileLabel,
                            startOnBoot = startOnBoot,
                            onPage = { settingsPage = it },
                            onClearActivity = vm::clearActivity,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerContent(
    route: ServerRoute,
    configs: List<ServerConfig>,
    connections: Map<String, dev.geomys.data.ConnectionInfo>,
    profileLabel: String,
    onProfileLabelClick: () -> Unit,
    onRoute: (ServerRoute) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (String?, String, dev.geomys.data.TunnelProtocol, String, String, Boolean) -> String?,
) {
    when (route) {
        ServerRoute.List -> ServerListScreen(
            configs = configs,
            connections = connections,
            profileLabel = profileLabel,
            onProfileLabelClick = onProfileLabelClick,
            onAdd = { onRoute(ServerRoute.Edit(null)) },
            onOpen = { onRoute(ServerRoute.Detail(it.id)) },
            onToggle = onToggle,
        )
        is ServerRoute.Detail -> {
            val config = configs.firstOrNull { it.id == route.id }
            if (config == null) {
                onRoute(ServerRoute.List)
            } else {
                ServerDetailScreen(
                    config = config,
                    connections = connections.values.filter { it.configId == config.id },
                    onEdit = { onRoute(ServerRoute.Edit(config.id)) },
                    onToggle = { onToggle(config.id, it) },
                    onDelete = {
                        onDelete(config.id)
                        onRoute(ServerRoute.List)
                    },
                )
            }
        }
        is ServerRoute.Edit -> {
            val existing = route.id?.let { id -> configs.firstOrNull { it.id == id } }
            ServerEditScreen(
                existing = existing,
                onSave = { name, protocol, host, port, enabled ->
                    val error = onSave(existing?.id, name, protocol, host, port, enabled)
                    if (error == null) onRoute(ServerRoute.List)
                    error
                },
                onCancel = { onRoute(ServerRoute.List) },
            )
        }
    }
}

@Composable
private fun ClientContent(
    route: ClientRoute,
    configs: List<ClientConfig>,
    connections: Map<String, dev.geomys.data.ConnectionInfo>,
    discovery: DiscoveryUiState,
    onRoute: (ClientRoute) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (String?, String, dev.geomys.data.DiscoveredProfile?, ServerConfig?, String, String, String, Boolean) -> String?,
    onRefreshProfiles: () -> Unit,
) {
    when (route) {
        ClientRoute.List -> ClientListScreen(
            configs = configs,
            connections = connections,
            onAdd = { onRoute(ClientRoute.Edit(null)) },
            onOpen = { onRoute(ClientRoute.Detail(it.id)) },
            onToggle = onToggle,
        )
        is ClientRoute.Detail -> {
            val config = configs.firstOrNull { it.id == route.id }
            if (config == null) {
                onRoute(ClientRoute.List)
            } else {
                ClientDetailScreen(
                    config = config,
                    connections = connections.values.filter { it.configId == config.id },
                    onEdit = { onRoute(ClientRoute.Edit(config.id)) },
                    onToggle = { onToggle(config.id, it) },
                    onDelete = {
                        onDelete(config.id)
                        onRoute(ClientRoute.List)
                    },
                )
            }
        }
        is ClientRoute.Edit -> {
            val existing = route.id?.let { id -> configs.firstOrNull { it.id == id } }
            ClientEditScreen(
                existing = existing,
                discovery = discovery,
                onRefreshProfiles = onRefreshProfiles,
                onSave = { name, profile, serverConfig, bindHost, bindPort, udpTimeout, enabled ->
                    val error = onSave(
                        existing?.id,
                        name,
                        profile,
                        serverConfig,
                        bindHost,
                        bindPort,
                        udpTimeout,
                        enabled,
                    )
                    if (error == null) onRoute(ClientRoute.List)
                    error
                },
                onCancel = { onRoute(ClientRoute.List) },
            )
        }
    }
}

@Composable
private fun SettingsContent(
    page: SettingsPage,
    vm: MainViewModel,
    activity: List<dev.geomys.data.ActivityEvent>,
    profileLabel: String,
    startOnBoot: Boolean,
    onPage: (SettingsPage) -> Unit,
    onClearActivity: () -> Unit,
) {
    when (page) {
        SettingsPage.Main -> SettingsScreen(
            vm = vm,
            profileLabel = profileLabel,
            startOnBoot = startOnBoot,
            onOpenActivity = { onPage(SettingsPage.Activity) },
            onOpenAbout = { onPage(SettingsPage.About) },
        )
        SettingsPage.Activity -> ActivityScreen(
            activity = activity,
            onClear = onClearActivity,
        )
        SettingsPage.About -> AboutScreen()
    }
}

private fun AppTab.label(): String = when (this) {
    AppTab.Server -> "Server"
    AppTab.Client -> "Client"
    AppTab.Settings -> "Settings"
}

private fun AppTab.icon() = when (this) {
    AppTab.Server -> Icons.Default.Lan
    AppTab.Client -> Icons.Default.Cable
    AppTab.Settings -> Icons.Default.Settings
}
