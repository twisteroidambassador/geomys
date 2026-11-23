package dev.geomys.profile

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import dev.geomys.data.DiscoveredProfile
import dev.geomys.data.ProfileInfo
import dev.geomys.data.ServerConfig
import dev.geomys.service.PeerBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ProfileDiscovery(private val context: Context) {
    data class Result(
        val profiles: List<DiscoveredProfile>,
        val error: String? = null,
    )

    suspend fun discover(): Result = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result(
                emptyList(),
                "INTERACT_ACROSS_USERS is not granted. Run the adb command in Settings.",
            )
        }

        val userManager = context.getSystemService(UserManager::class.java)
            ?: return@withContext Result(emptyList(), "UserManager unavailable")

        val candidates = LinkedHashSet<Int>()
        runCatching {
            userManager.getUserProfiles().forEach { candidates.add(it.hashCode()) }
        }
        var scanError: String? = null
        for (id in 0..MAX_SCAN_ID) {
            try {
                val handle = UserHandle.getUserHandleForUid(id * 100000)
                val serial = userManager.getSerialNumberForUser(handle)
                if (serial >= 0) candidates.add(id)
            } catch (e: SecurityException) {
                scanError = "Cannot scan for secondary users: ${e.message}"
                break
            } catch (_: Exception) {
                // Id is absent.
            }
        }

        val found = mutableListOf<DiscoveredProfile>()
        for (id in candidates.sorted()) {
            probe(id)?.let { found.add(it) }
        }
        Result(
            profiles = found,
            error = when {
                found.isEmpty() && scanError != null -> scanError
                else -> null
            },
        )
    }

    private suspend fun probe(userId: Int): DiscoveredProfile? {
        val peer = PeerBinder(context, userId)
        return try {
            val tunnel = peer.connect() ?: return null
            val infoJson = runCatching { tunnel.profileInfoJson }.getOrNull() ?: return null
            val configsJson = runCatching { tunnel.serverConfigsJson }.getOrNull() ?: "[]"
            val info = ProfileInfo.fromJson(org.json.JSONObject(infoJson))
            val configs = runCatching {
                val array = JSONArray(configsJson)
                (0 until array.length()).map { ServerConfig.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            DiscoveredProfile(
                info = info.copy(userId = if (info.userId == 0) userId else info.userId),
                serverConfigs = configs,
            )
        } catch (_: Exception) {
            null
        } finally {
            peer.close()
        }
    }

    companion object {
        const val PERMISSION = "android.permission.INTERACT_ACROSS_USERS"
        const val MAX_SCAN_ID = 99
    }
}
