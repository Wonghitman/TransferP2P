package com.oneturn.transfer.presence

import kotlinx.coroutines.CoroutineScope

internal expect suspend fun launchDevicePresenceSocket(
    wsUrl: String,
    deviceId: String,
    scope: CoroutineScope,
    onMessage: suspend (String) -> Unit,
)
