package com.oneturn.transfer.presence

import com.oneturn.transfer.platform.createHttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

internal actual suspend fun launchDevicePresenceSocket(
    wsUrl: String,
    deviceId: String,
    scope: CoroutineScope,
    onMessage: suspend (String) -> Unit,
) {
    val client = createHttpClient()
    client.webSocket(urlString = wsUrl) {
        send(Frame.Text("""{"type":"presence","deviceId":"$deviceId"}"""))
        for (frame in incoming) {
            if (!scope.isActive) break
            val text = when (frame) {
                is Frame.Text -> frame.readText()
                else -> continue
            }
            if (text.contains("transfer_invite")) {
                onMessage(text)
            }
        }
    }
}
