package com.oneturn.transfer.platform

import okio.Source

data class PickedFile(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val openSource: () -> Source,
)

expect class PlatformFilePicker {
    suspend fun pickFile(): PickedFile?
}
