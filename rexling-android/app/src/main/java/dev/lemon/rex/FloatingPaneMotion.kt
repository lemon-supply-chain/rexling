package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal class FloatingPaneMotion {
    var dockBounds by mutableStateOf<Rect?>(null)
    var workspaceOrigin by mutableStateOf(Offset.Zero)
    var restoring by mutableStateOf<String?>(null)
    var minimizing by mutableStateOf<FloatingPaneSnapshot?>(null)
        private set

    private val views = mutableMapOf<String, TerminalView>()
    private val viewports = mutableMapOf<String, Rect>()

    fun trackView(id: String, view: TerminalView) {
        views[id] = view
    }

    fun trackViewport(id: String, bounds: Rect) {
        viewports[id] = bounds
    }

    fun forget(id: String) {
        views.remove(id)
        viewports.remove(id)
    }

    fun capture(id: String) {
        minimizing = null
        if (dockBounds == null) return
        val view = views[id]?.takeIf { it.isAttachedToWindow && it.screen != null } ?: return
        val viewport = viewports[id] ?: return
        val at = IntArray(2).also(view::getLocationInWindow)
        val source =
            Rect(
                at[0].toFloat(),
                at[1].toFloat(),
                (at[0] + view.width).toFloat(),
                (at[1] + view.height).toFloat(),
            )
        val bounds = source.intersect(viewport)
        if (bounds.isEmpty) return
        // Keep one in-memory frame while the server switches away from the floating terminal.
        val scale = minOf(1f, 720f / maxOf(bounds.width, bounds.height))
        val bitmap =
            Bitmap.createBitmap(
                (bounds.width * scale).roundToInt().coerceAtLeast(1),
                (bounds.height * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(source.left - bounds.left, source.top - bounds.top)
        view.draw(canvas)
        minimizing = FloatingPaneSnapshot(id, bounds, bitmap.asImageBitmap())
    }

    fun finishMinimize(snapshot: FloatingPaneSnapshot) {
        if (minimizing === snapshot) minimizing = null
    }
}

internal class FloatingPaneSnapshot(val id: String, val bounds: Rect, val image: ImageBitmap)

@Composable
internal fun Modifier.floatingPaneViewport(id: String, motion: FloatingPaneMotion?): Modifier {
    if (motion == null) return this
    val restoring = motion.restoring == id && motion.dockBounds != null
    var viewport by remember(id, motion) { mutableStateOf(Rect.Zero) }
    val progress = remember(id, restoring) { Animatable(if (restoring) 0f else 1f) }
    LaunchedEffect(restoring) {
        if (restoring) {
            progress.animateTo(1f, tween(270, easing = FastOutSlowInEasing))
            if (motion.restoring == id) motion.restoring = null
        }
    }
    return onPlaced {
            val bounds = it.boundsInWindow()
            motion.trackViewport(id, bounds)
            if (restoring) viewport = bounds
        }
        .then(
            if (restoring)
                Modifier.graphicsLayer {
                    val dock = motion.dockBounds
                    if (dock != null && !viewport.isEmpty) {
                        towardsDock(progress.value, viewport.center, dock)
                    } else {
                        alpha = 0f
                    }
                }
            else Modifier,
        )
}

@Composable
internal fun MinimizingFloatingPane(
    motion: FloatingPaneMotion,
    confirmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshot = motion.minimizing ?: return
    val progress = remember(snapshot) { Animatable(1f) }
    LaunchedEffect(snapshot, confirmed) {
        if (confirmed) progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        else delay(3000)
        motion.finishMinimize(snapshot)
    }
    if (!confirmed) return
    val density = LocalDensity.current
    val shape = RoundedCornerShape(10.dp)
    Image(
        snapshot.image,
        null,
        modifier
            .offset {
                val position = snapshot.bounds.topLeft - motion.workspaceOrigin
                IntOffset(position.x.roundToInt(), position.y.roundToInt())
            }
            .size(
                with(density) { snapshot.bounds.width.toDp() },
                with(density) { snapshot.bounds.height.toDp() },
            )
            .graphicsLayer {
                clip = true
                this.shape = shape
                motion.dockBounds?.let { towardsDock(progress.value, snapshot.bounds.center, it) }
            }
            .testTag("Minimizing floating pane"),
    )
}

private fun GraphicsLayerScope.towardsDock(progress: Float, center: Offset, dock: Rect) {
    val remaining = 1f - progress
    translationX = (dock.center.x - center.x) * remaining
    translationY = (dock.center.y - center.y) * remaining
    scaleX = progress + remaining * (dock.width / size.width.coerceAtLeast(1f)).coerceAtMost(1f)
    scaleY = progress + remaining * (dock.height / size.height.coerceAtLeast(1f)).coerceAtMost(1f)
    alpha = (progress * 4f).coerceAtMost(1f)
}
