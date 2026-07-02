package com.oneturn.scaffolddemo.decompose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal const val SWIPE_POP_DURATION_MS = 280
internal const val SWIPE_ENTER_DURATION_MS = 300
private const val POP_THRESHOLD_RATIO = 0.35f
private const val POP_VELOCITY_THRESHOLD = 900f

@Stable
class SwipeBackOffsetState(
    private val screenWidthPx: Float,
) {
    private val anim = Animatable(0f)
    private var gestureOffset by mutableFloatStateOf(0f)
    var isDragging by mutableStateOf(false)
        private set
    var isAnimating by mutableStateOf(false)
        private set

    var isEnterAnimating by mutableStateOf(false)
        private set

    val offset: Float
        get() = if (isDragging) gestureOffset else anim.value

    fun prepareEnterFromRight() {
        isDragging = true
        isAnimating = false
        isEnterAnimating = true
        gestureOffset = screenWidthPx
    }

    fun onDrag(delta: Float) {
        isDragging = true
        gestureOffset = (gestureOffset + delta).coerceIn(0f, screenWidthPx)
    }

    suspend fun cancelAndSnap() {
        anim.stop()
        isDragging = false
        isAnimating = false
        gestureOffset = anim.value
    }

    suspend fun snapTo(value: Float) {
        isDragging = false
        anim.snapTo(value)
        gestureOffset = value
    }

    suspend fun animateEnterToZero() {
        isEnterAnimating = true
        try {
            animateTo(0f, durationMs = SWIPE_ENTER_DURATION_MS)
        } finally {
            isEnterAnimating = false
        }
    }

    suspend fun animateTo(
        target: Float,
        durationMs: Int = SWIPE_POP_DURATION_MS,
        initialVelocity: Float = 0f,
        useSpring: Boolean = false,
    ) {
        val start = offset
        isDragging = false
        isAnimating = true
        try {
            anim.snapTo(start)
            val spec: AnimationSpec<Float> = if (useSpring) {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            } else {
                tween(durationMillis = durationMs, easing = FastOutSlowInEasing)
            }
            anim.animateTo(
                targetValue = target,
                animationSpec = spec,
                initialVelocity = initialVelocity,
            )
            gestureOffset = target
        } finally {
            isAnimating = false
        }
    }

    fun shouldPop(velocity: Float): Boolean =
        offset > screenWidthPx * POP_THRESHOLD_RATIO || velocity > POP_VELOCITY_THRESHOLD

    suspend fun runPopAnimation(initialVelocity: Float = 0f) {
        val start = offset
        isDragging = false
        isAnimating = true
        try {
            anim.snapTo(start)
            val fromDrag = initialVelocity != 0f || start > 1f
            val spec: AnimationSpec<Float> = if (fromDrag) {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            } else {
                tween(durationMillis = SWIPE_POP_DURATION_MS, easing = FastOutSlowInEasing)
            }
            anim.animateTo(
                targetValue = screenWidthPx,
                animationSpec = spec,
                initialVelocity = initialVelocity,
            )
            gestureOffset = screenWidthPx
        } finally {
            isAnimating = false
        }
    }
}
