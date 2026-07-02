package com.oneturn.transfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.oneturn.transfer.api.TransferSessionManager
import com.oneturn.transfer.config.DEFAULT_SIGNALING_BASE_URL
import com.oneturn.transfer.platform.PlatformFilePicker
import com.oneturn.transfer.platform.initPlatformContext
import com.oneturn.transfer.platform.initSettingsContext
import com.oneturn.transfer.ui.TransferApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var sessionManager: TransferSessionManager
    private lateinit var filePicker: PlatformFilePicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPlatformContext(this)
        initSettingsContext(this)
        sessionManager = TransferSessionManager(
            baseUrl = DEFAULT_SIGNALING_BASE_URL,
            scope = lifecycleScope,
        )
        filePicker = PlatformFilePicker(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TransferApp(
                    sessionManager = sessionManager,
                    onPickFile = { pickAndSend() },
                )
            }
        }
    }

    private suspend fun pickAndSend() {
        val picked = filePicker.pickFile() ?: return
        picked.openSource.let { open ->
            sessionManager.sendFile(
                fileName = picked.name,
                mimeType = picked.mimeType,
                sizeBytes = picked.sizeBytes,
                openSource = open,
            )
        }
    }

    override fun onDestroy() {
        sessionManager.disconnect()
        super.onDestroy()
    }
}
