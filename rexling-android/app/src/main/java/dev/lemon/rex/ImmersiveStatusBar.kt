package dev.lemon.rex

import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntRect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal val LocalImmersiveStatusBar = staticCompositionLocalOf { false }

/** Retain system window decorations in freeform mode and any curved display edge. */
@Composable
internal fun immersiveTopInsets() =
    WindowInsets.captionBar.union(WindowInsets.waterfall).only(WindowInsetsSides.Top)

/** Bounds in the workspace, after its horizontal safety and top window decoration insets. */
@Composable
internal fun workspaceTopCutout(): IntRect? {
    val density = LocalDensity.current
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val reservedTop = immersiveTopInsets().getTop(density)
    if (cutoutTop <= reservedTop) return null
    val cutout = ViewCompat.getRootWindowInsets(LocalView.current)?.displayCutout ?: return null
    val bounds = cutout.boundingRects.filter { it.bottom in 1..cutoutTop }
    if (bounds.isEmpty()) return null
    val reservedLeft = WindowInsets.safeDrawing.getLeft(density, LocalLayoutDirection.current)
    return IntRect(
        bounds.minOf { it.left } - reservedLeft,
        0,
        bounds.maxOf { it.right } - reservedLeft,
        cutoutTop - reservedTop,
    )
}

internal fun Window.applyImmersiveStatusBar(immersive: Boolean) {
    val statusBar = WindowInsetsCompat.Type.statusBars()
    val controller = WindowCompat.getInsetsController(this, decorView)
    if (immersive) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(statusBar)
    } else {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.show(statusBar)
    }
}
