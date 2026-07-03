package com.oneturn.transfer.api

import com.oneturn.transfer.chat.ChatLine
import com.oneturn.transfer.chat.ChatMessagePayload
import com.oneturn.transfer.chat.toChatLine
import com.oneturn.transfer.identity.DeviceIdentityRepository
import com.oneturn.transfer.identity.DeviceRegistry
import com.oneturn.transfer.identity.TrustedDevice
import com.oneturn.transfer.pairing.PairingService
import com.oneturn.transfer.pairing.RoomInfo
import com.oneturn.transfer.platform.createReceiveSink
import com.oneturn.transfer.presence.DevicePresenceClient
import com.oneturn.transfer.presence.TransferInvite
import com.oneturn.transfer.signaling.InviteDeviceRequest
import com.oneturn.transfer.signaling.SignalingClient
import com.oneturn.transfer.signaling.SignalingMessage
import com.oneturn.transfer.signaling.SignalingRole
import com.oneturn.transfer.transfer.BlockRequest
import com.oneturn.transfer.transfer.FileTransferReceiver
import com.oneturn.transfer.transfer.FileTransferSender
import com.oneturn.transfer.transfer.TransferPhase
import com.oneturn.transfer.transfer.TransferProgress
import com.oneturn.transfer.webrtc.ConnectionMode
import com.oneturn.transfer.webrtc.DataChannelMessage
import com.oneturn.transfer.webrtc.IceServerConfig
import com.oneturn.transfer.webrtc.PeerAddressFamily
import com.oneturn.transfer.webrtc.WebRtcCoordinator
import com.oneturn.transfer.platform.createAppSettings
import com.russhwolf.settings.Settings
import com.shepeliev.webrtckmp.DataChannelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
    val devicePresence = DevicePresenceClient(baseUrl, scope)

    private var coordinator: WebRtcCoordinator? = null
    private var transferJobs = mutableListOf<Job>()
    private var peerWatchJob: Job? = null
    private var presenceJob: Job? = null
    private var icePrefetchJob: Job? = null
    private var cachedIceServers: List<IceServerConfig>? = null
    private var receiver: FileTransferReceiver? = null
    private var sender: FileTransferSender? = null
    private var acceptingInvite = false
    private var onlineRefreshJob: Job? = null
    private val processedInviteIds = mutableSetOf<String>()
    private val chatJson = Json { ignoreUnknownKeys = true }
    private val seenChatIds = mutableSetOf<String>()

    private val _room = MutableStateFlow<RoomInfo?>(null)
    val room: StateFlow<RoomInfo?> = _room.asStateFlow()

    private val _connectionMode = MutableStateFlow(ConnectionMode.Connecting)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    private val _addressFamily = MutableStateFlow(PeerAddressFamily.Unknown)
    val addressFamily: StateFlow<PeerAddressFamily> = _addressFamily.asStateFlow()

    private val _p2pReady = MutableStateFlow(false)
    val p2pReady: StateFlow<Boolean> = _p2pReady.asStateFlow()

    private val _senderProgress = MutableStateFlow(TransferProgress())
    val senderProgress: StateFlow<TransferProgress> = _senderProgress.asStateFlow()

    private val _receiverProgress = MutableStateFlow(TransferProgress())
    val receiverProgress: StateFlow<TransferProgress> = _receiverProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatLine>>(emptyList())
    val chatMessages: StateFlow<List<ChatLine>> = _chatMessages.asStateFlow()

    private val _pendingInvite = MutableStateFlow<TransferInvite?>(null)
    val pendingInvite: StateFlow<TransferInvite?> = _pendingInvite.asStateFlow()

    fun startDevicePresence() {
        val identity = identityRepository.getOrCreate()
        devicePresence.connect(identity.deviceId)
        presenceJob?.cancel()
        presenceJob = scope.launch {
            devicePresence.incomingInvites.collect { invite ->
                if (_room.value != null) return@collect
                if (invite.expiresAt < Clock.System.now().toEpochMilliseconds()) return@collect
                if (_pendingInvite.value?.inviteId == invite.inviteId) return@collect
                if (processedInviteIds.contains(invite.inviteId)) return@collect
                _pendingInvite.value = invite
                _statusMessage.value = "${invite.fromDisplayName} 请求连接，请同意或拒绝"
            }
        }
        scope.launch {
            devicePresence.presenceUpdates.collect { update ->
                deviceRegistry.updateDeviceOnline(update.deviceId, update.online)
            }
        }
        onlineRefreshJob?.cancel()
        onlineRefreshJob = scope.launch {
            while (true) {
                runCatching { deviceRegistry.refreshOnlineStatus() }
                delay(12_000)
            }
        }
        scope.launch {
            runCatching { deviceRegistry.refreshFromServer() }
        }
    }

    fun acceptPendingInvite() {
        val invite = _pendingInvite.value ?: return
        if (_room.value != null || acceptingInvite) return
        acceptingInvite = true
        scope.launch {
            runCatching { acceptTrustedInvite(invite) }
                .onFailure { error ->
                    _statusMessage.value = "接听失败: ${error.message ?: "未知错误"}"
                }
            _pendingInvite.value = null
            processedInviteIds.add(invite.inviteId)
            acceptingInvite = false
        }
    }

    fun rejectPendingInvite() {
        val invite = _pendingInvite.value ?: return
        scope.launch {
            val identity = identityRepository.getOrCreate()
            devicePresence.consumeInvite(identity.deviceId)
            processedInviteIds.add(invite.inviteId)
            _pendingInvite.value = null
            _statusMessage.value = "已拒绝 ${invite.fromDisplayName} 的连接请求"
        }
    }

    suspend fun createSendRoom(): RoomInfo {
        disconnectRoom()
        prefetchIceServers()
        val roomInfo = try {
            pairing.createSenderRoom()
        } catch (e: Exception) {
            throw IllegalStateException("无法连接信令服务器 $baseUrl: ${e.message}", e)
        }
        _room.value = roomInfo
        pairing.startPakeHandshake(roomInfo.code, roomInfo.code)
        startPeerJoinWatcher(SignalingRole.Sender)
        _connectionMode.value = ConnectionMode.Connecting
        _p2pReady.value = false
        _statusMessage.value = "房间已创建: ${roomInfo.code}，等待对方加入..."
        return roomInfo
    }

    suspend fun joinReceiveRoom(code: String): RoomInfo {
        disconnectRoom()
        prefetchIceServers()
        val roomInfo = try {
            pairing.joinReceiverRoom(code)
        } catch (e: Exception) {
            throw IllegalStateException("无法加入房间: ${e.message}", e)
        }
        _room.value = roomInfo
        pairing.startPakeHandshake(roomInfo.code, roomInfo.code)
        _connectionMode.value = ConnectionMode.Connecting
        _p2pReady.value = false
        startPeerJoinWatcher(SignalingRole.Receiver)
        _statusMessage.value = "已加入房间 ${roomInfo.code}，正在建立连接..."
        return roomInfo
    }

    suspend fun inviteTrustedDevice(device: TrustedDevice) {
        disconnectRoom()
        prefetchIceServers()
        val identity = identityRepository.getOrCreate()
        val response = try {
            signaling.inviteTrustedDevice(
                InviteDeviceRequest(
                    fromDeviceId = identity.deviceId,
                    toDeviceId = device.deviceId,
                    fromDisplayName = identity.displayName,
                ),
            )
        } catch (e: Exception) {
            throw IllegalStateException("邀请失败: ${e.message}", e)
        }
        val roomInfo = RoomInfo(
            code = response.code,
            joinUrl = response.joinUrl,
            wsUrl = response.wsUrl,
            expiresAt = response.expiresAt,
        )
        connectAsSender(roomInfo)
        _statusMessage.value = "已向 ${device.displayName} 发送连接请求，等待对方同意..."
    }

    suspend fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val session = requireActiveSession()
        ensureDataChannelReady(session)
        val identity = identityRepository.getOrCreate()
        val payload = ChatMessagePayload(
            messageId = "msg-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(1_000_000)}",
            from = identity.deviceId,
            fromName = identity.displayName,
            text = trimmed,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )
        session.sendText(chatJson.encodeToString(ChatMessagePayload.serializer(), payload))
        appendChatLine(payload.toChatLine(identity.deviceId))
    }

    suspend fun sendFile(
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        openSource: () -> Source,
    ) {
        val session = requireActiveSession()
        ensureDataChannelReady(session)
        val transferId = generateTransferId()
        val fileSender = FileTransferSender(session)
        sender = fileSender
        _senderProgress.value = TransferProgress(
            phase = TransferPhase.Handshaking,
            fileName = fileName,
            message = "准备发送...",
        )
        _statusMessage.value = "正在发送: $fileName"
        transferJobs += scope.launch {
            fileSender.progress.collect { progress ->
                _senderProgress.value = progress
                when (progress.phase) {
                    TransferPhase.Handshaking,
                    TransferPhase.Transferring,
                    -> _statusMessage.value = progress.message.ifBlank {
                        "正在发送: ${progress.fileName}"
                    }
                    TransferPhase.Verifying -> _statusMessage.value = "正在校验..."
                    TransferPhase.Completed -> _statusMessage.value = "发送完成: ${progress.fileName}"
                    TransferPhase.Failed -> _statusMessage.value = progress.message.ifBlank {
                        "发送失败"
                    }
                    TransferPhase.Idle -> Unit
                }
            }
        }
        openSource().use { source ->
            fileSender.send(fileName, mimeType, transferId, source)
        }
    }

    private suspend fun acceptTrustedInvite(invite: TransferInvite) {
        disconnectRoom()
        val identity = identityRepository.getOrCreate()
        devicePresence.consumeInvite(identity.deviceId)
        val roomInfo = RoomInfo(
            code = invite.code,
            joinUrl = "",
            wsUrl = invite.wsUrl,
            expiresAt = invite.expiresAt,
        )
        connectAsReceiver(roomInfo)
        _statusMessage.value = "已接受 ${invite.fromDisplayName} 的传输邀请，正在建立连接..."
    }

    private suspend fun connectAsSender(roomInfo: RoomInfo) {
        prefetchIceServers()
        signaling.connect(roomInfo.wsUrl, SignalingRole.Sender)
        signaling.waitUntilConnected()
        _room.value = roomInfo
        pairing.startPakeHandshake(roomInfo.code, roomInfo.code)
        _connectionMode.value = ConnectionMode.Connecting
        _p2pReady.value = false
        startPeerJoinWatcher(SignalingRole.Sender)
    }

    private suspend fun connectAsReceiver(roomInfo: RoomInfo) {
        prefetchIceServers()
        signaling.connect(roomInfo.wsUrl, SignalingRole.Receiver)
        signaling.waitUntilConnected()
        _room.value = roomInfo
        pairing.startPakeHandshake(roomInfo.code, roomInfo.code)
        _connectionMode.value = ConnectionMode.Connecting
        _p2pReady.value = false
        startPeerJoinWatcher(SignalingRole.Receiver)
    }

    private fun bindPeerHandlers() {
        val session = coordinator?.activeSession
            ?: error("WebRTC 会话未建立，无法绑定对等端")
        val fileReceiver = FileTransferReceiver(session, scope) { manifest ->
            createReceiveSink(manifest)
        }
        receiver = fileReceiver
        val localDeviceId = identityRepository.getOrCreate().deviceId
        transferJobs += scope.launch {
            session.incomingMessages.collect { message ->
                when (message) {
                    is DataChannelMessage.ChunkAck -> Unit
                    is DataChannelMessage.Text -> {
                        if (handleIncomingChat(message.text, localDeviceId)) return@collect
                        runCatching {
                            chatJson.decodeFromString(BlockRequest.serializer(), message.text)
                        }.onSuccess { request ->
                            sender?.onBlockRequest(request)
                            return@collect
                        }
                        runCatching { fileReceiver.handleText(message.text) }
                            .onFailure { error ->
                                _statusMessage.value = "接收失败: ${error.message ?: "未知错误"}"
                            }
                    }
                    is DataChannelMessage.Binary -> {
                        runCatching { fileReceiver.handleBinary(message.bytes) }
                            .onFailure { error ->
                                _statusMessage.value = "接收失败: ${error.message ?: "未知错误"}"
                            }
                    }
                }
            }
        }
        transferJobs += scope.launch {
            fileReceiver.progress.collect { progress ->
                _receiverProgress.value = progress
                when (progress.phase) {
                    TransferPhase.Handshaking,
                    TransferPhase.Transferring,
                    -> _statusMessage.value = progress.message.ifBlank {
                        "正在接收: ${progress.fileName}"
                    }
                    TransferPhase.Verifying -> _statusMessage.value = progress.message.ifBlank {
                        "正在校验文件..."
                    }
                    TransferPhase.Completed -> _statusMessage.value = progress.message.ifBlank {
                        "接收完成: ${progress.fileName}"
                    }
                    TransferPhase.Failed -> _statusMessage.value = progress.message.ifBlank {
                        "接收失败"
                    }
                    TransferPhase.Idle -> Unit
                }
            }
        }
    }

    fun disconnect() {
        disconnectRoom()
        _connectionMode.value = ConnectionMode.Closed
        _statusMessage.value = "已断开"
        startDevicePresence()
    }

    fun shutdown() {
        disconnectRoom()
        devicePresence.disconnect()
        presenceJob?.cancel()
        onlineRefreshJob?.cancel()
        presenceJob = null
        onlineRefreshJob = null
        _connectionMode.value = ConnectionMode.Closed
        _statusMessage.value = "已断开"
    }

    private fun disconnectRoom() {
        peerWatchJob?.cancel()
        peerWatchJob = null
        icePrefetchJob?.cancel()
        icePrefetchJob = null
        cachedIceServers = null
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
        _chatMessages.value = emptyList()
        seenChatIds.clear()
        _pendingInvite.value = null
        _p2pReady.value = false
        _addressFamily.value = PeerAddressFamily.Unknown
    }

    private fun startPeerJoinWatcher(role: SignalingRole) {
        peerWatchJob?.cancel()
        peerWatchJob = scope.launch {
            signaling.incoming.filterIsInstance<SignalingMessage.Joined>().collect { joined ->
                if (joined.peers.size >= 2 && coordinator == null) {
                    _statusMessage.value = "双方已就绪，正在建立 P2P 连接..."
                    runCatching {
                        establishWebRtc(role)
                    }.onFailure { error ->
                        coordinator?.stop()
                        coordinator = null
                        _p2pReady.value = false
                        val hint = when {
                            error.message?.contains("Timed out", ignoreCase = true) == true ->
                                "（请两台设备连同一 WiFi）"
                            error.message?.contains("打洞", ignoreCase = true) == true -> ""
                            else -> ""
                        }
                        _statusMessage.value =
                            "P2P 连接失败: ${error.message ?: "未知错误"}$hint"
                    }
                }
            }
        }
    }

    private suspend fun establishWebRtc(role: SignalingRole) {
        if (coordinator != null) return
        signaling.waitUntilConnected()
        _statusMessage.value = "获取 ICE 服务器..."
        val ice = resolveIceServers()
        _statusMessage.value = "正在 P2P 直连（请两台设备连同一 WiFi）..."
        val webRtcCoordinator = WebRtcCoordinator(
            scope = scope,
            signaling = signaling,
            role = role,
            iceServers = ice,
            onStatus = { _statusMessage.value = it },
        )
        coordinator = webRtcCoordinator
        try {
            withTimeout(90_000) {
                webRtcCoordinator.start()
            }
            val session = webRtcCoordinator.activeSession
                ?: error("WebRTC 会话建立失败")
            _connectionMode.value = session.connectionMode.value
            _addressFamily.value = session.addressFamily.value
            transferJobs += scope.launch {
                session.connectionMode.collect { _connectionMode.value = it }
            }
            transferJobs += scope.launch {
                session.addressFamily.collect { _addressFamily.value = it }
            }
            transferJobs += scope.launch {
                session.dataChannelState.collect { state ->
                    if (state == DataChannelState.Open) {
                        markP2pReady()
                    }
                }
            }
            if (session.dataChannelState.value != DataChannelState.Open) {
                session.waitForDataChannelOpen()
            }
            markP2pReady()
            bindPeerHandlers()
            session.refreshAddressFamilyNow()
        } catch (error: Exception) {
            webRtcCoordinator.stop()
            coordinator = null
            _p2pReady.value = false
            throw error
        }
    }

    private fun prefetchIceServers() {
        icePrefetchJob?.cancel()
        icePrefetchJob = scope.launch {
            runCatching {
                val ice = signaling.fetchIceServers()
                cachedIceServers = WebRtcCoordinator.defaultIceServers(ice.iceServers)
            }
        }
    }

    private suspend fun resolveIceServers(): List<IceServerConfig> {
        icePrefetchJob?.join()
        return cachedIceServers ?: WebRtcCoordinator.defaultIceServers(
            runCatching { signaling.fetchIceServers().iceServers }.getOrDefault(emptyList()),
        )
    }

    private suspend fun requireActiveSession(): com.oneturn.transfer.webrtc.WebRtcSession {
        coordinator?.activeSession?.let { return it }
        withTimeout(90_000) {
            while (coordinator?.activeSession == null) {
                delay(200)
            }
        }
        return coordinator?.activeSession ?: error("尚未建立 P2P 连接，请等待对方加入")
    }

    private suspend fun ensureDataChannelReady(session: com.oneturn.transfer.webrtc.WebRtcSession) {
        if (session.dataChannelState.value == DataChannelState.Open) {
            markP2pReady()
            return
        }
        _statusMessage.value = "等待 P2P 通道就绪..."
        try {
            session.waitForDataChannelOpen()
            markP2pReady()
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException(
                "P2P 通道超时未就绪。请两台设备连接同一 WiFi 后重试",
                error,
            )
        }
    }

    private fun markP2pReady() {
        _p2pReady.value = true
        if (_room.value != null) {
            _statusMessage.value = "P2P 已连接，可聊天或互传文件"
        }
    }

    private fun generateTransferId(): String =
        "xfer-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(1_000_000)}"

    fun reportError(message: String) {
        _statusMessage.value = message
    }

    private fun handleIncomingChat(text: String, localDeviceId: String): Boolean {
        val payload = runCatching {
            chatJson.decodeFromString(ChatMessagePayload.serializer(), text)
        }.getOrNull() ?: return false
        if (payload.type != "chat") return false
        if (!seenChatIds.add(payload.messageId)) return true
        appendChatLine(payload.toChatLine(localDeviceId))
        return true
    }

    private fun appendChatLine(line: ChatLine) {
        _chatMessages.value = _chatMessages.value + line
    }
}
