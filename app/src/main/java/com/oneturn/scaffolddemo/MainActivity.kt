package com.oneturn.scaffolddemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oneturn.scaffolddemo.components.ChatScreen
import com.oneturn.scaffolddemo.components.ScreenA
import com.oneturn.scaffolddemo.components.ScreenB
import com.oneturn.scaffolddemo.components.SwipeablePageWrapper
import com.oneturn.scaffolddemo.components.ToastTestScreen
import com.oneturn.scaffolddemo.navigation.NavKey
import com.oneturn.scaffolddemo.ui.theme.ScaffoldDemoTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScaffoldDemoTheme {
                val backstack = rememberSaveable { 
                    mutableStateListOf<NavKey>(NavKey.ToastTest) 
                }
                
                val scope = rememberCoroutineScope()
                val configuration = LocalConfiguration.current
                val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
                
                // 侧滑位移状态
                val offsetX = remember { Animatable(0f) }
                
                // 状态保存器
                val stateHolder = rememberSaveableStateHolder()

                // 物理返回键拦截
                androidx.activity.compose.BackHandler(enabled = backstack.size > 1) {
                    scope.launch {
                        // 执行动画退出
                        offsetX.animateTo(
                            targetValue = screenWidthPx,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        offsetX.snapTo(0f)
                        backstack.removeLast()
                    }
                }

                // 辅助函数：根据 Key 渲染对应的 Composable
                @Composable
                fun RenderPage(key: NavKey) {
                    when (key) {
                        is NavKey.ToastTest -> ToastTestScreen(
                            onNavigateToChat = { 
                                if (backstack.last() !is NavKey.ScreenA) {
                                    // 关键：在跳转前预设位移，防止新页面在 0 位置闪现
                                    scope.launch { offsetX.snapTo(screenWidthPx) }
                                    backstack.add(NavKey.ScreenA) 
                                }
                            }
                        )
                        is NavKey.ScreenA -> ScreenA(
                            onNavigateToB = { 
                                if (backstack.last() !is NavKey.ScreenB) {
                                    scope.launch { offsetX.snapTo(screenWidthPx) }
                                    backstack.add(NavKey.ScreenB) 
                                }
                            }
                        )
                        is NavKey.ScreenB -> ScreenB()
                        is NavKey.Chat -> ChatScreen()
                    }
                }

                // 进场动画逻辑
                LaunchedEffect(backstack.size) {
                    // 当位移在屏幕外时（跳转触发），启动飞入动画
                    if (offsetX.value >= screenWidthPx * 0.9f) {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .draggable(
                            state = rememberDraggableState { delta ->
                                if (backstack.size > 1) {
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + delta).coerceIn(0f, screenWidthPx))
                                    }
                                }
                            },
                            orientation = Orientation.Horizontal,
                            onDragStopped = { velocity ->
                                scope.launch {
                                    if (offsetX.value > screenWidthPx * 0.4f || velocity > 1000) {
                                        offsetX.animateTo(
                                            targetValue = screenWidthPx,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ),
                                            initialVelocity = velocity
                                        )
                                        // 归零并移除，由于此时已经在屏幕外，归零不会被看到
                                        offsetX.snapTo(0f)
                                        backstack.removeLast()
                                    } else {
                                        offsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            ),
                                            initialVelocity = velocity
                                        )
                                    }
                                }
                            }
                        )
                ) {
                    // 1. 如果有上一页（底层渲染）
                    if (backstack.size >= 2 && offsetX.value > 0f) {
                        val bottomKey = backstack[backstack.size - 2]
                        SwipeablePageWrapper(offsetX = offsetX.value, isBottomPage = true) {
                            // 恢复使用简单的 JSON Key，不再加索引以保持状态复用稳定
                            stateHolder.SaveableStateProvider(Json.encodeToString(bottomKey)) {
                                RenderPage(bottomKey)
                            }
                        }
                    }

                    // 2. 渲染当前页（顶层渲染）
                    val topKey = backstack.last()
                    SwipeablePageWrapper(offsetX = offsetX.value, isBottomPage = false) {
                        stateHolder.SaveableStateProvider(Json.encodeToString(topKey)) {
                            RenderPage(topKey)
                        }
                    }
                }
            }
        }
    }
}