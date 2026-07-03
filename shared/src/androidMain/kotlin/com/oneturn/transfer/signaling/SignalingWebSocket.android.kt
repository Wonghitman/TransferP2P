package com.oneturn.transfer.signaling

import com.oneturn.transfer.platform.PreferIpv4Dns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun SignalingClient.launchWebSocketSession(
    wsUrl: String,
    role: SignalingRole,
    deviceId: String?,
    scope: CoroutineScope,
) {
    val client = OkHttpClient.Builder()
        .dns(PreferIpv4Dns)
        .retryOnConnectionFailure(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    var attempt = 0
    while (scope.isActive) {
        try {
            setConnectionState(SignalingConnectionState.Connecting)
            runSocket(client, wsUrl, role, deviceId, scope)
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

private suspend fun SignalingClient.runSocket(
    client: OkHttpClient,
    wsUrl: String,
    role: SignalingRole,
    deviceId: String?,
    scope: CoroutineScope,
) = suspendCancellableCoroutine { continuation ->
    val opened = CompletableDeferred<Unit>()
    var socket: WebSocket? = null
    var senderJob: kotlinx.coroutines.Job? = null

    val request = Request.Builder()
        .url(wsUrl)
        .header("User-Agent", "TransferP2P-Android")
        .build()

    socket = client.newWebSocket(
        request,
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                setConnectionState(SignalingConnectionState.Connected)
                webSocket.send(encodeJoinMessage(role, deviceId))
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    decodeIncomingMessage(text)?.let { emitIncoming(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                senderJob?.cancel()
                if (!opened.isCompleted) {
                    opened.completeExceptionally(IllegalStateException("WebSocket closed: $reason"))
                }
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                senderJob?.cancel()
                if (!opened.isCompleted) {
                    opened.completeExceptionally(t)
                }
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }
        },
    )

    scope.launch {
        try {
            opened.await()
            senderJob = launch {
                drainOutbound { payload ->
                    socket?.send(payload) == true
                }
            }
        } catch (error: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(error)
            }
        }
    }

    continuation.invokeOnCancellation {
        senderJob?.cancel()
        socket?.close(1000, "cancelled")
    }
}
