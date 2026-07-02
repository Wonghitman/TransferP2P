package com.oneturn.transfer.signaling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.random.Random

class SignalingClient(
    private val baseUrl: String,
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
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<SignalingMessage> = _incoming.asSharedFlow()

    private val outbound = Channel<String>(Channel.BUFFERED)
    private var sessionJob: Job? = null

    suspend fun createRoom(): CreateRoomResponse =
        httpClient.post("$baseUrl/rooms") {
            contentType(ContentType.Application.Json)
        }.body()

    suspend fun joinRoom(code: String): JoinRoomResponse =
        httpClient.get("$baseUrl/rooms/$code").body()

    suspend fun fetchTurnCredentials(): TurnCredentialsResponse =
        httpClient.post("$baseUrl/turn-credentials") {
            contentType(ContentType.Application.Json)
        }.body()

    suspend fun registerDevice(request: RegisterDeviceRequest): RegisterDeviceResponse =
        httpClient.post("$baseUrl/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun claimDevice(request: ClaimDeviceRequest): TrustedDeviceDto =
        httpClient.post("$baseUrl/devices/claim") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun listTrustedDevices(deviceId: String): List<TrustedDeviceDto> =
        httpClient.get("$baseUrl/devices/$deviceId/trusted").body()

    fun connect(wsUrl: String, role: SignalingRole, deviceId: String? = null) {
        disconnect()
        sessionJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    _connectionState.value = SignalingConnectionState.Connecting
                    httpClient.webSocket(wsUrl) {
                        _connectionState.value = SignalingConnectionState.Connected
                        attempt = 0

                        val joinPayload = json.encodeToString(
                            SignalingMessage.serializer(),
                            SignalingMessage.Join(role, peerId, deviceId),
                        )
                        send(Frame.Text(joinPayload))

                        val sender = launch {
                            for (message in outbound) {
                                send(Frame.Text(message))
                            }
                        }

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val message = json.decodeFromString(
                                        SignalingMessage.serializer(),
                                        frame.readText(),
                                    )
                                    _incoming.emit(message)
                                }
                            }
                        } finally {
                            sender.cancel()
                        }
                    }
                } catch (_: Throwable) {
                    if (!isActive) break
                    _connectionState.value = SignalingConnectionState.Reconnecting
                    delay(1_000L * (attempt.coerceAtMost(5) + 1))
                    attempt++
                    continue
                }
                if (!isActive) break
                _connectionState.value = SignalingConnectionState.Reconnecting
                delay(1_000L)
            }
            _connectionState.value = SignalingConnectionState.Disconnected
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
        _connectionState.value = SignalingConnectionState.Disconnected
    }

    private fun generatePeerId(): String =
        buildString {
            append("peer-")
            repeat(8) { append(Random.nextInt(16).toString(16)) }
        }

    companion object {
        fun createHttpClient(): HttpClient = HttpClient {
            install(WebSockets)
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        classDiscriminator = "type"
                    },
                )
            }
        }
    }
}

enum class SignalingConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
}
