package com.oneturn.transfer.webrtc

import com.shepeliev.webrtckmp.DataChannel
import com.shepeliev.webrtckmp.DataChannelState
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onDataChannel
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onIceConnectionStateChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val DATA_CHANNEL_LABEL = "transfer"
private const val BACKPRESSURE_THRESHOLD = 1024 * 1024L
private const val DATA_CHANNEL_OPEN_TIMEOUT_MS = 60_000L
private const val MSG_TEXT: Byte = 1
private const val MSG_BINARY: Byte = 2

class WebRtcSession(
    private val scope: CoroutineScope,
    private val role: PeerRole,
    iceServers: List<IceServerConfig>,
) {
    private val _connectionMode = MutableStateFlow(ConnectionMode.Connecting)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    private val _addressFamily = MutableStateFlow(PeerAddressFamily.Unknown)
    val addressFamily: StateFlow<PeerAddressFamily> = _addressFamily.asStateFlow()

    private val _dataChannelState = MutableStateFlow(DataChannelState.Connecting)
    val dataChannelState: StateFlow<DataChannelState> = _dataChannelState.asStateFlow()

    private val incomingChannel = Channel<DataChannelMessage>(Channel.UNLIMITED)
    val incomingMessages = incomingChannel.receiveAsFlow()

    private val _localIceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 32)
    val localIceCandidates: SharedFlow<IceCandidate> = _localIceCandidates.asSharedFlow()

    private val peerConnection: PeerConnection = PeerConnection(
        RtcConfiguration(
            iceServers = buildIceServers(iceServers),
            bundlePolicy = com.shepeliev.webrtckmp.BundlePolicy.MaxBundle,
            rtcpMuxPolicy = com.shepeliev.webrtckmp.RtcpMuxPolicy.Require,
            iceTransportPolicy = com.shepeliev.webrtckmp.IceTransportPolicy.All,
        ),
    )

    private var dataChannel: DataChannel? = null
    private var remoteDescriptionApplied = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    init {
        scope.launch {
            peerConnection.onConnectionStateChange.collect { state ->
                when (state) {
                    PeerConnectionState.Failed, PeerConnectionState.Disconnected ->
                        _connectionMode.value = ConnectionMode.Failed
                    PeerConnectionState.Closed ->
                        _connectionMode.value = ConnectionMode.Closed
                    else -> Unit
                }
            }
        }
        scope.launch {
            peerConnection.onIceConnectionStateChange.collect { state ->
                when (state) {
                    com.shepeliev.webrtckmp.IceConnectionState.Connected,
                    com.shepeliev.webrtckmp.IceConnectionState.Completed,
                    -> {
                        _connectionMode.value = ConnectionMode.Direct
                        scope.launch { refreshAddressFamily() }
                    }
                    com.shepeliev.webrtckmp.IceConnectionState.Failed ->
                        _connectionMode.value = ConnectionMode.Failed
                    com.shepeliev.webrtckmp.IceConnectionState.Closed ->
                        _connectionMode.value = ConnectionMode.Closed
                    else -> _connectionMode.value = ConnectionMode.Connecting
                }
            }
        }
        scope.launch {
            peerConnection.onIceCandidate.collect { candidate ->
                _localIceCandidates.emit(candidate)
            }
        }
        if (role == PeerRole.Responder) {
            scope.launch {
                peerConnection.onDataChannel.collect { channel ->
                    bindDataChannel(channel)
                }
            }
        } else {
            val channel = peerConnection.createDataChannel(
                label = DATA_CHANNEL_LABEL,
                ordered = true,
                // reliable (default): do not set maxRetransmits / maxPacketLifeTime
            )
            if (channel != null) {
                bindDataChannel(channel)
            }
        }
    }

    suspend fun createOffer(): SessionDescription {
        val offer = peerConnection.createOffer(OfferAnswerOptions())
        peerConnection.setLocalDescription(offer)
        return offer
    }

    suspend fun createAnswer(remoteOffer: SessionDescription): SessionDescription {
        peerConnection.setRemoteDescription(remoteOffer)
        flushPendingIceCandidates()
        val answer = peerConnection.createAnswer(OfferAnswerOptions())
        peerConnection.setLocalDescription(answer)
        return answer
    }

    suspend fun setRemoteAnswer(answer: SessionDescription) {
        peerConnection.setRemoteDescription(answer)
        flushPendingIceCandidates()
    }

    suspend fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (!remoteDescriptionApplied) {
            pendingRemoteIce.add(candidate)
            return
        }
        peerConnection.addIceCandidate(candidate)
    }

    suspend fun waitForDataChannelOpen(timeoutMs: Long = DATA_CHANNEL_OPEN_TIMEOUT_MS) {
        syncDataChannelState()
        if (_dataChannelState.value == DataChannelState.Open) return

        withTimeout(timeoutMs) {
            while (_dataChannelState.value != DataChannelState.Open) {
                if (_connectionMode.value == ConnectionMode.Failed) {
                    error("P2P 打洞失败。请两台设备连接同一 WiFi")
                }
                if (_connectionMode.value == ConnectionMode.Closed) {
                    error("P2P 连接已关闭")
                }
                syncDataChannelState()
                if (_dataChannelState.value == DataChannelState.Open) return@withTimeout
                delay(100)
            }
        }
    }

    private fun syncDataChannelState() {
        val channel = dataChannel ?: return
        val state = channel.readyState
        if (state != _dataChannelState.value) {
            _dataChannelState.value = state
        }
    }

    private suspend fun flushPendingIceCandidates() {
        remoteDescriptionApplied = true
        val pending = pendingRemoteIce.toList()
        pendingRemoteIce.clear()
        pending.forEach { peerConnection.addIceCandidate(it) }
    }

    fun sendBinary(bytes: ByteArray) {
        dataChannel?.send(byteArrayOf(MSG_BINARY) + bytes)
    }

    fun sendText(text: String) {
        val body = text.encodeToByteArray()
        dataChannel?.send(byteArrayOf(MSG_TEXT) + body)
    }

    suspend fun awaitSendCapacity(additionalBytes: Long = 0L) {
        awaitBackpressure(additionalBytes)
    }

    suspend fun sendBinaryReliable(bytes: ByteArray, drainAfterSend: Boolean = false) {
        val frame = byteArrayOf(MSG_BINARY) + bytes
        withTimeout(120_000) {
            while (true) {
                val channel = dataChannel ?: error("DataChannel 未就绪")
                awaitBackpressure(frame.size.toLong())
                if (channel.send(frame)) {
                    if (drainAfterSend) {
                        awaitDrain()
                    } else {
                        awaitBackpressure(0)
                    }
                    return@withTimeout
                }
                delay(10)
            }
        }
    }

    suspend fun sendTextReliable(text: String, drainAfterSend: Boolean = false) {
        val frame = byteArrayOf(MSG_TEXT) + text.encodeToByteArray()
        withTimeout(120_000) {
            while (true) {
                val channel = dataChannel ?: error("DataChannel 未就绪")
                awaitBackpressure(frame.size.toLong())
                if (channel.send(frame)) {
                    if (drainAfterSend) {
                        awaitDrain()
                    } else {
                        awaitBackpressure(0)
                    }
                    return@withTimeout
                }
                delay(10)
            }
        }
    }

    suspend fun awaitDrain(timeoutMs: Long = 120_000L) {
        withTimeout(timeoutMs) {
            while ((dataChannel?.bufferedAmount ?: 0L) > 0L) {
                delay(10)
            }
        }
    }

    private suspend fun awaitBackpressure(additionalBytes: Long = 0L) {
        while ((dataChannel?.bufferedAmount ?: 0L) + additionalBytes > BACKPRESSURE_THRESHOLD) {
            delay(5)
        }
    }

    val bufferedAmount: Long
        get() = dataChannel?.bufferedAmount ?: 0L

    fun close() {
        dataChannel?.close()
        peerConnection.close()
        _connectionMode.value = ConnectionMode.Closed
        _addressFamily.value = PeerAddressFamily.Unknown
    }

    private suspend fun refreshAddressFamily() {
        repeat(12) {
            val family = IceStatsResolver.detectAddressFamily(peerConnection)
            if (family != PeerAddressFamily.Unknown) {
                _addressFamily.value = family
                if (family == PeerAddressFamily.Relay) {
                    _connectionMode.value = ConnectionMode.Relay
                }
                return
            }
            delay(250)
        }
    }

    suspend fun refreshAddressFamilyNow() {
        refreshAddressFamily()
    }

    private fun bindDataChannel(channel: DataChannel) {
        dataChannel = channel
        syncDataChannelState()
        scope.launch {
            channel.onOpen.collect {
                _dataChannelState.value = DataChannelState.Open
            }
        }
        scope.launch {
            channel.onClose.collect {
                _dataChannelState.value = DataChannelState.Closed
            }
        }
        scope.launch {
            channel.onMessage.collect { payload ->
                routeIncomingPayload(payload)
            }
        }
        if (channel.readyState == DataChannelState.Open) {
            _dataChannelState.value = DataChannelState.Open
        }
    }

    private suspend fun routeIncomingPayload(payload: Any?) {
        val bytes = payloadToBytes(payload) ?: return
        if (bytes.isEmpty()) return

        when (bytes[0]) {
            MSG_TEXT -> {
                val text = bytes.decodeToString(1, bytes.size, throwOnInvalidSequence = false)
                if (text.isNotBlank()) {
                    incomingChannel.send(DataChannelMessage.Text(text))
                }
            }
            MSG_BINARY -> {
                if (bytes.size > 1) {
                    incomingChannel.send(DataChannelMessage.Binary(bytes.copyOfRange(1, bytes.size)))
                }
            }
            else -> {
                val asText = runCatching { bytes.decodeToString() }.getOrNull()
                if (asText != null && asText.startsWith("{")) {
                    incomingChannel.send(DataChannelMessage.Text(asText))
                } else {
                    incomingChannel.send(DataChannelMessage.Binary(bytes))
                }
            }
        }
    }

    private fun payloadToBytes(payload: Any?): ByteArray? = when (payload) {
        is ByteArray -> payload
        is String -> payload.encodeToByteArray()
        else -> null
    }

    private fun buildIceServers(servers: List<IceServerConfig>): List<IceServer> =
        servers.map { server ->
            IceServer(
                urls = server.urls,
                username = server.username.orEmpty(),
                password = server.credential.orEmpty(),
            )
        }
}
