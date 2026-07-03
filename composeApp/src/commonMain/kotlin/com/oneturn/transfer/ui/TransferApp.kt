package com.oneturn.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager
import com.oneturn.transfer.chat.ChatLine
import com.oneturn.transfer.config.DEFAULT_SIGNALING_BASE_URL
import com.oneturn.transfer.pairing.RoomCodeGenerator
import com.oneturn.transfer.pairing.RoomInfo
import com.oneturn.transfer.qr.QrCodeGenerator
import com.oneturn.transfer.signaling.SignalingConnectionState
import com.oneturn.transfer.transfer.TransferPhase
import com.oneturn.transfer.transfer.TransferProgress
import com.oneturn.transfer.webrtc.ConnectionMode
import com.oneturn.transfer.webrtc.PeerAddressFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferApp(
    sessionManager: TransferSessionManager,
    onPickFile: suspend () -> Unit,
    onScanQr: suspend () -> String? = { null },
) {
    var tab by remember { mutableStateOf(TransferTab.Session) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer P2P") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabRow(tab) { tab = it }
            when (tab) {
                TransferTab.Session -> SessionScreen(sessionManager, onPickFile, onScanQr)
                TransferTab.Devices -> DevicesScreen(sessionManager)
            }
        }
    }
}

private enum class TransferTab { Session, Devices }

@Composable
private fun TabRow(selected: TransferTab, onSelect: (TransferTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onSelect(TransferTab.Session) }, modifier = Modifier.weight(1f)) {
            Text("传输")
        }
        Button(onClick = { onSelect(TransferTab.Devices) }, modifier = Modifier.weight(1f)) {
            Text("设备")
        }
    }
}

@Composable
private fun SessionScreen(
    sessionManager: TransferSessionManager,
    onPickFile: suspend () -> Unit,
    onScanQr: suspend () -> String?,
) {
    val scope = rememberCoroutineScope()
    val room by sessionManager.room.collectAsState()
    val connectionMode by sessionManager.connectionMode.collectAsState()
    val addressFamily by sessionManager.addressFamily.collectAsState()
    val p2pReady by sessionManager.p2pReady.collectAsState()
    val senderProgress by sessionManager.senderProgress.collectAsState()
    val receiverProgress by sessionManager.receiverProgress.collectAsState()
    val chatMessages by sessionManager.chatMessages.collectAsState()
    val status by sessionManager.statusMessage.collectAsState()
    val trusted by sessionManager.deviceRegistry.trustedDevices.collectAsState()
    val pendingInvite by sessionManager.pendingInvite.collectAsState()
    var roomCode by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }
    var isFileBusy by remember { mutableStateOf(false) }
    var isChatSending by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("信令: $DEFAULT_SIGNALING_BASE_URL", style = MaterialTheme.typography.bodySmall)
        SignalingBadge(sessionManager.signaling.connectionState.collectAsState().value)
        ConnectionBadge(connectionMode, addressFamily)
        Text(status)
        PendingInviteBanner(
            invite = pendingInvite,
            onAccept = { sessionManager.acceptPendingInvite() },
            onReject = { sessionManager.rejectPendingInvite() },
        )

        if (room == null) {
            ConnectSection(
                roomCode = roomCode,
                onRoomCodeChange = { roomCode = it },
                trusted = trusted,
                isBusy = isFileBusy,
                onCreateRoom = {
                    scope.launch {
                        isFileBusy = true
                        runCatching { sessionManager.createSendRoom() }
                            .onFailure { error ->
                                sessionManager.reportError("创建房间失败: ${error.message ?: "未知错误"}")
                            }
                        isFileBusy = false
                    }
                },
                onJoinRoom = {
                    scope.launch {
                        isFileBusy = true
                        runCatching {
                            sessionManager.joinReceiveRoom(RoomCodeGenerator.normalize(roomCode))
                        }.onFailure { error ->
                            sessionManager.reportError("加入房间失败: ${error.message ?: "未知错误"}")
                        }
                        isFileBusy = false
                    }
                },
                onScanQr = {
                    scope.launch {
                        isFileBusy = true
                        val scanned = onScanQr()
                        if (scanned != null) {
                            val parsed = RoomCodeGenerator.parseFromScan(scanned)
                            roomCode = parsed
                            runCatching { sessionManager.joinReceiveRoom(parsed) }
                                .onFailure { error ->
                                    sessionManager.reportError("加入房间失败: ${error.message ?: "未知错误"}")
                                }
                        }
                        isFileBusy = false
                    }
                },
                onInviteTrusted = { device ->
                    scope.launch {
                        isFileBusy = true
                        runCatching { sessionManager.inviteTrustedDevice(device) }
                            .onFailure { error ->
                                sessionManager.reportError("邀请失败: ${error.message ?: "未知错误"}")
                            }
                        isFileBusy = false
                    }
                },
            )
        } else {
            RoomDetails(room!!)
            ChatSection(
                messages = chatMessages,
                chatInput = chatInput,
                onChatInputChange = { chatInput = it },
                enabled = !isChatSending && !isFileBusy,
                onSendChat = {
                    val text = chatInput
                    scope.launch {
                        isChatSending = true
                        runCatching { sessionManager.sendChatMessage(text) }
                            .onSuccess { chatInput = "" }
                            .onFailure { error ->
                                sessionManager.reportError("发送失败: ${error.message ?: "未知错误"}")
                            }
                        isChatSending = false
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !isFileBusy,
                    onClick = {
                        scope.launch {
                            isFileBusy = true
                            runCatching { onPickFile() }
                                .onFailure { error ->
                                    sessionManager.reportError("发送失败: ${error.message ?: "未知错误"}")
                                }
                            isFileBusy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (p2pReady) "发送文件" else "发送文件（建立通道中…）")
                }
                Button(
                    onClick = { sessionManager.disconnect() },
                    enabled = !isFileBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("断开")
                }
            }
            TransferProgressView("发出", senderProgress)
            TransferProgressView("收到", receiverProgress)
        }
        Text(
            "已信任设备的连接请求需手动同意（双方保持 App 打开）",
            style = MaterialTheme.typography.bodySmall,
        )
        if (isFileBusy || isChatSending) CircularProgressIndicator()
    }
}

