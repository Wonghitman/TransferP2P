package com.oneturn.transfer.presence

import com.oneturn.transfer.platform.createSignalingHttp
import com.oneturn.transfer.signaling.ConsumeInviteRequest
import com.oneturn.transfer.signaling.InviteNotification
import com.oneturn.transfer.signaling.PresenceUpdateNotification
import com.oneturn.transfer.signaling.TransferInviteDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DevicePresenceClient(
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val platformHttp = createSignalingHttp()

    private val _incomingInvites = MutableSharedFlow<TransferInvite>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingInvites: SharedFlow<TransferInvite> = _incomingInvites.asSharedFlow()

    private val _presenceUpdates = MutableSharedFlow<PresenceUpdateNotification>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val presenceUpdates: SharedFlow<PresenceUpdateNotification> = _presenceUpdates.asSharedFlow()

    private var presenceJob: Job? = null
    private var pollJob: Job? = null

    fun connect(deviceId: String) {
        disconnect()
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/devices/ws"
        presenceJob = scope.launch {
            while (isActive) {
                runCatching {
                    launchDevicePresenceSocket(wsUrl, deviceId, this) { text ->
                        handleIncomingText(text)
                    }
                }
                delay(3_000)
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    fetchPendingInvite(deviceId)?.let { _incomingInvites.emit(it) }
                }
                delay(5_000)
            }
        }
    }

    fun disconnect() {
        presenceJob?.cancel()
        pollJob?.cancel()
        presenceJob = null
        pollJob = null
    }

    suspend fun consumeInvite(deviceId: String) {
        runCatching {
            platformHttp.post(
                "$baseUrl/devices/invites/consume",
                json.encodeToString(ConsumeInviteRequest.serializer(), ConsumeInviteRequest(deviceId)),
            )
        }
    }

    private suspend fun fetchPendingInvite(deviceId: String): TransferInvite? {
        val text = platformHttp.get("$baseUrl/devices/$deviceId/invites")
        if (text.isBlank() || text == "null") return null
        return json.decodeFromString(TransferInviteDto.serializer(), text).toTransferInvite()
    }

    private suspend fun handleIncomingText(text: String) {
        val presence = runCatching {
            json.decodeFromString(PresenceUpdateNotification.serializer(), text)
        }.getOrNull()
        if (presence?.type == "presence_update") {
            _presenceUpdates.emit(presence)
            return
        }

        val notification = runCatching {
            json.decodeFromString(InviteNotification.serializer(), text)
        }.getOrNull() ?: return
        if (notification.type != "transfer_invite") return
        if (notification.inviteId.isBlank()) return
        _incomingInvites.emit(
            TransferInvite(
                inviteId = notification.inviteId,
                code = notification.code,
                wsUrl = notification.wsUrl,
                fromDeviceId = notification.fromDeviceId,
                fromDisplayName = notification.fromDisplayName,
                expiresAt = notification.expiresAt,
            ),
        )
    }
}
