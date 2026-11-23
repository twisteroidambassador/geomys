package dev.geomys.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.UserHandle
import dev.geomys.IProfileTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Holds a Binder connection to the tunneler service in another profile.
 * The service is protected by a signature permission, so only the same app can bind.
 */
class PeerBinder(
    private val context: Context,
    val userId: Int,
    private val onDisconnected: (() -> Unit)? = null,
) {
    private var connection: ServiceConnection? = null
    private var bound = false
    private var tunnel: IProfileTunnel? = null

    suspend fun connect(timeoutMs: Long = 2000L): IProfileTunnel? {
        tunnel?.let { return it }
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            val value = IProfileTunnel.Stub.asInterface(service)
                            tunnel = value
                            if (cont.isActive) cont.resume(value)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            tunnel = null
                            onDisconnected?.invoke()
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            tunnel = null
                            onDisconnected?.invoke()
                        }
                    }
                    connection = conn
                    val intent = Intent(context, TunnelService::class.java)
                        .setComponent(ComponentName(context, TunnelService::class.java))
                    val ok = runCatching {
                        context.bindServiceAsUser(
                            intent,
                            conn,
                            Context.BIND_AUTO_CREATE,
                            UserHandle.getUserHandleForUid(userId * 100000),
                        )
                    }.getOrDefault(false)
                    bound = ok
                    if (!ok) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation {
                        runCatching { context.unbindService(conn) }
                        bound = false
                        connection = null
                    }
                }
            } ?: run {
                close()
                null
            }
        }
    }

    fun close() {
        val conn = connection
        if (bound && conn != null) {
            runCatching { context.unbindService(conn) }
        }
        bound = false
        connection = null
        tunnel = null
    }
}