@Composable
private fun ConnectSection(
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    trusted: List<com.oneturn.transfer.identity.TrustedDevice>,
    isBusy: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onScanQr: () -> Unit,
    onInviteTrusted: (com.oneturn.transfer.identity.TrustedDevice) -> Unit,
) {
    Text("建立连接", style = MaterialTheme.typography.titleSmall)
    Button(enabled = !isBusy, onClick = onCreateRoom, modifier = Modifier.fillMaxWidth()) {
        Text("创建房间（显示房间码）")
    }
    OutlinedTextField(
        value = roomCode,
        onValueChange = onRoomCodeChange,
        label = { Text("输入房间码加入") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            enabled = !isBusy && roomCode.isNotBlank(),
            onClick = onJoinRoom,
            modifier = Modifier.weight(1f),
        ) {
            Text("加入")
        }
        Button(
            enabled = !isBusy,
            onClick = onScanQr,
            modifier = Modifier.weight(1f),
        ) {
            Text("扫码")
        }
    }
    Text("已信任设备", style = MaterialTheme.typography.titleSmall)
    if (trusted.isEmpty()) {
        Text("暂无，请先在「设备」页配对", style = MaterialTheme.typography.bodySmall)
    } else {
        trusted.forEach { device ->
            val onlineLabel = if (device.online) "在线" else "离线"
            Button(
                enabled = !isBusy,
                onClick = { onInviteTrusted(device) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("连接 ${device.displayName}（$onlineLabel）")
            }
        }
    }
}

@Composable
private fun PendingInviteBanner(
    invite: com.oneturn.transfer.presence.TransferInvite?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    invite ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${invite.fromDisplayName} 请求与你连接",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                Text("同意")
            }
            Button(onClick = onReject, modifier = Modifier.weight(1f)) {
                Text("拒绝")
            }
        }
    }
}

