package com.oneturn.transfer.platform

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.oneturn.transfer.transfer.TransferManifest
import kotlinx.coroutines.CompletableDeferred
import okio.buffer
import okio.sink
import okio.source
import java.io.File

private const val PUBLIC_SUBDIR = "TransferP2P"

actual class PlatformFilePicker(
    private val activity: ComponentActivity,
) {
    private var pendingResult: CompletableDeferred<PickedFile?>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val deferred = pendingResult
        pendingResult = null
        if (uri == null) {
            deferred?.complete(null)
            return@registerForActivityResult
        }
        deferred?.complete(readUri(uri))
    }

    actual suspend fun pickFile(): PickedFile? {
        val deferred = CompletableDeferred<PickedFile?>()
        pendingResult = deferred
        launcher.launch(arrayOf("*/*"))
        return deferred.await()
    }

    private fun readUri(uri: Uri): PickedFile? {
        val resolver = activity.contentResolver
        var name = "file"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return PickedFile(
            name = name,
            mimeType = mime,
            sizeBytes = size,
            openSource = {
                resolver.openInputStream(uri)?.source()
                    ?: error("Cannot open $uri")
            },
        )
    }
}

private lateinit var appContext: android.content.Context
private val receiveFiles = mutableMapOf<String, File>()

fun initPlatformContext(context: android.content.Context) {
    appContext = context.applicationContext
}

actual fun createReceiveSink(manifest: TransferManifest): okio.Sink {
    val dir = File(appContext.cacheDir, "received")
    dir.mkdirs()
    val safeName = manifest.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val file = File(dir, "${manifest.transferId}-$safeName")
    receiveFiles[manifest.transferId] = file
    return file.sink()
}

actual fun spoolAndHash(source: okio.Source): SpooledSource =
    SpooledSourceImpl(spoolToTempFile(source))

private fun spoolToTempFile(source: okio.Source): File {
    val dir = File(appContext.cacheDir, "spool")
    dir.mkdirs()
    val file = File.createTempFile("xfer", ".bin", dir)
    try {
        val hashSink = okio.HashingSink.sha256(file.sink())
        hashSink.buffer().use { out ->
            source.buffer().use { input -> input.readAll(out) }
        }
        spoolHashes[file.absolutePath] = hashSink.hash.hex()
        return file
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

private val spoolHashes = mutableMapOf<String, String>()

private class SpooledSourceImpl(
    private val file: File,
) : SpooledSource {
    private val raf = java.io.RandomAccessFile(file, "r")

    override val sizeBytes: Long
        get() = file.length()

    override val sha256: String
        get() = spoolHashes[file.absolutePath] ?: ""

    override fun readChunk(index: Int, chunkSize: Int): ByteArray {
        val offset = index.toLong() * chunkSize
        val remaining = sizeBytes - offset
        if (remaining <= 0) return ByteArray(0)
        val len = minOf(remaining, chunkSize.toLong()).toInt()
        val bytes = ByteArray(len)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    override fun close() {
        raf.close()
        spoolHashes.remove(file.absolutePath)
        file.delete()
    }
}

actual fun publishReceivedFile(manifest: TransferManifest): String {
    val file = receiveFiles[manifest.transferId]
        ?: return "保存失败: 找不到接收缓存"
    if (!file.exists() || file.length() <= 0L) {
        return "保存失败: 缓存文件为空"
    }

    val safeName = uniqueFileName(
        manifest.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_"),
    )
    val mimeType = manifest.mimeType.ifBlank { guessMimeType(safeName) }

    val published = runCatching { publishToPublicDownloads(file, safeName, mimeType) }
        .getOrElse { "保存失败: ${it.message ?: "MediaStore 写入异常"}" }

    if (!published.startsWith("保存失败")) {
        receiveFiles.remove(manifest.transferId)
        runCatching { file.delete() }
        return published
    }

    val fallback = runCatching { publishToAppDownloads(file, safeName) }
        .getOrElse { "保存失败: ${it.message ?: "备用目录写入异常"}" }
    if (!fallback.startsWith("保存失败")) {
        receiveFiles.remove(manifest.transferId)
        runCatching { file.delete() }
    }
    return fallback
}

private fun publishToAppDownloads(file: File, safeName: String): String {
    val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Received")
    if (!dir.exists() && !dir.mkdirs()) {
        return "保存失败: 无法创建应用下载目录"
    }
    val dest = File(dir, safeName)
    file.copyTo(dest, overwrite = true)
    MediaScannerConnection.scanFile(appContext, arrayOf(dest.absolutePath), null, null)
    return "已保存到应用目录 Android/data/${appContext.packageName}/files/Download/Received/$safeName"
}

private fun uniqueFileName(original: String): String {
    val dot = original.lastIndexOf('.')
    val base = if (dot > 0) original.substring(0, dot) else original
    val ext = if (dot > 0) original.substring(dot) else ""
    val stamp = (System.currentTimeMillis() % 1_000_000).toString()
    return "${base}_$stamp$ext"
}

private fun guessMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

private fun publishToPublicDownloads(file: File, displayName: String, mimeType: String): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return publishToLegacyPublicDownloads(file, displayName)
    }

    val resolver = appContext.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBDIR/"

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val uri = resolver.insert(collection, values)
        ?: return "保存失败: 无法创建下载条目"

    val written = try {
        val output = resolver.openOutputStream(uri)
        if (output == null) {
            false
        } else {
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    if (!written) {
        resolver.delete(uri, null, null)
        return "保存失败: 写入下载目录失败"
    }

    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    resolver.update(uri, values, null, null)

    return buildString {
        append("已保存到 Download/$PUBLIC_SUBDIR/$displayName")
        append("（文件管理里可能显示为「下载」，请搜索文件名: $displayName）")
    }
}

@Suppress("DEPRECATION")
private fun publishToLegacyPublicDownloads(file: File, safeName: String): String {
    if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
        return "保存失败: 存储不可用"
    }
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        PUBLIC_SUBDIR,
    )
    if (!dir.exists() && !dir.mkdirs()) {
        return "保存失败: 无法创建 Download/$PUBLIC_SUBDIR"
    }
    val dest = File(dir, safeName)
    file.copyTo(dest, overwrite = true)
    MediaScannerConnection.scanFile(appContext, arrayOf(dest.absolutePath), null, null)
    return "已保存到 Download/$PUBLIC_SUBDIR/$safeName"
}
