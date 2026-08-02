package com.oneturn.transfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.api.TransferSessionManager
import kotlinx.coroutines.launch

@Composable
fun DevicesPage(
    sessionManager: TransferSessionManager,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val identity = remember { sessionManager.deviceRegistry.currentIdentity() }
    val trusted by sessionManager.deviceRegistry.trustedDevices.collectAsState()
    var claimCode by remember { mutableStateOf("") }
    var registrationCode by remember { mutableStateOf<String?>(null) }
    var deviceStatus by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
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
