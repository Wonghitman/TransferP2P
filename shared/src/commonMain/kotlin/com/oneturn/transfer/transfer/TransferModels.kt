package com.oneturn.transfer.transfer

import kotlinx.serialization.Serializable

@Serializable
data class TransferManifest(
    val type: String = "start",
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val sha256: String,
)

@Serializable
data class TransferComplete(
    val type: String = "end",
    val transferId: String,
    val sha256: String,
    val sizeBytes: Long = 0,
)

@Serializable
data class TransferError(
    val transferId: String,
    val message: String,
)

enum class TransferPhase {
    Idle,
    Handshaking,
    Transferring,
    Verifying,
    Completed,
    Failed,
}

data class TransferProgress(
    val phase: TransferPhase = TransferPhase.Idle,
    val fileName: String = "",
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val message: String = "",
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f)
}
