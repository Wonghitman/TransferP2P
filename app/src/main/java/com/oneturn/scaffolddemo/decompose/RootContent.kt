package com.oneturn.scaffolddemo.decompose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.Child
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.oneturn.scaffolddemo.components.ChatScreen
import com.oneturn.scaffolddemo.components.ScreenA
import com.oneturn.scaffolddemo.components.ScreenB
import com.oneturn.scaffolddemo.components.SwipeablePageWrapper
import com.oneturn.scaffolddemo.components.ToastTestScreen
import kotlinx.coroutines.launch

@Composable
fun RootContent(
    component: RootComponent,
    modifier: Modifier = Modifier,
) {
    val childStack by component.stack.subscribeAsState()
    val active = childStack.active
    val underneath = childStack.backStack.lastOrNull()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val swipeOffset = remember(screenWidthPx) { SwipeBackOffsetState(screenWidthPx) }
    val stateHolder = rememberSaveableStateHolder()

    var exitingChild by remember {
        mutableStateOf<Child.Created<*, RootComponent.Child>?>(null)
    }

    val stackDepth = childStack.backStack.size + 1
    var prevStackDepth by remember { mutableIntStateOf(stackDepth) }
    var enterPreparedDepth by remember { mutableIntStateOf(stackDepth) }

    // 必须在读取 offsetX 之前同步把新页放到屏外，且每个深度只执行一次
    if (stackDepth > prevStackDepth && prevStackDepth > 0 && enterPreparedDepth != stackDepth) {
        swipeOffset.prepareEnterFromRight()
        enterPreparedDepth = stackDepth
    }

    val offsetX = swipeOffset.offset
    val isEnterAnimating = swipeOffset.isEnterAnimating
    val canSwipeBack = childStack.backStack.isNotEmpty()

    LaunchedEffect(stackDepth) {
        val previous = prevStackDepth
        prevStackDepth = stackDepth
        if (stackDepth > previous && previous > 0) {
            swipeOffset.animateEnterToZero()
        }
    }

    suspend fun finishPop(exiting: Child.Created<*, RootComponent.Child>) {
        exitingChild = exiting
        component.onBackClicked()
        swipeOffset.snapTo(0f)
        withFrameMillis { }
        exitingChild = null
    }

    fun popBack(initialVelocity: Float = 0f) {
        scope.launch {
            val stack = component.stack.value
            if (stack.backStack.isEmpty()) return@launch
            val exiting = stack.active
            swipeOffset.cancelAndSnap()
            swipeOffset.runPopAnimation(initialVelocity)
            finishPop(exiting)
        }
    }

    BackHandler(enabled = canSwipeBack) {
        popBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .then(
                if (canSwipeBack) {
                    Modifier.draggable(
                        state = rememberDraggableState { delta ->
                            swipeOffset.onDrag(delta)
                        },
                        orientation = Orientation.Horizontal,
                        onDragStopped = { velocity ->
                            scope.launch {
                                val stack = component.stack.value
                                if (stack.backStack.isEmpty()) return@launch
                                if (swipeOffset.shouldPop(velocity)) {
                                    val exiting = stack.active
                                    swipeOffset.runPopAnimation(initialVelocity = velocity)
                                    finishPop(exiting)
                                } else {
                                    swipeOffset.animateTo(
                                        target = 0f,
                                        initialVelocity = velocity,
                                        useSpring = true,
                                    )
                                }
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        val stackLayers = buildList {
            underneath?.let(::add)
            add(active)
        }

        stackLayers.forEachIndexed { index, child ->
            val isTop = child.key == active.key
            val layerOffsetX = when {
                isTop -> offsetX
                isEnterAnimating -> 0f
                else -> offsetX
            }
            key(child.key) {
                SwipeablePageWrapper(
                    modifier = Modifier.zIndex(index.toFloat()),
                    offsetX = layerOffsetX,
                    screenWidthPx = screenWidthPx,
                    isBottomPage = !isTop,
                ) {
                    stateHolder.SaveableStateProvider(child.key) {
                        ChildContent(child)
                    }
                }
            }
        }

        exitingChild?.let { exiting ->
            key("exiting-${exiting.key}") {
                SwipeablePageWrapper(
                    modifier = Modifier.zIndex(100f),
                    offsetX = screenWidthPx,
                    screenWidthPx = screenWidthPx,
                    isBottomPage = false,
                ) {
                    ChildContent(exiting)
                }
            }
        }
    }
}

@Composable
private fun ChildContent(child: Child.Created<*, RootComponent.Child>) {
    when (val instance = child.instance) {
        is RootComponent.Child.ToastTest -> ToastTestScreen(
            onNavigateToChat = instance.component::onNavigateToScreenA,
        )

        is RootComponent.Child.ScreenA -> ScreenA(
            onNavigateToB = instance.component::onNavigateToScreenB,
        )

        is RootComponent.Child.ScreenB -> ScreenB()
        is RootComponent.Child.Chat -> ChatScreen()
    }
}