@Composable
private fun ChatSection(
    messages: List<ChatLine>,
    chatInput: String,
    onChatInputChange: (String) -> Unit,
    enabled: Boolean,
    onSendChat: () -> Unit,
) {
    Text("消息", style = MaterialTheme.typography.titleSmall)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (messages.isEmpty()) {
            Text(
                "P2P 连接后可互发消息",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            messages.forEach { line -> ChatBubble(line) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = chatInput,
            onValueChange = onChatInputChange,
            label = { Text("输入消息") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
        )
        Button(
            enabled = enabled && chatInput.isNotBlank(),
            onClick = onSendChat,
        ) {
            Text("发送")
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val align = if (line.isMine) Alignment.End else Alignment.Start
    val bg = if (line.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Text(
            text = if (line.isMine) "我" else line.fromName,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(line.text)
        }
    }
}

@Composable
private fun DevicesScreen(sessionManager: TransferSessionManager) {
    val scope = rememberCoroutineScope()
    val identity = remember { sessionManager.deviceRegistry.currentIdentity() }
    val trusted by sessionManager.deviceRegistry.trustedDevices.collectAsState()
    val pendingInvite by sessionManager.pendingInvite.collectAsState()
    var claimCode by remember { mutableStateOf("") }
    var registrationCode by remember { mutableStateOf<String?>(null) }
    var deviceStatus by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("本机: ${identity.displayName}")
        Text("设备 ID: ${identity.deviceId}", style = MaterialTheme.typography.bodySmall)
        Text(
            "设备配对走信令服务器，无需先建立 P2P 连接",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "配对码为 6 位大写字母或数字（非房间码）",
            style = MaterialTheme.typography.bodySmall,
        )
        PendingInviteBanner(
            invite = pendingInvite,
            onAccept = { sessionManager.acceptPendingInvite() },
            onReject = { sessionManager.rejectPendingInvite() },
        )
        if (deviceStatus.isNotBlank()) {
            Text(deviceStatus, color = MaterialTheme.colorScheme.error)
        }
        Button(
            enabled = !isBusy,
            onClick = {
                scope.launch {
                    isBusy = true
                    runCatching { sessionManager.deviceRegistry.startPairingRegistration() }
                        .onSuccess { reg ->
                            registrationCode = reg.pairingCode
                            deviceStatus = "配对码已生成，10 分钟内有效"
                        }
                        .onFailure { error ->
                            deviceStatus = "生成配对码失败: ${error.message ?: "未知错误"}"
                        }
                    isBusy = false
                }
            },
        ) {
            Text("生成配对码（给其他设备）")
        }
        registrationCode?.let {
            Text("配对码: $it", style = MaterialTheme.typography.titleMedium)
            Text("10 分钟内有效，给对方设备输入", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = claimCode,
            onValueChange = { claimCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) },
            label = { Text("输入配对码（6位）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = !isBusy && claimCode.isNotBlank(),
            onClick = {
                scope.launch {
                    isBusy = true
                    runCatching {
                        sessionManager.deviceRegistry.claimPairingCode(claimCode.trim())
                    }.onSuccess { trustedDevice ->
                        claimCode = ""
                        deviceStatus = "已添加信任设备: ${trustedDevice.displayName}，对方需点「刷新设备列表」"
                    }.onFailure { error ->
                        deviceStatus = error.message ?: "登记失败"
                    }
                    isBusy = false
                }
            },
        ) {
            Text("登记信任设备")
        }
        Button(
            enabled = !isBusy,
            onClick = {
                scope.launch {
                    isBusy = true
                    runCatching { sessionManager.deviceRegistry.refreshFromServer() }
                        .onSuccess { deviceStatus = "设备列表已刷新" }
                        .onFailure { error ->
                            deviceStatus = "刷新失败: ${error.message ?: "未知错误"}"
                        }
                    isBusy = false
                }
            },
        ) {
            Text("刷新设备列表")
        }
        Text("已信任设备 (${trusted.size})")
        trusted.forEach { device ->
            val onlineLabel = if (device.online) "在线" else "离线"
            Text("${device.displayName} · $onlineLabel")
            Text(device.deviceId, style = MaterialTheme.typography.bodySmall)
        }
        if (isBusy) CircularProgressIndicator()
    }
}

@Composable
private fun RoomDetails(room: RoomInfo) {
    val matrix = remember(room.joinUrl) {
        if (room.joinUrl.isNotBlank()) QrCodeGenerator.encodeMatrix(room.joinUrl) else null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("房间: ${room.code}", style = MaterialTheme.typography.titleMedium)
        if (room.joinUrl.isNotBlank()) {
            Text("加入链接: ${room.joinUrl}", style = MaterialTheme.typography.bodySmall)
            matrix?.let { QrCodeImage(it) }
        }
    }
}

@Composable
private fun SignalingBadge(state: SignalingConnectionState) {
    val label = when (state) {
        SignalingConnectionState.Connected -> "信令: 已连接"
        SignalingConnectionState.Connecting -> "信令: 连接中..."
        SignalingConnectionState.Reconnecting -> "信令: 重连中..."
        SignalingConnectionState.Disconnected -> "信令: 未连接"
    }
    Text(label, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ConnectionBadge(mode: ConnectionMode, family: PeerAddressFamily) {
    val label = when (mode) {
        ConnectionMode.Direct -> when (family) {
            PeerAddressFamily.IPv6 -> "连接: 直连 IPv6"
            PeerAddressFamily.IPv4 -> "连接: 直连 IPv4"
            PeerAddressFamily.Relay -> "连接: 中继"
            PeerAddressFamily.Unknown -> "连接: 直连"
        }
        ConnectionMode.Relay -> "连接: 中继"
        ConnectionMode.Connecting -> "连接: 连接中"
        ConnectionMode.Failed -> "连接: 失败"
        ConnectionMode.Closed -> "连接: 已关闭"
    }
    Text(label, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TransferProgressView(label: String, progress: TransferProgress) {
    if (progress.phase == TransferPhase.Idle) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ${progress.fileName} - ${progress.phase}")
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${progress.bytesSent}/${progress.totalBytes} bytes, " +
                "${"%.1f".format(progress.bytesPerSecond / 1024)} KB/s",
        )
        if (progress.message.isNotBlank()) Text(progress.message)
    }
}
