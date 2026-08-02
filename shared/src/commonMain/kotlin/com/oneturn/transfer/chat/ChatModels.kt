package com.oneturn.transfer.chat

import com.oneturn.transfer.transfer.TransferPhase
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

/** A rendered entry in the chat timeline: a text message or a file transfer. */
sealed interface ChatItem {
    val id: String
    val isMine: Boolean
    val timestamp: Long

    data class Text(
        val messageId: String,
        val fromName: String,
        val text: String,
        override val isMine: Boolean,
        override val timestamp: Long,
    ) : ChatItem {
        override val id: String get() = messageId
    }

    data class File(
        val transferId: String,
        val fileName: String,
        val sizeBytes: Long,
        override val isMine: Boolean,
        override val timestamp: Long,
        val phase: TransferPhase,
        val bytesSent: Long = 0,
        val bytesPerSecond: Double = 0.0,
        val message: String = "",
    ) : ChatItem {
        override val id: String get() = "file-$transferId"

        val fraction: Float
            get() = if (sizeBytes <= 0) 0f else (bytesSent.toFloat() / sizeBytes).coerceIn(0f, 1f)
    }
}

fun ChatMessagePayload.toChatItemText(localDeviceId: String): ChatItem.Text =
    ChatItem.Text(
        messageId = messageId,
        fromName = fromName,
        text = text,
        isMine = from == localDeviceId,
        timestamp = timestamp,
    )
