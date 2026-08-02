package com.oneturn.transfer.transfer

import com.oneturn.transfer.platform.monotonicNanos
import com.oneturn.transfer.platform.publishReceivedFile
import com.oneturn.transfer.platform.spoolAndHash
import com.oneturn.transfer.webrtc.WebRtcSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSink
import okio.HashingSink
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

/**
 * Push-based block transfer (LocalSend-style):
 * the sender streams every chunk onto the ordered/reliable DataChannel,
 * the receiver writes chunks in arrival order. No per-chunk request round-trips.
 */
class FileTransferSender(
    private val session: WebRtcSession,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    private var activeTransferId: String? = null

    suspend fun send(
        fileName: String,
        mimeType: String,
        transferId: String,
        source: Source,
    ) {
        activeTransferId = transferId

        _progress.value = TransferProgress(
            phase = TransferPhase.Handshaking,
            fileName = fileName,
            message = "准备发送...",
        )

        spoolAndHash(source).use { data ->
            val sizeBytes = data.sizeBytes
            val totalChunks = estimateChunkCount(sizeBytes, chunkSize)
            val manifest = TransferManifest(
                transferId = transferId,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                sha256 = data.sha256,
            )

            _progress.value = _progress.value.copy(
                phase = TransferPhase.Handshaking,
                totalBytes = sizeBytes,
                message = "准备发送...",
            )
            session.sendTextReliable(json.encodeToString(TransferManifest.serializer(), manifest), drainAfterSend = true)

            _progress.value = _progress.value.copy(
                phase = TransferPhase.Transferring,
                totalBytes = sizeBytes,
                message = "正在发送...",
            )

            var sentBytes = 0L
            var lastReportNanos = monotonicNanos()
            var lastReportBytes = 0L

            for (index in 0 until totalChunks) {
                if (!currentCoroutineContext().isActive) break
                val chunk = data.readChunk(index, chunkSize)
                session.sendBinaryReliable(encodeFrame(index, chunk))
                sentBytes += chunk.size

                val now = monotonicNanos()
                if (now - lastReportNanos >= 200_000_000) {
                    val elapsedSec = (now - lastReportNanos) / 1_000_000_000.0
                    val delta = sentBytes - lastReportBytes
                    val bytesPerSecond = if (elapsedSec > 0) delta / elapsedSec else 0.0
                    lastReportNanos = now
                    lastReportBytes = sentBytes
                    _progress.value = _progress.value.copy(
                        bytesSent = sentBytes,
                        totalBytes = sizeBytes,
                        bytesPerSecond = bytesPerSecond,
                        message = "已发送 $sentBytes/$sizeBytes 字节",
                    )
                }
            }

            session.awaitDrain()
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Verifying,
                bytesSent = sentBytes,
                message = "发送完成信息...",
            )
            session.sendBinaryReliable(
                encodeFrame(
                    FRAME_COMPLETE,
                    json.encodeToString(
                        TransferComplete.serializer(),
                        TransferComplete(
                            transferId = manifest.transferId,
                            sha256 = data.sha256,
                            sizeBytes = sentBytes,
                        ),
                    ).encodeToByteArray(),
                ),
                drainAfterSend = true,
            )
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Completed,
                bytesSent = sentBytes,
                totalBytes = sizeBytes,
                message = "",
            )
        }
        activeTransferId = null
    }

    private fun encodeFrame(frameType: Int, payload: ByteArray): ByteArray {
        val header = Buffer()
        header.writeIntLe(frameType)
        header.writeIntLe(payload.size)
        return header.readByteArray() + payload
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 16 * 1024
        const val FRAME_COMPLETE = -1
    }
}

