package com.oneturn.transfer.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SignalingRole {
    @SerialName("sender")
    Sender,

    @SerialName("receiver")
    Receiver,
}

@Serializable
sealed class SignalingMessage {
    @Serializable
    @SerialName("join")
    data class Join(
        val role: SignalingRole,
        val peerId: String,
        val deviceId: String? = null,
    ) : SignalingMessage()

    @Serializable
    @SerialName("joined")
    data class Joined(
        val peerId: String,
        val peers: List<String>,
    ) : SignalingMessage()

    @Serializable
    @SerialName("offer")
    data class Offer(
        val from: String,
        val sdp: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(
        val from: String,
        val sdp: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("ice")
    data class IceCandidate(
        val from: String,
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
    ) : SignalingMessage()

    @Serializable
    @SerialName("leave")
    data class Leave(
        val peerId: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("pake")
    data class Pake(
        val from: String,
        val payload: String,
    ) : SignalingMessage()
}

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String? = null,
)

@Serializable
data class CreateRoomResponse(
    val code: String,
    val joinUrl: String,
    val wsUrl: String,
    val expiresAt: Long,
)

@Serializable
data class JoinRoomResponse(
    val code: String,
    val wsUrl: String,
    val expiresAt: Long,
)

@Serializable
data class IceServersResponse(
    val iceServers: List<IceServerDto>,
    val ttl: Int,
)

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val publicKey: String,
    val displayName: String,
)

@Serializable
data class RegisterDeviceResponse(
    val deviceId: String,
    val pairingCode: String,
    val expiresAt: Long,
)

@Serializable
data class ClaimDeviceRequest(
    val pairingCode: String,
    val deviceId: String,
    val publicKey: String,
    val displayName: String,
)

@Serializable
data class TrustedDeviceDto(
    val deviceId: String,
    val publicKey: String,
    val displayName: String,
    val online: Boolean = false,
)

@Serializable
data class OnlineStatusRequest(
    val deviceIds: List<String>,
)

@Serializable
data class OnlineStatusResponse(
    val online: Map<String, Boolean>,
)

@Serializable
data class InviteDeviceRequest(
    val fromDeviceId: String,
    val toDeviceId: String,
    val fromDisplayName: String,
)

@Serializable
data class InviteDeviceResponse(
    val code: String,
    val joinUrl: String,
    val wsUrl: String,
    val expiresAt: Long,
    val inviteId: String,
    val toDeviceId: String,
)

@Serializable
data class TransferInviteDto(
    val inviteId: String,
    val code: String,
    val wsUrl: String,
    val fromDeviceId: String,
    val fromDisplayName: String,
    val toDeviceId: String,
    val expiresAt: Long,
)

@Serializable
data class ConsumeInviteRequest(
    val toDeviceId: String,
)

@Serializable
data class InviteNotification(
    val type: String,
    val inviteId: String = "",
    val code: String = "",
    val wsUrl: String = "",
    val fromDeviceId: String = "",
    val fromDisplayName: String = "",
    val expiresAt: Long = 0,
)

@Serializable
data class PresenceUpdateNotification(
    val type: String,
    val deviceId: String,
    val online: Boolean,
)
