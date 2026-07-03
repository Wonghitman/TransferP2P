package com.oneturn.transfer.webrtc

import com.oneturn.transfer.signaling.IceServerDto

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

fun IceServerDto.toConfig(): IceServerConfig =
    IceServerConfig(urls = urls, username = username, credential = credential)

enum class ConnectionMode {
    Connecting,
    Direct,
    Relay,
    Failed,
    Closed,
}

enum class PeerAddressFamily {
    Unknown,
    IPv4,
    IPv6,
    Relay,
}

enum class PeerRole {
    Initiator,
    Responder,
}

sealed interface DataChannelMessage {
    data class Text(val text: String) : DataChannelMessage
    data class Binary(val bytes: ByteArray) : DataChannelMessage
    data class ChunkAck(val ackedBytes: Long) : DataChannelMessage
}
