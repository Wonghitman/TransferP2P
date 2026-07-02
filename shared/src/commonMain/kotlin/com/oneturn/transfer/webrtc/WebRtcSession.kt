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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val DATA_CHANNEL_LABEL = "transfer"
private const val BACKPRESSURE_THRESHOLD = 64 * 1024L

class WebRtcSession(
    private val scope: CoroutineScope,
    private val role: PeerRole,
    iceServers: List<IceServerConfig>,
) {
    private val _connectionMode = MutableStateFlow(ConnectionMode.Connecting)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    private val _dataChannelState = MutableStateFlow(DataChannelState.Connecting)
    val dataChannelState: StateFlow<DataChannelState> = _dataChannelState.asStateFlow()

    private val _incomingBinary = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incomingBinary: SharedFlow<ByteArray> = _incomingBinary.asSharedFlow()

    private val _incomingText = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val incomingText: SharedFlow<String> = _incomingText.asSharedFlow()

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
                _connectionMode.value = when (state) {
                    com.shepeliev.webrtckmp.IceConnectionState.Connected,
                    com.shepeliev.webrtckmp.IceConnectionState.Completed,
                    -> ConnectionMode.Direct
                    com.shepeliev.webrtckmp.IceConnectionState.Failed ->
                        ConnectionMode.Failed
                    com.shepeliev.webrtckmp.IceConnectionState.Closed ->
                        ConnectionMode.Closed
                    else -> ConnectionMode.Connecting
                }
            }
        }
        scope.launch {
            peerConnection.onIceCandidate.collect { candidate ->
                _localIceCandidates.emit(candidate)
            }
        }
        scope.launch {
            peerConnection.onDataChannel.collect { channel ->
                bindDataChannel(channel)
            }
        }
        if (role == PeerRole.Initiator) {
            val channel = peerConnection.createDataChannel(
                label = DATA_CHANNEL_LABEL,
                ordered = true,
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
        val answer = peerConnection.createAnswer(OfferAnswerOptions())
        peerConnection.setLocalDescription(answer)
        return answer
    }

    suspend fun setRemoteAnswer(answer: SessionDescription) {
        peerConnection.setRemoteDescription(answer)
    }

    suspend fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection.addIceCandidate(candidate)
    }

    suspend fun waitForDataChannelOpen() {
        dataChannelState.filter { it == DataChannelState.Open }.first()
    }

    fun sendBinary(bytes: ByteArray) {
        dataChannel?.send(bytes)
    }

    fun sendText(text: String) {
        dataChannel?.send(text.encodeToByteArray())
    }

    val bufferedAmount: Long
        get() = dataChannel?.bufferedAmount ?: 0L

    val bufferedAmountLowThreshold: Long
        get() = BACKPRESSURE_THRESHOLD

    fun close() {
        dataChannel?.close()
        peerConnection.close()
        _connectionMode.value = ConnectionMode.Closed
    }

    private fun bindDataChannel(channel: DataChannel) {
        dataChannel = channel
        _dataChannelState.value = channel.readyState
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
                val bytes = payload as? ByteArray ?: return@collect
                val asText = runCatching { bytes.decodeToString() }.getOrNull()
                if (asText != null && asText.startsWith("{")) {
                    _incomingText.emit(asText)
                } else {
                    _incomingBinary.emit(bytes)
                }
            }
        }
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
