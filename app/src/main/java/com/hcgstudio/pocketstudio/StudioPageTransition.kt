package com.hcgstudio.pocketstudio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun <T> StudioPageTransition(
    targetState: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val currentDepth = depth(targetState)
    var lastDepth by remember { mutableStateOf<Int?>(null) }

    // Dispose the old page immediately so only the visible page owns focus and back handlers.
    key(targetState) {
        val from = remember { lastDepth }
        val visible = remember { MutableTransitionState(from == null).apply { this.targetState = true } }
        val direction = from?.let { currentDepth.compareTo(it) } ?: 0
        val layoutDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
        val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
        val slide = if (direction == 0) EnterTransition.None else slideInHorizontally(tween(200)) {
            direction * layoutDirection * distance
        }
        AnimatedVisibility(
            visibleState = visible,
            modifier = modifier.clipToBounds(),
            enter = fadeIn(tween(160)) + slide,
            label = "Studio page"
        ) { Box { content(targetState) } }
    }
    SideEffect { lastDepth = currentDepth }
}
