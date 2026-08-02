package com.oneturn.transfer.signaling

import com.oneturn.transfer.platform.createHttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal actual suspend fun SignalingClient.launchWebSocketSession(
    wsUrl: String,
    role: SignalingRole,
    deviceId: String?,
    scope: CoroutineScope,
) {
    val client = createHttpClient()
    var attempt = 0
    while (scope.isActive) {
        try {
            setConnectionState(SignalingConnectionState.Connecting)
            client.webSocket(urlString = wsUrl) {
                setConnectionState(SignalingConnectionState.Connected)
                send(Frame.Text(encodeJoinMessage(role, deviceId)))
                val senderJob = launch {
                    drainOutbound { payload ->
                        send(Frame.Text(payload))
                        true
                    }
                }
                try {
                    for (frame in incoming) {
                        val text = when (frame) {
                            is Frame.Text -> frame.readText()
                            else -> continue
                        }
                        decodeIncomingMessage(text)?.let { emitIncoming(it) }
                    }
                } finally {
                    senderJob.cancel()
                }
            }
            attempt = 0
        } catch (_: Throwable) {
            if (!scope.isActive) break
            setConnectionState(SignalingConnectionState.Reconnecting)
            delay(1_000L * (attempt.coerceAtMost(5) + 1))
            attempt++
            continue
        }
        if (!scope.isActive) break
        setConnectionState(SignalingConnectionState.Reconnecting)
        delay(1_000L)
    }
    setConnectionState(SignalingConnectionState.Disconnected)
}
