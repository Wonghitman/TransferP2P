package com.oneturn.transfer.webrtc

private const val IPV6_PRIORITY_BOOST = 1_000_000_000L

/**
 * Raises IPv6 ICE candidate priority so connectivity checks prefer v6 when both are available.
 */
fun boostIpv6CandidatePriority(candidate: String): String {
    val trimmed = candidate.trim()
    val hasPrefix = trimmed.startsWith("candidate:")
    val body = if (hasPrefix) trimmed.removePrefix("candidate:") else trimmed
    val tokens = body.split(Regex("\\s+"))
    if (tokens.size < 6) return candidate

    val ip = tokens[4]
    if (!IceStatsResolver.isIpv6Address(ip)) return candidate

    val priority = tokens[3].toLongOrNull() ?: return candidate
    val boosted = (priority + IPV6_PRIORITY_BOOST).coerceAtMost(Long.MAX_VALUE)
    val updated = tokens.toMutableList()
    updated[3] = boosted.toString()
    val rebuilt = updated.joinToString(" ")
    return if (hasPrefix) "candidate:$rebuilt" else rebuilt
}
