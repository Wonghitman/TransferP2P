package com.oneturn.transfer.presence

import com.oneturn.transfer.platform.PreferIpv4Dns
import kotlinx.coroutines.CoroutineScope
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

internal actual suspend fun launchDevicePresenceSocket(
    wsUrl: String,
    deviceId: String,
    scope: CoroutineScope,
    onMessage: suspend (String) -> Unit,
) {
    val client = OkHttpClient.Builder()
        .dns(PreferIpv4Dns)
        .retryOnConnectionFailure(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "TransferP2P-Android")
            .build()

        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"presence","deviceId":"$deviceId"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch {
                        if (text.contains("transfer_invite")) {
                            onMessage(text)
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(t)
                    }
                }
            },
        )

        continuation.invokeOnCancellation {
            socket.close(1000, "cancelled")
        }
    }
}