class FileTransferReceiver(
    private val session: WebRtcSession,
    private val scope: CoroutineScope,
    private val onManifest: suspend (TransferManifest) -> Sink,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    private var manifest: TransferManifest? = null
    private var hashingSink: HashingSink? = null
    private var bufferedOutput: BufferedSink? = null
    private var receivedBytes = 0L
    private var expectedBytes = 0L
    private var receivedChunks = 0
    private var totalChunks = 0
    private var lastReportNanos = 0L
    private var lastReportBytes = 0L
    private var pendingComplete: TransferComplete? = null
    private var pendingTimeoutJob: Job? = null
    private var awaitCompleteJob: Job? = null
    private val binaryQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()

    init {
        scope.launch {
            for (bytes in binaryQueue) {
                writeMutex.withLock {
                    processBinary(bytes)
                }
            }
        }
    }

    suspend fun handleText(text: String) {
        runCatching { json.decodeFromString(TransferManifest.serializer(), text) }
            .onSuccess {
                writeMutex.withLock { startReceive(it) }
                return
            }

        runCatching { json.decodeFromString(TransferComplete.serializer(), text) }
            .onSuccess { verifyAndComplete(it); return }

        runCatching { json.decodeFromString(TransferError.serializer(), text) }
            .onSuccess { error ->
                _progress.value = _progress.value.copy(
                    phase = TransferPhase.Failed,
                    message = error.message,
                )
            }
    }

    suspend fun handleBinary(bytes: ByteArray) {
        binaryQueue.send(bytes)
    }

    private suspend fun processBinary(bytes: ByteArray) {
        if (bytes.size < 8) {
            if (manifest != null) {
                _progress.value = _progress.value.copy(
                    phase = TransferPhase.Failed,
                    message = "分片格式错误 (${bytes.size} 字节)",
                )
            }
            return
        }
        val buffer = Buffer().write(bytes)
        val frameType = buffer.readIntLe()
        val payloadLen = buffer.readIntLe()
        if (payloadLen < 0 || buffer.size < payloadLen) {
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Failed,
                message = "帧 $frameType 数据不完整",
            )
            return
        }
        val payload = buffer.readByteArray(payloadLen.toLong())

        when (frameType) {
            FileTransferSender.FRAME_COMPLETE -> {
                runCatching {
                    json.decodeFromString(TransferComplete.serializer(), payload.decodeToString())
                }.onSuccess { verifyAndComplete(it) }
                    .onFailure {
                        _progress.value = _progress.value.copy(
                            phase = TransferPhase.Failed,
                            message = "完成信息解析失败",
                        )
                    }
                return
            }
        }

        if (frameType < 0) return
        if (manifest == null) return

        // Push protocol: chunks must arrive in order.
        if (frameType != receivedChunks) {
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Failed,
                message = "分片顺序异常（期望 $receivedChunks，收到 $frameType）",
            )
            return
        }
        writeChunk(payload)
    }

    private suspend fun writeChunk(payload: ByteArray) {
        val sink = bufferedOutput
        if (sink == null) {
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Failed,
                message = "接收未就绪",
            )
            return
        }
        try {
            sink.write(payload)
            if (receivedChunks % 16 == 15) {
                sink.flush()
            }
        } catch (error: Exception) {
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Failed,
                message = "写入失败: ${error.message ?: "未知错误"}",
            )
            closeOutput()
            return
        }
        receivedBytes += payload.size
        receivedChunks++
        updateProgress()

        val now = monotonicNanos()
        if (now - lastReportNanos >= 500_000_000) {
            val elapsedSec = (now - lastReportNanos) / 1_000_000_000.0
            val delta = receivedBytes - lastReportBytes
            val bps = if (elapsedSec > 0) delta / elapsedSec else 0.0
            _progress.value = _progress.value.copy(bytesPerSecond = bps)
            lastReportNanos = now
            lastReportBytes = receivedBytes
        }

        if (receivedChunks >= totalChunks) {
            scheduleAwaitComplete()
        }
        tryFinalizeIfReady()
    }

    private fun updateProgress() {
        val targetBytes = pendingComplete?.sizeBytes?.takeIf { it > 0 }
            ?: expectedBytes.takeIf { it > 0 }
            ?: receivedBytes
        val completeTag = if (pendingComplete != null) " [完成已到]" else ""
        _progress.value = _progress.value.copy(
            bytesSent = receivedBytes,
            totalBytes = targetBytes.coerceAtLeast(receivedBytes),
            phase = TransferPhase.Transferring,
            message = "接收中: $receivedBytes/$targetBytes ($receivedChunks/$totalChunks 片)$completeTag",
        )
    }

    private suspend fun tryFinalizeIfReady() {
        val complete = pendingComplete ?: return
        val targetBytes = complete.sizeBytes.takeIf { it > 0 }
            ?: expectedBytes.takeIf { it > 0 }
            ?: receivedBytes
        if (receivedBytes < targetBytes) return
        pendingTimeoutJob?.cancel()
        awaitCompleteJob?.cancel()
        _progress.value = _progress.value.copy(
            phase = TransferPhase.Verifying,
            totalBytes = targetBytes,
            message = "正在校验...",
        )
        finalizeVerify(complete, targetBytes)
    }

    private fun scheduleAwaitComplete() {
        if (awaitCompleteJob?.isActive == true) return
        awaitCompleteJob = scope.launch {
            delay(60_000)
            if (pendingComplete != null || manifest == null) return@launch
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Failed,
                message = "未收到完成确认，请重试",
            )
            closeOutput()
        }
    }

    private suspend fun startReceive(parsed: TransferManifest) {
        pendingTimeoutJob?.cancel()
        awaitCompleteJob?.cancel()
        pendingComplete = null
        runCatching { bufferedOutput?.flush() }
        runCatching { bufferedOutput?.close() }
        runCatching { hashingSink?.close() }
        bufferedOutput = null
        hashingSink = null
        manifest = parsed
        expectedBytes = parsed.sizeBytes
        totalChunks = parsed.totalChunks
        receivedBytes = 0L
        receivedChunks = 0
        val outputSink = onManifest(parsed)
        val hashSink = HashingSink.sha256(outputSink)
        hashingSink = hashSink
        bufferedOutput = hashSink.buffer()
        _progress.value = TransferProgress(
            phase = TransferPhase.Transferring,
            fileName = parsed.fileName,
            totalBytes = parsed.sizeBytes,
            message = "开始接收...",
        )
    }

    private suspend fun verifyAndComplete(complete: TransferComplete) {
        val currentManifest = manifest ?: return
        if (complete.transferId != currentManifest.transferId) return

        val targetBytes = when {
            complete.sizeBytes > 0 -> complete.sizeBytes
            currentManifest.sizeBytes > 0 -> currentManifest.sizeBytes
            else -> receivedBytes
        }
        expectedBytes = targetBytes
        pendingComplete = complete
        awaitCompleteJob?.cancel()

        if (receivedBytes < targetBytes) {
            schedulePendingTimeout(targetBytes)
            updateProgress()
            return
        }
        _progress.value = _progress.value.copy(
            phase = TransferPhase.Verifying,
            totalBytes = targetBytes,
            message = "正在校验...",
        )
        finalizeVerify(complete, targetBytes)
    }

    private fun schedulePendingTimeout(targetBytes: Long) {
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = scope.launch {
            delay(60_000)
            val complete = pendingComplete ?: return@launch
            if (manifest == null) return@launch
            if (receivedBytes >= targetBytes) {
                finalizeVerify(complete, targetBytes)
                return@launch
            }
            pendingComplete = null
            _progress.value = _progress.value.copy(
                phase = TransferPhase.Failed,
                bytesSent = receivedBytes,
                totalBytes = targetBytes,
                message = "接收超时: 已收 $receivedBytes/$targetBytes 字节",
            )
            closeOutput()
        }
    }

    private suspend fun finalizeVerify(complete: TransferComplete, targetBytes: Long) {
        pendingTimeoutJob?.cancel()
        awaitCompleteJob?.cancel()
        pendingComplete = null
        val currentManifest = manifest
        val hashSink = hashingSink
        runCatching { bufferedOutput?.flush() }
        runCatching { bufferedOutput?.close() }
        bufferedOutput = null
        runCatching { hashSink?.close() }
        val digest = hashSink?.hash?.hex().orEmpty()
        val ok = digest.equals(complete.sha256, ignoreCase = true)
        val savedMessage = if (ok && currentManifest != null) {
            publishReceivedFile(currentManifest)
        } else {
            null
        }
        val savedOk = savedMessage != null && !savedMessage.startsWith("保存失败")
        _progress.value = _progress.value.copy(
            phase = if (ok && savedOk) TransferPhase.Completed else TransferPhase.Failed,
            bytesSent = receivedBytes,
            totalBytes = targetBytes,
            message = when {
                ok && savedOk -> "校验通过，$savedMessage"
                ok && savedMessage != null -> savedMessage
                ok -> "校验通过，但保存失败"
                receivedBytes != targetBytes ->
                    "校验失败: 已收 $receivedBytes/$targetBytes 字节"
                else -> "校验失败: 期望 ${complete.sha256}，实际 $digest"
            },
        )
        manifest = null
        hashingSink = null
        receivedBytes = 0L
        expectedBytes = 0L
        receivedChunks = 0
        totalChunks = 0
    }

    private fun closeOutput() {
        pendingTimeoutJob?.cancel()
        awaitCompleteJob?.cancel()
        while (binaryQueue.tryReceive().isSuccess) {
            // drain stale frames
        }
        runCatching { bufferedOutput?.flush() }
        runCatching { bufferedOutput?.close() }
        runCatching { hashingSink?.close() }
        bufferedOutput = null
        hashingSink = null
        manifest = null
        receivedBytes = 0L
        expectedBytes = 0L
        receivedChunks = 0
        totalChunks = 0
        pendingComplete = null
    }
}

fun estimateChunkCount(sizeBytes: Long, chunkSize: Int): Int =
    if (sizeBytes <= 0) 0 else ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
