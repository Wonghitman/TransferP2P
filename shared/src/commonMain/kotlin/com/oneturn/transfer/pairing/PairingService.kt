package com.oneturn.transfer.pairing

import com.oneturn.transfer.signaling.SignalingClient
import com.oneturn.transfer.signaling.SignalingMessage
import com.oneturn.transfer.signaling.SignalingRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class PairingService(
    private val signaling: SignalingClient,
    private val scope: CoroutineScope,
) {
    suspend fun createSenderRoom(): RoomInfo {
        val response = signaling.createRoom()
        signaling.connect(response.wsUrl, SignalingRole.Sender)
        return RoomInfo(
            code = response.code,
            joinUrl = response.joinUrl,
            wsUrl = response.wsUrl,
            expiresAt = response.expiresAt,
        )
    }

    suspend fun joinReceiverRoom(code: String): RoomInfo {
        val response = signaling.joinRoom(code)
        signaling.connect(response.wsUrl, SignalingRole.Receiver)
        return RoomInfo(
            code = response.code,
            joinUrl = "",
            wsUrl = response.wsUrl,
            expiresAt = response.expiresAt,
        )
    }

    fun startPakeHandshake(roomCode: String, salt: String) {
        val pake = PakeSession(roomCode, salt)
        scope.launch {
            val proof = pake.createProof(signaling.peerId)
            signaling.send(SignalingMessage.Pake(signaling.peerId, proof))
        }
        scope.launch {
            signaling.incoming.filterIsInstance<SignalingMessage.Pake>().collect { message ->
                if (message.from == signaling.peerId) return@collect
                val valid = pake.verifyProof(message.from, message.payload)
                if (!valid) {
                    signaling.send(
                        SignalingMessage.Error("PAKE verification failed for ${message.from}"),
                    )
                }
            }
        }
    }
}
