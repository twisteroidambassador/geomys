package dev.geomys.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

object EndpointValidator {
    private val ipv4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    fun parseHost(raw: String): InetAddress {
        val input = raw.trim()
        require(input.isNotEmpty()) { "Host is required" }
        require(!input.contains(' ')) { "Host must be a numeric IP address" }

        val scopeIndex = input.indexOf('%')
        if (scopeIndex >= 0) {
            val addressPart = input.substring(0, scopeIndex)
            val scopeName = input.substring(scopeIndex + 1)
            require(addressPart.contains(':')) { "Only IPv6 addresses can have a scope" }
            require(scopeName.isNotBlank()) { "IPv6 scope is required after %" }
            val base = runCatching { InetAddress.getByName(addressPart) }
                .getOrElse { throw IllegalArgumentException("Invalid IPv6 address") }
            require(base is Inet6Address) { "Only IPv6 addresses can have a scope" }
            val nif = NetworkInterface.getByName(scopeName)
                ?: throw IllegalArgumentException("Unknown IPv6 scope: $scopeName")
            return Inet6Address.getByAddress(null, base.address, nif)
        }

        val isV4 = ipv4.matches(input)
        val isV6 = input.contains(':')
        require(isV4 || isV6) { "Enter a numeric IPv4 or IPv6 address (no hostnames)" }
        val address = runCatching { InetAddress.getByName(input) }
            .getOrElse { throw IllegalArgumentException("Invalid IP address") }
        require(address is Inet4Address || address is Inet6Address) {
            "Enter a numeric IPv4 or IPv6 address (no hostnames)"
        }
        return address
    }

    fun validatePort(port: Int, label: String = "Port"): String? =
        if (port !in 1..65535) "$label must be between 1 and 65535" else null

    fun isLoopback(address: InetAddress): Boolean = address.isLoopbackAddress

    fun isLoopback(raw: String): Boolean = runCatching { parseHost(raw).isLoopbackAddress }.getOrDefault(false)

    fun hostString(address: InetAddress): String = address.hostAddress ?: address.toString()
}
