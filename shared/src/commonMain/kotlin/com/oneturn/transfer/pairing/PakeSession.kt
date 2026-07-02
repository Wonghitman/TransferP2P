package com.oneturn.transfer.pairing

import com.oneturn.transfer.crypto.sha256Hex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class PakeSession(
    private val roomCode: String,
    private val salt: String,
) {
    fun deriveSessionKey(peerId: String): ByteArray =
        sha256Hex("$roomCode|$salt|$peerId").encodeToByteArray()

    fun createProof(peerId: String): String =
        Base64.encode(deriveSessionKey(peerId))

    fun verifyProof(peerId: String, proof: String): Boolean =
        runCatching { createProof(peerId) == proof }.getOrDefault(false)
}
