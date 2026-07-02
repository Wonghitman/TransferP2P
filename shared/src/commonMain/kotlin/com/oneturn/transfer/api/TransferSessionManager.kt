package com.oneturn.transfer.api

import com.oneturn.transfer.identity.DeviceIdentityRepository
import com.oneturn.transfer.identity.DeviceRegistry
import com.oneturn.transfer.pairing.PairingService
import com.oneturn.transfer.pairing.RoomInfo
import com.oneturn.transfer.platform.createReceiveSink
import com.oneturn.transfer.signaling.SignalingClient
import com.oneturn.transfer.signaling.SignalingRole
import com.oneturn.transfer.transfer.FileTransferReceiver
import com.oneturn.transfer.transfer.FileTransferSender
import com.oneturn.transfer.transfer.TransferManifest
import com.oneturn.transfer.transfer.TransferProgress
import com.oneturn.transfer.transfer.computeSha256
import com.oneturn.transfer.transfer.estimateChunkCount
import com.oneturn.transfer.webrtc.ConnectionMode
import com.oneturn.transfer.webrtc.WebRtcCoordinator
import com.oneturn.transfer.platform.createAppSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.Source
import kotlin.random.Random
import kotlin.time.Clock

class TransferSessionManager(
    private val baseUrl: String,
    private val scope: CoroutineScope,
    settings: Settings = createAppSettings(),
) {
    val signaling = SignalingClient(baseUrl, scope = scope)
    val pairing = PairingService(signaling, scope)
    val identityRepository = DeviceIdentityRepository(settings)
    val deviceRegistry = DeviceRegistry(signaling, identityRepository)

    private var coordinator: WebRtcCoordinator? = null
    private var transferJobs = mutableListOf<Job>()
    private var receiver: FileTransferReceiver? = null
    private var sender: FileTransferSender? = null

    private val _room = MutableStateFlow<RoomInfo?>(null)
    val room: StateFlow<RoomInfo?> = _room.asStateFlow()

    private val _connectionMode = MutableStateFlow(ConnectionMode.Connecting)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    private val _senderProgress = MutableStateFlow(TransferProgress())
    val senderProgress: StateFlow<TransferProgress> = _senderProgress.asStateFlow()

    private val _receiverProgress = MutableStateFlow(TransferProgress())
    val receiverProgress: StateFlow<TransferProgress> = _receiverProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    suspend fun createSendRoom(): RoomInfo {
        val roomInfo = pairing.createSenderRoom()
        _room.value = roomInfo
        pairing.startPakeHandshake(roomInfo.code, roomInfo.code)
        establishWebRtc(SignalingRole.Sender)
        _statusMessage.value = "房间已创建，等待接收方加入"
        return roomInfo
    }

    suspend fun joinReceiveRoom(code: String): RoomInfo {
        val roomInfo = pairing.joinReceiverRoom(code)
        _room.value = roomInfo
        pairing.startPakeHandshake(roomInfo.code, roomInfo.code)
        establishWebRtc(SignalingRole.Receiver)
        _statusMessage.value = "已加入房间，正在建立连接"
        return roomInfo
    }

    suspend fun sendFile(
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        openSource: () -> Source,
    ) {
        val session = coordinator?.activeSession ?: error("WebRTC 未连接")
        val sha256 = openSource().use { computeSha256(it) }
        val manifest = TransferManifest(
            transferId = generateTransferId(),
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            chunkSize = FileTransferSender.DEFAULT_CHUNK_SIZE,
            totalChunks = estimateChunkCount(sizeBytes, FileTransferSender.DEFAULT_CHUNK_SIZE),
            sha256 = sha256,
        )
        val fileSender = FileTransferSender(session)
        sender = fileSender
        transferJobs += scope.launch {
            fileSender.progress.collect { _senderProgress.value = it }
        }
        openSource().use { source ->
            fileSender.send(manifest, source)
        }
    }

    private fun bindReceiver() {
        val session = coordinator?.activeSession ?: return
        val fileReceiver = FileTransferReceiver(session) { manifest ->
            createReceiveSink(manifest)
        }
        receiver = fileReceiver
        transferJobs += scope.launch {
            session.incomingText.collect { fileReceiver.handleText(it) }
        }
        transferJobs += scope.launch {
            session.incomingBinary.collect { fileReceiver.handleBinary(it) }
        }
        transferJobs += scope.launch {
            fileReceiver.progress.collect { _receiverProgress.value = it }
        }
    }

    fun disconnect() {
        transferJobs.forEach { it.cancel() }
        transferJobs.clear()
        coordinator?.stop()
        coordinator = null
        receiver = null
        sender = null
        signaling.disconnect()
        _room.value = null
        _senderProgress.value = TransferProgress()
        _receiverProgress.value = TransferProgress()
        _connectionMode.value = ConnectionMode.Closed
        _statusMessage.value = "已断开"
    }

    private suspend fun establishWebRtc(role: SignalingRole) {
        val turn = signaling.fetchTurnCredentials()
        val ice = WebRtcCoordinator.defaultIceServers(turn.iceServers)
        val webRtcCoordinator = WebRtcCoordinator(scope, signaling, role, ice)
        coordinator = webRtcCoordinator
        transferJobs += scope.launch {
            webRtcCoordinator.activeSession?.connectionMode?.collect {
                _connectionMode.value = it
            }
        }
        webRtcCoordinator.start()
        if (role == SignalingRole.Receiver) {
            bindReceiver()
        }
        _statusMessage.value = "P2P 连接已建立"
    }

    private fun generateTransferId(): String =
        "xfer-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(1_000_000)}"
}
