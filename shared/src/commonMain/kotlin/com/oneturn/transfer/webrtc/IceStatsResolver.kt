package com.oneturn.transfer.webrtc

import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcStats

object IceStatsResolver {
    suspend fun detectAddressFamily(peerConnection: PeerConnection): PeerAddressFamily {
        val report = peerConnection.getStats() ?: return PeerAddressFamily.Unknown
        val stats = report.stats

        val transport = stats.values.firstOrNull { it.type == "transport" } ?: return PeerAddressFamily.Unknown
        val pairId = transport.members["selectedCandidatePairId"]?.toString() ?: return PeerAddressFamily.Unknown
        val pair = stats[pairId] ?: return PeerAddressFamily.Unknown

        val localId = pair.members["localCandidateId"]?.toString() ?: return PeerAddressFamily.Unknown
        val local = stats[localId] ?: return PeerAddressFamily.Unknown

        return classifyCandidate(local)
    }

    private fun classifyCandidate(stats: RtcStats): PeerAddressFamily {
        val candidateType = stats.members["candidateType"]?.toString()?.lowercase().orEmpty()
        if (candidateType == "relay") return PeerAddressFamily.Relay

        val address = stats.members["address"]?.toString().orEmpty()
        if (address.isEmpty()) return PeerAddressFamily.Unknown
        return if (isIpv6Address(address)) PeerAddressFamily.IPv6 else PeerAddressFamily.IPv4
    }

    fun isIpv6Address(address: String): Boolean {
        val host = address.trim().removePrefix("[").removeSuffix("]")
        return host.count { it == ':' } >= 2
    }
}
