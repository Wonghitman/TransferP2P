package com.oneturn.transfer.platform

import com.oneturn.transfer.transfer.TransferManifest
import okio.Buffer
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

actual class PlatformFilePicker {
    actual suspend fun pickFile(): PickedFile? {
        val picked = pickBrowserFile() ?: return null
        val bytes = picked.bytes
        return PickedFile(
            name = picked.name,
            mimeType = picked.mimeType,
            sizeBytes = picked.sizeBytes,
            openSource = {
                Buffer().also { it.write(bytes) }
            },
        )
    }
}

private val receiveBuffers = mutableMapOf<String, Buffer>()

actual fun createReceiveSink(manifest: TransferManifest): Sink {
    val buffer = Buffer()
    receiveBuffers[manifest.transferId] = buffer
    return buffer
}

actual fun spoolAndHash(source: Source): SpooledSource {
    val bytes = source.buffer().use { it.readByteArray() }
    val hashSink = okio.HashingSink.sha256(okio.blackholeSink())
    hashSink.buffer().use { it.write(bytes) }
    hashSink.close()
    val digest = hashSink.hash.hex()
    return object : SpooledSource {
        override val sizeBytes: Long = bytes.size.toLong()
        override val sha256: String = digest
        override fun readChunk(index: Int, chunkSize: Int): ByteArray {
            val offset = index.toLong() * chunkSize
            val remaining = bytes.size.toLong() - offset
            if (remaining <= 0) return ByteArray(0)
            val len = minOf(remaining, chunkSize.toLong()).toInt()
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + len)
        }

        override fun close() = Unit
    }
}

actual fun publishReceivedFile(manifest: TransferManifest): String {
    val buffer = receiveBuffers.remove(manifest.transferId)
        ?: return "保存失败: 找不到接收缓存"
    val bytes = buffer.readByteArray()
    if (bytes.isEmpty()) {
        return "保存失败: 缓存文件为空"
    }
    val safeName = manifest.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val mime = manifest.mimeType.ifBlank { "application/octet-stream" }
    return runCatching {
        downloadBrowserFile(safeName, mime, bytes)
        "已下载: $safeName"
    }.getOrElse {
        "保存失败: ${it.message ?: "下载异常"}"
    }
}

actual class QrScanner {
    actual suspend fun scanJoinUrl(): String? = null
}
