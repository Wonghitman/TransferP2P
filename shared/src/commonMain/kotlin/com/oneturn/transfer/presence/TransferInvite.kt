package com.oneturn.transfer.presence

import com.oneturn.transfer.signaling.TransferInviteDto

data class TransferInvite(
    val inviteId: String,
    val code: String,
    val wsUrl: String,
    val fromDeviceId: String,
    val fromDisplayName: String,
    val expiresAt: Long,
)

fun TransferInviteDto.toTransferInvite(): TransferInvite =
    TransferInvite(
        inviteId = inviteId,
        code = code,
        wsUrl = wsUrl,
        fromDeviceId = fromDeviceId,
        fromDisplayName = fromDisplayName,
        expiresAt = expiresAt,
    )
