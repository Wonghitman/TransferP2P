package com.oneturn.transfer.signaling

import com.oneturn.transfer.platform.createSignalingHttp
import com.oneturn.transfer.platform.SignalingHttp
import com.oneturn.transfer.platform.createHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

class SignalingClient(
    private val baseUrl: String,
    private val platformHttp: SignalingHttp = createSignalingHttp(),
    private val httpClient: HttpClient = createHttpClient(),
    private val scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    val peerId: String = generatePeerId()

    private val _connectionState = MutableStateFlow(SignalingConnectionState.Disconnected)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingMessage>(
        replay = 32,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<SignalingMessage> = _incoming.asSharedFlow()

    private val outbound = Channel<String>(Channel.BUFFERED)
    private var sessionJob: Job? = null

    suspend fun checkHealth(): Boolean = withNetworkRetry {
        json.decodeFromString(
            HealthResponse.serializer(),
            platformHttp.get("$baseUrl/"),
        ).ok
    }

    suspend fun createRoom(): CreateRoomResponse = withNetworkRetry {
        json.decodeFromString(
            CreateRoomResponse.serializer(),
            platformHttp.post("$baseUrl/rooms"),
        )
    }

    suspend fun joinRoom(code: String): JoinRoomResponse = withNetworkRetry {
        json.decodeFromString(
            JoinRoomResponse.serializer(),
            platformHttp.get("$baseUrl/rooms/$code"),
        )
    }

    suspend fun fetchIceServers(): IceServersResponse = withNetworkRetry {
        json.decodeFromString(
            IceServersResponse.serializer(),
            platformHttp.post("$baseUrl/ice-servers"),
        )
    }

    suspend fun registerDevice(request: RegisterDeviceRequest): RegisterDeviceResponse =
        withNetworkRetry {
            json.decodeFromString(
                RegisterDeviceResponse.serializer(),
                platformHttp.post(
                    "$baseUrl/devices/register",
                    json.encodeToString(RegisterDeviceRequest.serializer(), request),
                ),
            )
        }

    suspend fun claimDevice(request: ClaimDeviceRequest): TrustedDeviceDto = withNetworkRetry {
        json.decodeFromString(
            TrustedDeviceDto.serializer(),
            platformHttp.post(
                "$baseUrl/devices/claim",
                json.encodeToString(ClaimDeviceRequest.serializer(), request),
            ),
        )
    }

    suspend fun listTrustedDevices(deviceId: String): List<TrustedDeviceDto> = withNetworkRetry {
        json.decodeFromString(
            ListSerializer(TrustedDeviceDto.serializer()),
            platformHttp.get("$baseUrl/devices/$deviceId/trusted"),
        )
    }

    suspend fun fetchOnlineStatus(deviceIds: List<String>): Map<String, Boolean> = withNetworkRetry {
        if (deviceIds.isEmpty()) return@withNetworkRetry emptyMap()
        json.decodeFromString(
            OnlineStatusResponse.serializer(),
            platformHttp.post(
                "$baseUrl/devices/online-status",
                json.encodeToString(OnlineStatusRequest.serializer(), OnlineStatusRequest(deviceIds)),
            ),
        ).online
    }

    suspend fun inviteTrustedDevice(request: InviteDeviceRequest): InviteDeviceResponse =
        withNetworkRetry {
            json.decodeFromString(
                InviteDeviceResponse.serializer(),
                platformHttp.post(
                    "$baseUrl/devices/invite",
                    json.encodeToString(InviteDeviceRequest.serializer(), request),
                ),
            )
        }

    fun connect(wsUrl: String, role: SignalingRole, deviceId: String? = null) {
        disconnect()
        _incoming.resetReplayCache()
        sessionJob = scope.launch {
            launchWebSocketSession(wsUrl, role, deviceId, this)
        }
    }

    suspend fun waitUntilConnected(timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) {
            connectionState.first { it == SignalingConnectionState.Connected }
        }
    }

    suspend fun send(message: SignalingMessage) {
        val payload = json.encodeToString(SignalingMessage.serializer(), message)
        outbound.send(payload)
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        while (outbound.tryReceive().isSuccess) {
            // drain pending messages
        }
        _incoming.resetReplayCache()
        _connectionState.value = SignalingConnectionState.Disconnected
    }

    internal fun setConnectionState(state: SignalingConnectionState) {
        _connectionState.value = state
    }

    internal fun encodeJoinMessage(role: SignalingRole, deviceId: String?): String =
        json.encodeToString(
            SignalingMessage.serializer(),
            SignalingMessage.Join(role, peerId, deviceId),
        )

    internal suspend fun decodeIncomingMessage(text: String): SignalingMessage? =
        runCatching {
            json.decodeFromString(SignalingMessage.serializer(), text)
        }.getOrNull()

    internal suspend fun emitIncoming(message: SignalingMessage) {
        _incoming.emit(message)
    }

    internal suspend fun drainOutbound(send: (String) -> Boolean) {
        for (message in outbound) {
            if (!send(message)) break
        }
    }

    private fun generatePeerId(): String =
        buildString {
            append("peer-")
            repeat(8) { append(Random.nextInt(16).toString(16)) }
        }

    private suspend fun <T> withNetworkRetry(
        attempts: Int = 3,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return@withContext block()
            } catch (error: Throwable) {
                lastError = error
                if (error is SignalingHttpException && error.statusCode in 400..499) {
                    throw error
                }
                if (attempt < attempts - 1) {
                    delay(1_500L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("网络请求失败")
    }
}

enum class SignalingConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
}
