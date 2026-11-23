package dev.geomys

import android.app.Application
import dev.geomys.data.TunnelRepository
import dev.geomys.service.Notifications

class GeomysApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TunnelRepository.init(this)
        Notifications.createChannel(this)
    }
}
