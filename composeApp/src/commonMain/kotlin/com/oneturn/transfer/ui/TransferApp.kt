package com.oneturn.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager
import com.oneturn.transfer.config.DEFAULT_SIGNALING_BASE_URL
import com.oneturn.transfer.pairing.RoomInfo
import com.oneturn.transfer.qr.QrCodeGenerator
import com.oneturn.transfer.transfer.TransferPhase
import com.oneturn.transfer.webrtc.ConnectionMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferApp(
    sessionManager: TransferSessionManager,
    onPickFile: suspend () -> Unit,
) {
    var tab by remember { mutableStateOf(TransferTab.Send) }
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
                TransferTab.Send -> SendScreen(sessionManager, onPickFile)
                TransferTab.Receive -> ReceiveScreen(sessionManager)
                TransferTab.Devices -> DevicesScreen(sessionManager)
            }
        }
    }
}

private enum class TransferTab { Send, Receive, Devices }

@Composable
private fun TabRow(selected: TransferTab, onSelect: (TransferTab) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onSelect(TransferTab.Send) }, modifier = Modifier.weight(1f)) {
            Text("发送")
        }
        Button(onClick = { onSelect(TransferTab.Receive) }, modifier = Modifier.weight(1f)) {
            Text("接收")
        }
        Button(onClick = { onSelect(TransferTab.Devices) }, modifier = Modifier.weight(1f)) {
            Text("设备")
        }
    }
}

@Composable
private fun SendScreen(
    sessionManager: TransferSessionManager,
    onPickFile: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val room by sessionManager.room.collectAsState()
    val connectionMode by sessionManager.connectionMode.collectAsState()
    val progress by sessionManager.senderProgress.collectAsState()
    val status by sessionManager.statusMessage.collectAsState()
    var isBusy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("信令: $DEFAULT_SIGNALING_BASE_URL", style = MaterialTheme.typography.bodySmall)
        ConnectionBadge(connectionMode)
        Text(status)

        if (room == null) {
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        runCatching { sessionManager.createSendRoom() }
                            .onFailure { sessionManager.disconnect() }
                        isBusy = false
                    }
                },
            ) {
                Text("创建房间")
            }
        } else {
            RoomDetails(room!!)
            Button(
                enabled = !isBusy && progress.phase != TransferPhase.Transferring,
                onClick = {
                    scope.launch {
                        isBusy = true
                        runCatching { onPickFile() }
                        isBusy = false
                    }
                },
            ) {
                Text("选择文件并发送")
            }
            TransferProgressView(progress)
            Button(onClick = { sessionManager.disconnect() }) {
                Text("断开")
            }
        }
        if (isBusy) CircularProgressIndicator()
    }
}

@Composable
private fun ReceiveScreen(sessionManager: TransferSessionManager) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    val room by sessionManager.room.collectAsState()
    val connectionMode by sessionManager.connectionMode.collectAsState()
    val progress by sessionManager.receiverProgress.collectAsState()
    val status by sessionManager.statusMessage.collectAsState()
    var isBusy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionBadge(connectionMode)
        Text(status)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("房间码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = !isBusy && code.isNotBlank(),
            onClick = {
                scope.launch {
                    isBusy = true
                    runCatching { sessionManager.joinReceiveRoom(code.trim()) }
                    isBusy = false
                }
            },
        ) {
            Text("加入房间")
        }
        room?.let { RoomDetails(it) }
        TransferProgressView(progress)
        if (room != null) {
            Button(onClick = { sessionManager.disconnect() }) {
                Text("断开")
            }
        }
        if (isBusy) CircularProgressIndicator()
    }
}

@Composable
private fun DevicesScreen(sessionManager: TransferSessionManager) {
    val scope = rememberCoroutineScope()
    val identity = remember { sessionManager.deviceRegistry.currentIdentity() }
    val trusted by sessionManager.deviceRegistry.trustedDevices.collectAsState()
    var pairingCode by remember { mutableStateOf("") }
    var claimCode by remember { mutableStateOf("") }
    var registrationCode by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本机: ${identity.displayName}")
        Text("设备 ID: ${identity.deviceId}", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                scope.launch {
                    val reg = sessionManager.deviceRegistry.startPairingRegistration()
                    registrationCode = reg.pairingCode
                }
            },
        ) {
            Text("生成配对码（给其他设备）")
        }
        registrationCode?.let { Text("配对码: $it") }
        OutlinedTextField(
            value = claimCode,
            onValueChange = { claimCode = it },
            label = { Text("输入配对码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                scope.launch {
                    sessionManager.deviceRegistry.claimPairingCode(claimCode.trim())
                    claimCode = ""
                }
            },
        ) {
            Text("登记信任设备")
        }
        Button(
            onClick = {
                scope.launch { sessionManager.deviceRegistry.refreshFromServer() }
            },
        ) {
            Text("刷新设备列表")
        }
        Text("已信任设备 (${trusted.size})")
        trusted.forEach { device ->
            Text("${device.displayName} (${device.deviceId})")
        }
    }
}

@Composable
private fun RoomDetails(room: RoomInfo) {
    val matrix = remember(room.joinUrl) {
        if (room.joinUrl.isNotBlank()) QrCodeGenerator.encodeMatrix(room.joinUrl) else null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("房间码: ${room.code}", style = MaterialTheme.typography.titleMedium)
        if (room.joinUrl.isNotBlank()) {
            Text("加入链接: ${room.joinUrl}", style = MaterialTheme.typography.bodySmall)
            matrix?.let { QrCodeImage(it) }
        }
    }
}

@Composable
private fun ConnectionBadge(mode: ConnectionMode) {
    val label = when (mode) {
        ConnectionMode.Direct -> "连接模式: 直连"
        ConnectionMode.Relay -> "连接模式: TURN 中继"
        ConnectionMode.Connecting -> "连接模式: 连接中"
        ConnectionMode.Failed -> "连接模式: 失败"
        ConnectionMode.Closed -> "连接模式: 已关闭"
    }
    Text(label, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TransferProgressView(progress: com.oneturn.transfer.transfer.TransferProgress) {
    if (progress.phase == TransferPhase.Idle) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${progress.fileName} - ${progress.phase}")
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
