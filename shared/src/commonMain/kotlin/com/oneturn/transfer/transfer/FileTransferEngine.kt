package com.oneturn.transfer.transfer

import com.oneturn.transfer.platform.publishReceivedFile
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
import okio.blackholeSink
import okio.buffer

/**
 * Pull-based block transfer (similar to BitTorrent):
 * receiver requests each chunk; sender only responds to requests.
 * This prevents the sender from outpacing the receiver on WebRTC DataChannel.
 */
class FileTransferSender(
    private val session: WebRtcSession,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    private val requestQueue = Channel<Int>(Channel.UNLIMITED)
    private var activeTransferId: String? = null

    fun onBlockRequest(request: BlockRequest) {
        if (request.transferId != activeTransferId) return
        requestQueue.trySend(request.chunkIndex)
    }

    suspend fun send(
        fileName: String,
        mimeType: String,
        transferId: String,
        source: Source,
    ) {
        activeTransferId = transferId
        while (requestQueue.tryReceive().isSuccess) {
            // drain stale requests
        }

        val chunks = readAllChunks(source, chunkSize)
        val sizeBytes = chunks.sumOf { it.size.toLong() }
        val totalChunks = chunks.size
        val manifest = TransferManifest(
            transferId = transferId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            sha256 = "",
        )

        _progress.value = TransferProgress(
            phase = TransferPhase.Handshaking,
            fileName = fileName,
            totalBytes = sizeBytes,
            message = "准备发送...",
        )
        session.sendTextReliable(json.encodeToString(TransferManifest.serializer(), manifest), drainAfterSend = true)

        val hashSink = HashingSink.sha256(blackholeSink())
        hashSink.buffer().use { hashBuffer ->
            chunks.forEach { hashBuffer.write(it) }
        }
        hashSink.close()
        val digest = hashSink.hash.hex()

        _progress.value = _progress.value.copy(
            phase = TransferPhase.Transferring,
            totalBytes = sizeBytes,
            message = "等待接收方请求分片...",
        )

        var sentBytes = 0L
        var lastReportNanos = System.nanoTime()
        var lastReportBytes = 0L
        val sentIndices = mutableSetOf<Int>()

        while (sentIndices.size < totalChunks && currentCoroutineContext().isActive) {
            val requestedIndex = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                requestQueue.receive()
            } ?: throw IllegalStateException("接收方未请求分片，传输超时")

            if (requestedIndex < 0 || requestedIndex >= totalChunks) continue

            val chunk = chunks[requestedIndex]
            session.sendBinaryReliable(encodeFrame(requestedIndex, chunk), drainAfterSend = true)
            if (sentIndices.add(requestedIndex)) {
                sentBytes += chunk.size
            }

            val now = System.nanoTime()
            var bytesPerSecond = _progress.value.bytesPerSecond
            if (now - lastReportNanos >= 500_000_000) {
                val elapsedSec = (now - lastReportNanos) / 1_000_000_000.0
                val delta = sentBytes - lastReportBytes
                bytesPerSecond = if (elapsedSec > 0) delta / elapsedSec else 0.0
                lastReportNanos = now
                lastReportBytes = sentBytes
            }
            _progress.value = _progress.value.copy(
                bytesSent = sentBytes,
                totalBytes = sizeBytes,
                bytesPerSecond = bytesPerSecond,
                message = "已发送 ${sentIndices.size}/$totalChunks 片",
            )
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
                        sha256 = digest,
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
        const val REQUEST_TIMEOUT_MS = 60_000L
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

        if (frameType != receivedChunks) {
            requestChunk(receivedChunks)
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

        val now = System.nanoTime()
        if (now - lastReportNanos >= 500_000_000) {
            val elapsedSec = (now - lastReportNanos) / 1_000_000_000.0
            val delta = receivedBytes - lastReportBytes
            val bps = if (elapsedSec > 0) delta / elapsedSec else 0.0
            _progress.value = _progress.value.copy(bytesPerSecond = bps)
            lastReportNanos = now
            lastReportBytes = receivedBytes
        }

        if (receivedChunks < totalChunks) {
            requestChunk(receivedChunks)
        } else {
            scheduleAwaitComplete()
        }
        tryFinalizeIfReady()
    }

    private suspend fun requestChunk(index: Int) {
        val current = manifest ?: return
        session.sendTextReliable(
            json.encodeToString(
                BlockRequest.serializer(),
                BlockRequest(
                    transferId = current.transferId,
                    chunkIndex = index,
                ),
            ),
            drainAfterSend = true,
        )
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
        requestChunk(0)
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

private fun readAllChunks(source: Source, chunkSize: Int): List<ByteArray> {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(chunkSize)
    source.buffer().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            chunks.add(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
        }
    }
    return chunks
}

fun computeSha256(source: Source): String = computeSha256AndSize(source).first

fun computeSha256AndSize(source: Source): Pair<String, Long> {
    val hashSink = HashingSink.sha256(blackholeSink())
    val size = source.buffer().use { input ->
        hashSink.buffer().use { output ->
            output.writeAll(input)
        }
    }
    hashSink.close()
    return hashSink.hash.hex() to size
}

fun measureSourceSize(source: Source): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    source.buffer().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
        }
    }
    return total
}

fun estimateChunkCount(sizeBytes: Long, chunkSize: Int): Int =
    if (sizeBytes <= 0) 0 else ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
