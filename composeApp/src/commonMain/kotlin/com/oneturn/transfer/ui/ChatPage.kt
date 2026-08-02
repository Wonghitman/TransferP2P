package com.oneturn.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager
import com.oneturn.transfer.chat.ChatItem
import com.oneturn.transfer.pairing.RoomInfo
import com.oneturn.transfer.signaling.SignalingConnectionState
import com.oneturn.transfer.transfer.TransferPhase
import com.oneturn.transfer.webrtc.ConnectionMode
import com.oneturn.transfer.webrtc.PeerAddressFamily
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * 全屏聊天页：顶部连接状态栏，中部消息流（文本气泡 + 文件卡片，自动滚动到底），
 * 底部固定输入栏（发送消息 + 发送文件）。
 */
@Composable
fun ChatPage(
    sessionManager: TransferSessionManager,
    onPickFile: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val room by sessionManager.room.collectAsState()
    val connectionMode by sessionManager.connectionMode.collectAsState()
    val addressFamily by sessionManager.addressFamily.collectAsState()
    val p2pReady by sessionManager.p2pReady.collectAsState()
    val chatMessages by sessionManager.chatMessages.collectAsState()
    val status by sessionManager.statusMessage.collectAsState()
    val signaling by sessionManager.signaling.connectionState.collectAsState()
    var chatInput by remember { mutableStateOf("") }
    var isChatSending by remember { mutableStateOf(false) }
    var isFileBusy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val lastItemCount = chatMessages.size
    LaunchedEffect(lastItemCount) {
        if (lastItemCount > 0) {
            listState.animateScrollToItem(lastItemCount - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ChatTopBar(
            room = room,
            signaling = signaling,
            mode = connectionMode,
            family = addressFamily,
            p2pReady = p2pReady,
            status = status,
            onDisconnect = { sessionManager.disconnect() },
        )

        if (room == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "尚未连接，请先在「匹配」页创建或加入房间",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (chatMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (p2pReady) "已连接，发送一条消息或文件开始吧" else "P2P 通道建立中...",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                items(chatMessages, key = { it.id }) { item ->
                    when (item) {
                        is ChatItem.Text -> TextBubble(item)
                        is ChatItem.File -> FileBubble(item)
                    }
                }
            }
        }

        ChatInputBar(
            chatInput = chatInput,
            onChatInputChange = { chatInput = it },
            enabled = !isChatSending && !isFileBusy && room != null,
            canSend = chatInput.isNotBlank(),
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
            onPickFile = {
                scope.launch {
                    isFileBusy = true
                    runCatching { onPickFile() }
                        .onFailure { error ->
                            sessionManager.reportError("发送失败: ${error.message ?: "未知错误"}")
                        }
                    isFileBusy = false
                }
            },
        )
    }
}

@Composable
private fun ChatTopBar(
    room: RoomInfo?,
    signaling: SignalingConnectionState,
    mode: ConnectionMode,
    family: PeerAddressFamily,
    p2pReady: Boolean,
    status: String,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    room?.let { "房间: ${it.code}" } ?: "未连接",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(signalingLabel(signaling))
                        append(" · ")
                        append(connectionLabel(mode, family))
                        if (p2pReady) append(" · 就绪")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onDisconnect, enabled = room != null) {
                Text("断开")
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextBubble(item: ChatItem.Text) {
    val align = if (item.isMine) Alignment.End else Alignment.Start
    val bg = if (item.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Text(
            text = if (item.isMine) "我" else item.fromName,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(item.text)
        }
    }
}

@Composable
private fun FileBubble(item: ChatItem.File) {
    val align = if (item.isMine) Alignment.End else Alignment.Start
    val bg = if (item.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val inProgress = item.phase == TransferPhase.Handshaking || item.phase == TransferPhase.Transferring ||
        item.phase == TransferPhase.Verifying
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Text(
            text = if (item.isMine) "我发送了文件" else "对方发送了文件",
            style = MaterialTheme.typography.labelSmall,
        )
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(item.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                inProgress -> {
                    LinearProgressIndicator(
                        progress = { item.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(item.bytesSent)} / ${formatBytes(item.sizeBytes)}" +
                            if (item.bytesPerSecond > 0) " · ${formatSpeed(item.bytesPerSecond)}" else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                item.phase == TransferPhase.Completed -> {
                    Text(
                        "完成 · ${formatBytes(item.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item.phase == TransferPhase.Failed -> {
                    Text(
                        item.message.ifBlank { "传输失败" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    Text(
                        item.message.ifBlank { "等待中..." },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    chatInput: String,
    onChatInputChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    onSendChat: () -> Unit,
    onPickFile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) onSendChat()
                },
            ),
        )
        OutlinedButton(
            onClick = onPickFile,
            enabled = enabled,
        ) {
            Text("文件")
        }
        Button(
            enabled = enabled && canSend,
            onClick = onSendChat,
        ) {
            Text("发送")
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).roundToLong() / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).roundToLong() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 10).roundToLong() / 10.0} GB"
}

private fun formatSpeed(bytesPerSecond: Double): String =
    formatBytes(bytesPerSecond.roundToLong()) + "/s"

private fun signalingLabel(state: SignalingConnectionState): String = when (state) {
    SignalingConnectionState.Connected -> "信令已连接"
    SignalingConnectionState.Connecting -> "信令连接中"
    SignalingConnectionState.Reconnecting -> "信令重连中"
    SignalingConnectionState.Disconnected -> "信令未连接"
}

private fun connectionLabel(mode: ConnectionMode, family: PeerAddressFamily): String = when (mode) {
    ConnectionMode.Direct -> when (family) {
        PeerAddressFamily.IPv6 -> "直连 IPv6"
        PeerAddressFamily.IPv4 -> "直连 IPv4"
        PeerAddressFamily.Relay -> "中继"
        PeerAddressFamily.Unknown -> "直连"
    }
    ConnectionMode.Relay -> "中继"
    ConnectionMode.Connecting -> "连接中"
    ConnectionMode.Failed -> "连接失败"
    ConnectionMode.Closed -> "已关闭"
}
