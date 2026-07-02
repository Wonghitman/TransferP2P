package com.oneturn.transfer.crypto

import okio.HashingSink
import okio.blackholeSink
import okio.buffer

fun sha256Hex(data: ByteArray): String {
    val sink = HashingSink.sha256(blackholeSink())
    sink.buffer().use {
        it.write(data)
        it.flush()
    }
    sink.close()
    return sink.hash.hex()
}

fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())
