package com.oneturn.transfer.platform

import com.oneturn.transfer.transfer.TransferManifest
import okio.Sink

expect fun createReceiveSink(manifest: TransferManifest): Sink

expect class QrScanner {
    suspend fun scanJoinUrl(): String?
}
