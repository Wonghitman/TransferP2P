package com.oneturn.transfer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferApp(
    sessionManager: TransferSessionManager,
    onPickFile: suspend () -> Unit,
    onScanQr: suspend () -> String? = { null },
) {
    val room by sessionManager.room.collectAsState()
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    var tab by remember { mutableStateOf(TransferTab.Match) }
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Transfer P2P") },
                )
            },
            bottomBar = {
                if (!keyboardVisible) {
                    NavigationBar {
                        TransferTab.entries.forEach { entry ->
                            val selected = tab == entry
                            NavigationBarItem(
                                selected = selected,
                                onClick = { tab = entry },
                                icon = { Text(entry.icon) },
                                label = {
                                    Text(
                                        if (entry == TransferTab.Chat && room != null) "● ${entry.label}" else entry.label,
                                    )
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    TransferTab.Match -> MatchPage(
                        sessionManager = sessionManager,
                        onScanQr = onScanQr,
                        modifier = Modifier.padding(16.dp),
                    )
                    TransferTab.Chat -> ChatPage(
                        sessionManager = sessionManager,
                        onPickFile = onPickFile,
                    )
                    TransferTab.Devices -> DevicesPage(
                        sessionManager = sessionManager,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        PendingInviteDialog(sessionManager)
    }
}

private enum class TransferTab(val label: String, val icon: String) {
    Match("匹配", "🔗"),
    Chat("聊天", "💬"),
    Devices("设备", "📱"),
}
