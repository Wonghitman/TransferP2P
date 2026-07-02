package com.oneturn.scaffolddemo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SwipeablePageWrapper(
    offsetX: Float,
    screenWidthPx: Float,
    isBottomPage: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                if (isBottomPage) {
                    val progress = if (screenWidthPx > 0f) {
                        (offsetX / screenWidthPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    // progress=0 时保持原位，避免老页面在盖住状态下被向左偏移
                    translationX = if (progress > 0f) {
                        -screenWidthPx * (1f - progress) * 0.3f
                    } else {
                        0f
                    }
                    alpha = if (progress > 0f) 0.8f + 0.2f * progress else 1f
                } else {
                    translationX = offsetX
                }
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()

        if (!isBottomPage && offsetX > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .offset { IntOffset(-8.dp.roundToPx(), 0) }
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.08f)),
                        ),
                    ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenA(onNavigateToB: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Screen A") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
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
        topBar = { TopAppBar(title = { Text("Screen B") }) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这是页面 B", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("从屏幕任意位置向右滑动返回", color = Color.Gray)
            }
        }
    }
}
