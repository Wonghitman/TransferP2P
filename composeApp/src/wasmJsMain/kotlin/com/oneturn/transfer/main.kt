package com.oneturn.transfer

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.oneturn.transfer.api.TransferSessionManager
import com.oneturn.transfer.config.DEFAULT_SIGNALING_BASE_URL
import com.oneturn.transfer.platform.PlatformFilePicker
import com.oneturn.transfer.platform.browserJoinCodeFromLocation
import com.oneturn.transfer.ui.WebTransferApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessionManager = TransferSessionManager(
        baseUrl = DEFAULT_SIGNALING_BASE_URL,
        scope = scope,
    )
    sessionManager.startDevicePresence()
    val filePicker = PlatformFilePicker()
    val initialJoinCode = browserJoinCodeFromLocation()

    ComposeViewport {
        WebTransferApp(
            sessionManager = sessionManager,
            initialJoinCode = initialJoinCode,
            onPickFile = {
                val picked = filePicker.pickFile() ?: return@WebTransferApp
                picked.openSource.let { open ->
                    sessionManager.sendFile(
                        fileName = picked.name,
                        mimeType = picked.mimeType,
                        sizeBytes = picked.sizeBytes,
                        openSource = open,
                    )
                }
            },
        )
    }

    if (initialJoinCode != null) {
        scope.launch {
            runCatching { sessionManager.joinReceiveRoom(initialJoinCode) }
                .onFailure { error ->
                    sessionManager.reportError("自动加入失败: ${error.message ?: "未知错误"}")
                }
        }
    }
}
