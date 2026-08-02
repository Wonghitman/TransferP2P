package com.oneturn.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager

private val WebBg = androidx.compose.ui.graphics.Color(0xFFF6F7F9)

@Composable
fun WebTransferApp(
    sessionManager: TransferSessionManager,
    initialJoinCode: String? = null,
    onPickFile: suspend () -> Unit,
) {
    // wasmJs/Skiko has no system CJK fonts — theme must load Noto Sans SC first.
    WithChineseFont {
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WebBg),
                contentAlignment = Alignment.Center,
            ) {
                val wide = maxWidth >= 960.dp
                Surface(
                    modifier = Modifier
                        .padding(if (wide) 32.dp else 16.dp)
                        .widthIn(max = if (wide) 1040.dp else 640.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (wide) 28.dp else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        WebHeader()
                        var tab by remember { mutableStateOf(WebTab.Match) }
                        when (tab) {
                            WebTab.Match -> MatchPage(
                                sessionManager = sessionManager,
                                initialCode = initialJoinCode.orEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                            WebTab.Chat -> ChatPage(
                                sessionManager = sessionManager,
                                onPickFile = onPickFile,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                            WebTab.Devices -> DevicesPage(
                                sessionManager = sessionManager,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                        WebTabRow(tab) { tab = it }
                    }
                }
            }
            PendingInviteDialog(sessionManager)
        }
    }
}

private enum class WebTab { Match, Chat, Devices }

@Composable
private fun WebHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Transfer P2P",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "网页端 · 与客户端同 WiFi 直连传输",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WebTabRow(selected: WebTab, onSelect: (WebTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        WebTabButton("匹配", selected == WebTab.Match) { onSelect(WebTab.Match) }
        WebTabButton("聊天", selected == WebTab.Chat) { onSelect(WebTab.Chat) }
        WebTabButton("设备", selected == WebTab.Devices) { onSelect(WebTab.Devices) }
    }
}

@Composable
private fun WebTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = if (selected) "● $label" else label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
