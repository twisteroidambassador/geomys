package dev.geomys.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.geomys.BuildConfig
import dev.geomys.data.ActivityEvent
import dev.geomys.data.TunnelRepository
import android.content.pm.PackageManager

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    profileLabel: String,
    startOnBoot: Boolean,
    onOpenActivity: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var permissionRefreshKey by remember { mutableIntStateOf(0) }

    val permissions = remember(permissionRefreshKey) {
        PermissionSnapshot(
            interact = ContextCompat.checkSelfPermission(
                context,
                "android.permission.INTERACT_ACROSS_USERS",
            ) == PackageManager.PERMISSION_GRANTED,
            notifications = ContextCompat.checkSelfPermission(
                context,
                "android.permission.POST_NOTIFICATIONS",
            ) == PackageManager.PERMISSION_GRANTED,
            localNetwork = ContextCompat.checkSelfPermission(
                context,
                "android.permission.ACCESS_LOCAL_NETWORK",
            ) == PackageManager.PERMISSION_GRANTED,
            battery = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )
    }
    val notificationGranted = permissions.notifications
    val interactGranted = permissions.interact
    val localNetworkGranted = permissions.localNetwork
    val batteryGranted = permissions.battery

    // Return from an adb grant, permission dialog, or system settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionRefreshKey++
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefreshKey++ }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionRefreshKey++ }

    val grantCommand = "adb shell pm grant --user ${vm.myUserId} ${context.packageName} " +
        "android.permission.INTERACT_ACROSS_USERS"

    var label by remember(profileLabel) { mutableStateOf(profileLabel) }
    val dismissKeyboard = rememberKeyboardDismiss()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("This profile", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Profile ${vm.myUserId} · ${vm.myProfileType}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The server tab displays this label. Peers see it when they discover this profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Profile label") },
            supportingText = { Text("Default is Profile ${vm.myUserId}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                dismissKeyboard()
                vm.setProfileLabel(label)
            }) { Text("Save label") }
            OutlinedButton(onClick = {
                dismissKeyboard()
                vm.resetProfileLabel()
                label = "Profile ${vm.myUserId}"
            }) { Text("Reset") }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Permissions",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { permissionRefreshKey++ }) {
                Text("Recheck all")
            }
        }
        PermissionCard(
            title = "INTERACT_ACROSS_USERS",
            description = "Required to discover and bind to tunneler profiles. This permission " +
                "cannot be granted from the app; use adb.",
            granted = interactGranted,
            actionLabel = "Copy adb command",
            onAction = { copyToClipboard(context, grantCommand) },
            extra = grantCommand,
        )
        OutlinedButton(onClick = { copyToClipboard(context, grantCommand) }) {
            Text("Copy adb command")
        }
        PermissionCard(
            title = "Notifications",
            description = "The foreground service needs a notification so the relay can keep " +
                "running while the profile is in the background.",
            granted = notificationGranted,
            actionLabel = "Allow",
            onAction = {
                notificationLauncher.launch("android.permission.POST_NOTIFICATIONS")
            },
        )
        PermissionCard(
            title = "Unrestricted battery usage",
            description = "Recommended so the OS does not stop the relay while the screen is off.",
            granted = batteryGranted,
            actionLabel = "Open settings",
            onAction = {
                val intent = Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(intent) }
            },
        )
        PermissionCard(
            title = "Local network",
            description = "Only needed if a server or client config uses a non-loopback address.",
            granted = localNetworkGranted,
            actionLabel = "Allow",
            onAction = {
                localNetworkLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            },
        )

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Start automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Start enabled client listeners after boot or an app update, once the profile is unlocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = startOnBoot,
                onCheckedChange = { vm.setStartOnBoot(it) },
            )
        }

        HorizontalDivider()
        Text("More", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = {
                dismissKeyboard()
                onOpenActivity()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Activity")
        }
        OutlinedButton(
            onClick = {
                dismissKeyboard()
                onOpenAbout()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("About")
        }
    }
}

@Composable
fun ActivityScreen(
    activity: List<ActivityEvent>,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recent activity",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (activity.isEmpty()) {
            EmptyState("No activity yet", "Connection events and errors will appear here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(activity) { event -> ActivityRow(event) }
            }
        }
    }
}

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Geomys", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Version ${BuildConfig.VERSION_NAME} · protocol ${TunnelRepository.PROTOCOL_VERSION}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Licensed under the GNU General Public License v3.0.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "A local relay for Android 17+. It bridges TCP and UDP listeners in one profile " +
                "to target endpoints in another profile over Binder and a socketpair.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Security", style = MaterialTheme.typography.titleSmall)
                Text(
                    "This app deliberately crosses profile boundaries. Any app in the client " +
                        "profile can connect to a client listener, and the target sees this " +
                        "app's identity, not the original app's. Enable only the configs you " +
                        "need, prefer loopback hosts, and do not use it to expose sensitive " +
                        "services to untrusted apps.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Requirements", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android 17+ and INTERACT_ACROSS_USERS granted via adb. The client " +
                        "profile needs the permission; the server profile only needs the app " +
                        "installed and the AIDL service enabled.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "No telemetry. Configs and logs stay on the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Geomys command", text))
}

private data class PermissionSnapshot(
    val interact: Boolean,
    val notifications: Boolean,
    val localNetwork: Boolean,
    val battery: Boolean,
)
