package com.oneturn.transfer.platform

import com.oneturn.transfer.transfer.TransferManifest
import okio.Sink
import okio.Source

expect fun createReceiveSink(manifest: TransferManifest): Sink

/** Copy a finished receive into user-visible storage (gallery / downloads). */
expect fun publishReceivedFile(manifest: TransferManifest): String

expect class QrScanner {
    suspend fun scanJoinUrl(): String?
}

/**
 * Spools a [Source] to a random-access chunk reader while computing its SHA-256,
 * so the sender never has to hold the whole file in memory.
 */
interface SpooledSource : AutoCloseable {
    val sizeBytes: Long
    val sha256: String
    fun readChunk(index: Int, chunkSize: Int): ByteArray
}

expect fun spoolAndHash(source: Source): SpooledSource
