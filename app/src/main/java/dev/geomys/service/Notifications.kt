package dev.geomys.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.geomys.MainActivity
import dev.geomys.R

object Notifications {
    const val CHANNEL_ID = "geomys_tunnel"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP = "dev.geomys.action.STOP"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tunnel service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps enabled client listeners running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, configCount: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, TunnelClientService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentTitle("Geomys")
            .setContentText(
                if (configCount == 0) "No client configs enabled"
                else "$configCount client config${if (configCount == 1) "" else "s"} active"
            )
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
