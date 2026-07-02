package com.oneturn.transfer.transfer

import com.oneturn.transfer.webrtc.WebRtcSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.HashingSink
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer

class FileTransferSender(
    private val session: WebRtcSession,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    suspend fun send(manifest: TransferManifest, source: Source) {
        _progress.value = TransferProgress(
            phase = TransferPhase.Handshaking,
            fileName = manifest.fileName,
            totalBytes = manifest.sizeBytes,
        )
        session.sendText(json.encodeToString(TransferManifest.serializer(), manifest))

        val buffer = ByteArray(chunkSize)
        var sentBytes = 0L
        var chunkIndex = 0
        var lastReportNanos = System.nanoTime()
        var lastReportBytes = 0L

        _progress.value = _progress.value.copy(phase = TransferPhase.Transferring)

        source.buffer().use { bufferedSource ->
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                awaitBackpressure()
                val read = bufferedSource.read(buffer)
                if (read == -1) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val frame = encodeChunk(manifest.transferId, chunkIndex, chunk)
                session.sendBinary(frame)
                sentBytes += read
                chunkIndex++

                val now = System.nanoTime()
                if (now - lastReportNanos >= 500_000_000) {
                    val elapsedSec = (now - lastReportNanos) / 1_000_000_000.0
                    val delta = sentBytes - lastReportBytes
                    val bps = if (elapsedSec > 0) delta / elapsedSec else 0.0
                    _progress.value = _progress.value.copy(
                        bytesSent = sentBytes,
                        bytesPerSecond = bps,
                    )
                    lastReportNanos = now
                    lastReportBytes = sentBytes
                }
            }
        }

        _progress.value = _progress.value.copy(
            phase = TransferPhase.Verifying,
            bytesSent = manifest.sizeBytes,
        )
        session.sendText(
            json.encodeToString(
                TransferComplete.serializer(),
                TransferComplete(manifest.transferId, manifest.sha256),
            ),
        )
        _progress.value = _progress.value.copy(phase = TransferPhase.Completed)
    }

    private suspend fun awaitBackpressure() {
        while (session.bufferedAmount > session.bufferedAmountLowThreshold * 2) {
            delay(5)
        }
    }

    private fun encodeChunk(transferId: String, chunkIndex: Int, payload: ByteArray): ByteArray {
        val idBytes = transferId.encodeToByteArray()
        val header = Buffer()
        header.writeIntLe(idBytes.size)
        header.write(idBytes)
        header.writeIntLe(chunkIndex)
        header.writeIntLe(payload.size)
        return header.readByteArray() + payload
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 16 * 1024
    }
}

class FileTransferReceiver(
    private val session: WebRtcSession,
    private val onManifest: suspend (TransferManifest) -> Sink,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    private var manifest: TransferManifest? = null
    private var hashingSink: HashingSink? = null
    private var receivedBytes = 0L
    private var lastReportNanos = 0L
    private var lastReportBytes = 0L

    suspend fun handleText(text: String) {
        when {
            text.contains("\"chunkSize\"") && text.contains("\"transferId\"") -> {
                val parsed = json.decodeFromString(TransferManifest.serializer(), text)
                startReceive(parsed)
            }
            text.contains("\"sha256\"") && text.contains("\"transferId\"") -> {
                val complete = json.decodeFromString(TransferComplete.serializer(), text)
                verifyAndComplete(complete)
            }
            text.contains("\"message\"") -> {
                val error = json.decodeFromString(TransferError.serializer(), text)
                _progress.value = _progress.value.copy(
                    phase = TransferPhase.Failed,
                    message = error.message,
                )
            }
        }
    }

    suspend fun handleBinary(bytes: ByteArray) {
        val manifest = manifest ?: return
        val buffer = Buffer().write(bytes)
        val idLen = buffer.readIntLe()
        val transferId = buffer.readUtf8(idLen.toLong())
        if (transferId != manifest.transferId) return
        val chunkIndex = buffer.readIntLe()
        val payloadLen = buffer.readIntLe()
        val payload = buffer.readByteArray(payloadLen.toLong())

        hashingSink?.buffer()?.use { sink ->
            sink.write(payload)
            sink.flush()
        }
        receivedBytes += payload.size

        val now = System.nanoTime()
        if (now - lastReportNanos >= 500_000_000) {
            val elapsedSec = (now - lastReportNanos) / 1_000_000_000.0
            val delta = receivedBytes - lastReportBytes
            val bps = if (elapsedSec > 0) delta / elapsedSec else 0.0
            _progress.value = _progress.value.copy(
                bytesSent = receivedBytes,
                bytesPerSecond = bps,
            )
            lastReportNanos = now
            lastReportBytes = receivedBytes
        }

        session.sendText(
            json.encodeToString(
                TransferAck.serializer(),
                TransferAck(transferId, chunkIndex),
            ),
        )
    }

    private suspend fun startReceive(parsed: TransferManifest) {
        manifest = parsed
        val outputSink = onManifest(parsed)
        val hashSink = HashingSink.sha256(outputSink)
        hashingSink = hashSink
        _progress.value = TransferProgress(
            phase = TransferPhase.Transferring,
            fileName = parsed.fileName,
            totalBytes = parsed.sizeBytes,
        )
    }

    private fun verifyAndComplete(complete: TransferComplete) {
        hashingSink?.close()
        val digest = hashingSink?.hash?.hex() ?: ""
        val ok = digest.equals(complete.sha256, ignoreCase = true)
        _progress.value = _progress.value.copy(
            phase = if (ok) TransferPhase.Completed else TransferPhase.Failed,
            bytesSent = receivedBytes,
            message = if (ok) "校验通过" else "校验失败: $digest",
        )
        manifest = null
        hashingSink = null
        receivedBytes = 0L
    }
}

fun computeSha256(source: Source): String {
    val hashSink = HashingSink.sha256(blackholeSink())
    source.buffer().use { input ->
        hashSink.buffer().use { output ->
            output.writeAll(input)
            output.flush()
        }
    }
    hashSink.close()
    return hashSink.hash.hex()
}

fun estimateChunkCount(sizeBytes: Long, chunkSize: Int): Int =
    if (sizeBytes <= 0) 0 else ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
