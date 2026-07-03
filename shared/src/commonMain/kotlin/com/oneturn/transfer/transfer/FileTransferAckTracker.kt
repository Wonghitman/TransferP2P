package com.oneturn.transfer.transfer

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

class FileTransferAckTracker {
    private val ackChannel = Channel<Int>(Channel.UNLIMITED)
    private val acknowledged = mutableSetOf<Int>()

    fun reset() {
        acknowledged.clear()
        while (ackChannel.tryReceive().isSuccess) {
            // drain stale acks
        }
    }

    /** [chunkIndex] is cumulative: chunks 0..chunkIndex are all received. */
    fun onAck(chunkIndex: Int) {
        markThrough(chunkIndex)
        ackChannel.trySend(chunkIndex)
    }

    suspend fun awaitWindowSpace(nextChunkIndex: Int, windowSize: Int, timeoutMs: Long = 90_000L) {
        withTimeout(timeoutMs) {
            while (unackedSentCount(nextChunkIndex) >= windowSize) {
                consumeAck()
            }
        }
    }

    suspend fun awaitAllSent(lastChunkIndex: Int, timeoutMs: Long = 90_000L) {
        if (lastChunkIndex < 0) return
        withTimeout(timeoutMs) {
            while (!hasAllChunks(lastChunkIndex)) {
                consumeAck()
            }
        }
    }

    private fun markThrough(chunkIndex: Int) {
        for (index in 0..chunkIndex) {
            acknowledged.add(index)
        }
    }

    private fun unackedSentCount(nextChunkIndex: Int): Int {
        var unacked = 0
        for (index in 0 until nextChunkIndex) {
            if (index !in acknowledged) unacked++
        }
        return unacked
    }

    private fun hasAllChunks(lastChunkIndex: Int): Boolean {
        for (index in 0..lastChunkIndex) {
            if (index !in acknowledged) return false
        }
        return true
    }

    private suspend fun consumeAck() {
        val ack = ackChannel.receive()
        markThrough(ack)
    }
}
