package dev.geomys.profile

import dev.geomys.data.ProfileInfo
import dev.geomys.data.TunnelRepository

object ProfileIdentity {
    fun current(): ProfileInfo = TunnelRepository.profileInfo()
    fun label(): String = TunnelRepository.profileLabel.value
    fun userId(): Int = TunnelRepository.myUserId()
    fun type(): String = TunnelRepository.profileType()
}
