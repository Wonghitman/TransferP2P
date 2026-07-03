package com.oneturn.transfer.signaling

import kotlinx.coroutines.CoroutineScope

internal expect suspend fun SignalingClient.launchWebSocketSession(
    wsUrl: String,
    role: SignalingRole,
    deviceId: String?,
    scope: CoroutineScope,
)
