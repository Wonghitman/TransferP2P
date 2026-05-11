package com.oneturn.scaffolddemo.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat

/**
 * 递归查找当前上下文关联 of Activity
 */
fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    // 核心控制参数：是否固定 TopBar
    keepTopBarFixed: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // 根据 API 版本采用不同的窗口管理策略
    DisposableEffect(keepTopBarFixed) {
        val window = context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val originalMode = window.attributes.softInputMode

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 (API 35)+ 强制全屏沉浸，不再支持通过 setDecorFitsSystemWindows(true) 切换到 Pan 模式。
            // 我们保持其为 false（沉浸式），键盘不会引发物理窗口平移。
            window.setDecorFitsSystemWindows(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30-34 依然可以根据 keepTopBarFixed 动态切换
            window.setDecorFitsSystemWindows(!keepTopBarFixed)
        } else {
            // API 30 以下逻辑保持不变
            if (keepTopBarFixed) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            } else {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            }
        }
        
        window.attributes = window.attributes

        onDispose {
            // 还原窗口状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 还原通常推荐的 Edge-to-Edge 模式
                window.setDecorFitsSystemWindows(false) 
            } else {
                window.setSoftInputMode(originalMode)
            }
            window.attributes = window.attributes
        }
    }

    Scaffold(
        modifier = modifier
            .graphicsLayer {
                // 【核心修正】：针对 API 35+，由于窗口绝对静止且无法切换到 Pan，
                // 我们在 “顶出模式” (keepTopBarFixed = false) 时采用手动位移模拟。
                if (!keepTopBarFixed && Build.VERSION.SDK_INT >= 35) {
                    translationY = -imeBottomPx.toFloat()
                } else {
                    translationY = 0f
                }
            },
        topBar = topBar,
        bottomBar = bottomBar,
        contentWindowInsets = if (keepTopBarFixed) {
            // 固定模式：主动消费 IME Insets 以抬起底部内容，TopBar 在沉浸式环境下保持不动
            ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime)
        } else {
            // 顶出模式：不消费键盘高度，让系统 WindowManager 进行物理平移
            ScaffoldDefaults.contentWindowInsets
        }
    ) { paddingValues ->
        content(paddingValues)
    }
}
