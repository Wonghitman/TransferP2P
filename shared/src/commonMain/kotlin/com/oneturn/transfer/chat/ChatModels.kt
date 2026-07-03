package com.oneturn.transfer.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessagePayload(
    val type: String = "chat",
    val messageId: String,
    val from: String,
    val fromName: String,
    val text: String,
    val timestamp: Long,
)

data class ChatLine(
    val messageId: String,
    val fromName: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
)

fun ChatMessagePayload.toChatLine(localDeviceId: String): ChatLine =
    ChatLine(
        messageId = messageId,
        fromName = fromName,
        text = text,
        isMine = from == localDeviceId,
        timestamp = timestamp,
    )
