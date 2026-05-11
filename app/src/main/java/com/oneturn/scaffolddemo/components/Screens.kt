package com.oneturn.scaffolddemo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 纯 UI 容器，不再自己管理手势状态
 * 
 * @param offsetX 当前页面的水平位移
 * @param content 页面内容
 */
@Composable
fun SwipeablePageWrapper(
    offsetX: Float,
    isBottomPage: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (isBottomPage) {
                    // 底层页面：模拟微信的视差效果
                    translationX = -200f * (1f - offsetX / size.width)
                    // 底层页面稍微暗一点
                    alpha = 0.8f + 0.2f * (offsetX / size.width)
                } else {
                    // 顶层页面：直接跟随位移
                    translationX = offsetX
                }
            }
            .background(MaterialTheme.colorScheme.background)
    ) {
        content()
        
        // 如果是顶层页面正在滑动，在它左边加一道阴影
        if (!isBottomPage && offsetX > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(10.dp)
                    .offset { IntOffset(-10.dp.roundToPx(), 0) }
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.1f))
                        )
                    )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenA(onNavigateToB: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Screen A") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("这是页面 A", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToB) {
                Text("跳转到页面 B")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenB() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Screen B") }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这是页面 B", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("从屏幕左侧边缘向右滑...", color = Color.Gray)
            }
        }
    }
}
