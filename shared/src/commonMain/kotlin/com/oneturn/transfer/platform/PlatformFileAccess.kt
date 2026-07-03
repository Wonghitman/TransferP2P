package com.oneturn.transfer.platform

import com.oneturn.transfer.transfer.TransferManifest
import okio.Sink

expect fun createReceiveSink(manifest: TransferManifest): Sink

/** Copy a finished receive into user-visible storage (gallery / downloads). */
expect fun publishReceivedFile(manifest: TransferManifest): String

expect class QrScanner {
    suspend fun scanJoinUrl(): String?
}
