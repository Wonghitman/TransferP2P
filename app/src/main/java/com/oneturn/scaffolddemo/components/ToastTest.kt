package com.oneturn.scaffolddemo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import android.view.WindowManager
import androidx.compose.material3.ButtonDefaults
import kotlinx.coroutines.delay


@Composable
fun DialogToast(
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false, // 解除 Dialog 默认的宽度限制
            dismissOnBackPress = false,      // 不拦截返回键（视你的需求而定）
            dismissOnClickOutside = false    // 不拦截外部点击
        )
    ) {
        // 【核心黑科技】：获取 Dialog 的底层 Window
        // 1. 把蒙层透明度设为 0
        // 2. 设置 FLAG_NOT_TOUCH_MODAL 允许点击透传
        // 3. 设置 FLAG_NOT_FOCUSABLE 防止拦截返回键等事件（可选，但通常与透传配合使用）
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.apply {
            setDimAmount(0f)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }

        // 占满全屏，但背景透明，内部对齐方式设为 TopCenter 或 BottomCenter
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            // 这里放你原来的 Box Toast UI 代码
            Box(
                modifier = Modifier
                    .padding(top = 100.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = message, color = Color.White)
            }
        }
    }
}

@Composable
fun PopupToast(
    message: String,
    onDismiss: () -> Unit
) {
    // Popup 默认 focusable = false，这会使其不拦截外部点击
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        // 【Popup 版黑科技】：Popup 虽然没有直接提供 WindowProvider，但它的根 View 就是底层窗口的 View
        val view = LocalView.current
        LaunchedEffect(view) {
            val windowManager = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val params = view.layoutParams as? android.view.WindowManager.LayoutParams
            if (params != null) {
                // 注入透传 Flag
                params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager.updateViewLayout(view, params)
            }
        }

        Box(
            modifier = Modifier
                .padding(top = 120.dp) // 稍微偏下一点以区分 DialogToast
                .background(Color.Blue.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = "Popup: $message", color = Color.White)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToastTestScreen(
    onNavigateToChat: () -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var showToast by remember { mutableStateOf(false) }
    var usePopup by remember { mutableStateOf(false) } // 新增：是否使用 Popup 模式
    var toastMessage by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Dialog 与 Toast 遮挡测试", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = {
                showDialog = true
                // 模拟在 Dialog 弹出 1 秒后显示 Toast
            }) {
                Text("1. 先显 Dialog")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                toastMessage = "我是后出的 Toast"
                showToast = true
            }) {
                Text("直接显示 Toast")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                toastMessage = "我是先出的 Toast"
                showToast = true
                usePopup = false
                // 注意：这里只是演示，实际可能会通过 LaunchedEffect 在 Toast 显示时触发 Dialog
            }) {
                Text("先显示 Toast (Dialog)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                toastMessage = "显示 Popup Toast"
                showToast = true
                usePopup = true
            }) {
                Text("显示 Popup Toast (测试透传)")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onNavigateToChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("跳转到聊天界面")
            }
        }
    }

    // 弹窗逻辑
    if (showDialog) {
        BasicAlertDialog(onDismissRequest = { showDialog = false }) {
            Surface(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("我是一个 BasicAlertDialog")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        toastMessage = "在 Dialog 上层显示的 Toast"
                        showToast = true
                    }) {
                        Text("在弹窗上触发 Toast")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { showDialog = false }) {
                        Text("关闭 Dialog")
                    }
                }
            }
        }
    }

    if (showToast) {
        if (usePopup) {
            PopupToast(message = toastMessage) {
                showToast = false
            }
        } else {
            DialogToast(message = toastMessage) {
                showToast = false
            }
        }
        // 自动消失逻辑
        LaunchedEffect(showToast) {
            if (showToast) {
                delay(3000)
                showToast = false
            }
        }
    }
}
