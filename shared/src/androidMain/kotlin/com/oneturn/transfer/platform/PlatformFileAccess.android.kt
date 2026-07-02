package com.oneturn.transfer.platform

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.oneturn.transfer.transfer.TransferManifest
import kotlinx.coroutines.CompletableDeferred
import okio.sink
import okio.source
import java.io.File

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

fun initPlatformContext(context: android.content.Context) {
    appContext = context.applicationContext
}

actual fun createReceiveSink(manifest: TransferManifest): okio.Sink {
    val dir = File(appContext.cacheDir, "received")
    dir.mkdirs()
    val safeName = manifest.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val file = File(dir, safeName)
    return file.sink()
}

actual class QrScanner {
    actual suspend fun scanJoinUrl(): String? = null
}
