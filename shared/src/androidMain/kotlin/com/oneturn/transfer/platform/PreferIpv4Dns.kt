package com.oneturn.transfer.platform

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

internal object PreferIpv4Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 + addresses.filterNot { it is Inet4Address } else addresses
    }
}
