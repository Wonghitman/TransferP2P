package com.oneturn.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager
import com.oneturn.transfer.config.DEFAULT_SIGNALING_BASE_URL
import com.oneturn.transfer.pairing.RoomCodeGenerator
import com.oneturn.transfer.pairing.RoomInfo
import com.oneturn.transfer.qr.QrCodeGenerator
import com.oneturn.transfer.signaling.SignalingConnectionState
import com.oneturn.transfer.webrtc.ConnectionMode
import com.oneturn.transfer.webrtc.PeerAddressFamily
import kotlinx.coroutines.launch

/**
 * 匹配/连接页：创建房间、输入房间码加入、扫码加入、从信任设备发起连接。
 * 已创建房间时展示房间码与二维码供对方扫码加入。
 */
@Composable
fun MatchPage(
    sessionManager: TransferSessionManager,
    onScanQr: suspend () -> String? = { null },
    initialCode: String = "",
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val room by sessionManager.room.collectAsState()
    val connectionMode by sessionManager.connectionMode.collectAsState()
    val addressFamily by sessionManager.addressFamily.collectAsState()
    val status by sessionManager.statusMessage.collectAsState()
    val trusted by sessionManager.deviceRegistry.trustedDevices.collectAsState()
    val signaling by sessionManager.signaling.connectionState.collectAsState()
    var roomCode by remember { mutableStateOf(initialCode) }
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBlock(signaling, connectionMode, addressFamily, status)

        if (room == null) {
            Text("建立连接", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        runCatching { sessionManager.createSendRoom() }
                            .onFailure { error ->
                                sessionManager.reportError("创建房间失败: ${error.message ?: "未知错误"}")
                            }
                        isBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("创建房间（显示房间码）")
            }
            RoomCodeInput(
                onCodeChange = { roomCode = it },
                enabled = !isBusy,
                initialCode = initialCode,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !isBusy && RoomCodeGenerator.isValid(roomCode),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            runCatching {
                                sessionManager.joinReceiveRoom(RoomCodeGenerator.normalize(roomCode))
                            }.onFailure { error ->
                                sessionManager.reportError("加入房间失败: ${error.message ?: "未知错误"}")
                            }
                            isBusy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("加入")
                }
                Button(
                    enabled = !isBusy,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val scanned = onScanQr()
                            if (scanned != null) {
                                val parsed = RoomCodeGenerator.parseFromScan(scanned)
                                roomCode = parsed
                                runCatching { sessionManager.joinReceiveRoom(parsed) }
                                    .onFailure { error ->
                                        sessionManager.reportError("加入房间失败: ${error.message ?: "未知错误"}")
                                    }
                            }
                            isBusy = false
                        }
                    },
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
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            scope.launch {
                                isBusy = true
                                runCatching { sessionManager.inviteTrustedDevice(device) }
                                    .onFailure { error ->
                                        sessionManager.reportError("邀请失败: ${error.message ?: "未知错误"}")
                                    }
                                isBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("连接 ${device.displayName}（${if (device.online) "在线" else "离线"}）")
                    }
                }
            }
        } else {
            RoomShareCard(room!!)
        }
        Text(
            "信令: $DEFAULT_SIGNALING_BASE_URL",
            style = MaterialTheme.typography.bodySmall,
        )
        if (isBusy) CircularProgressIndicator()
    }
}

@Composable
private fun RoomShareCard(room: RoomInfo) {
    val matrix = remember(room.joinUrl) {
        if (room.joinUrl.isNotBlank()) QrCodeGenerator.encodeMatrix(room.joinUrl) else null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("房间已创建", style = MaterialTheme.typography.titleMedium)
        Text("房间码: ${room.code}", style = MaterialTheme.typography.titleLarge)
        Text(
            "让对方输入房间码或在「聊天」页发起文件，等待对方加入...",
            style = MaterialTheme.typography.bodySmall,
        )
        if (room.joinUrl.isNotBlank()) {
            Text(room.joinUrl, style = MaterialTheme.typography.bodySmall)
            matrix?.let {
                QrCodeImage(it, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun StatusBlock(
    signaling: SignalingConnectionState,
    mode: ConnectionMode,
    family: PeerAddressFamily,
    status: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(signalingLabel(signaling), style = MaterialTheme.typography.bodySmall)
        Text(connectionLabel(mode, family), color = MaterialTheme.colorScheme.primary)
        if (status.isNotBlank()) Text(status)
    }
}

private fun signalingLabel(state: SignalingConnectionState): String = when (state) {
    SignalingConnectionState.Connected -> "信令: 已连接"
    SignalingConnectionState.Connecting -> "信令: 连接中..."
    SignalingConnectionState.Reconnecting -> "信令: 重连中..."
    SignalingConnectionState.Disconnected -> "信令: 未连接"
}

private fun connectionLabel(mode: ConnectionMode, family: PeerAddressFamily): String = when (mode) {
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
