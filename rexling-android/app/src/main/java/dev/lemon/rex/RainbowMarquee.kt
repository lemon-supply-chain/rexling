package dev.lemon.rex

import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Marquee driver. Holds the phases as state so tile parameters stay equal and only the draw phase
 * is invalidated on every frame.
 */
internal class RainbowMarquee(
    private val hueState: State<Float>,
    private val glintState: State<Float>,
) {
    val hue: Float
        get() = hueState.value

    val glint: Float
        get() = glintState.value

    /** Soft white wash on the tile, breathing in step with the chase. */
    val light: Float
        get() {
            val breath = (sin(glint * TWO_PI) + 1f) * .5f
            return .1f + .07f * breath * breath
        }

    private val rainbowBrush by
        lazy(LazyThreadSafetyMode.NONE) { RotatingSweepBrush(RAINBOW_COLORS) }
    private val glintBrush by lazy(LazyThreadSafetyMode.NONE) { RotatingSweepBrush(GLINT_COLORS) }
    private val tileLightBrush by lazy(LazyThreadSafetyMode.NONE) { TileLightBrush() }

    internal fun prepareRainbow(degrees: Float, centerX: Float, centerY: Float): Brush {
        rainbowBrush.update(degrees, centerX, centerY)
        return rainbowBrush
    }

    internal fun prepareGlint(degrees: Float, centerX: Float, centerY: Float): Brush {
        glintBrush.update(degrees, centerX, centerY)
        return glintBrush
    }

    internal fun prepareTileLight(radius: Float, centerX: Float, centerY: Float): Brush {
        tileLightBrush.update(radius, centerX, centerY)
        return tileLightBrush
    }
}

private const val HUE_STEPS = 24
private const val GLINT_STEPS = 40
private const val GLINT_ARC = .055f
private const val TWO_PI = (2.0 * Math.PI).toFloat()
private val RingThickness = 1.1.dp

private val RAINBOW_COLORS: IntArray =
    IntArray(HUE_STEPS + 1) { index ->
        val hue = (index.toFloat() / HUE_STEPS) * 360f
        Color.hsv(hue, .92f, 1f).toArgb()
    }

private val GLINT_COLORS: IntArray =
    IntArray(GLINT_STEPS + 1) { index ->
        val t = index.toFloat() / GLINT_STEPS
        val gap = min(t, 1f - t)
        val distance = gap / GLINT_ARC
        val alpha = (exp(-distance * distance) * .95f).coerceIn(0f, 1f)
        Color.White.copy(alpha = alpha).toArgb()
    }

private val TILE_LIGHT_COLORS = intArrayOf(-0x1, 0x00ffffff)
private val TILE_LIGHT_STOPS = floatArrayOf(0f, 1f)

private class RotatingSweepBrush(colors: IntArray) : ShaderBrush() {
    private val shader = SweepGradient(0f, 0f, colors, null)
    private val matrix = Matrix()
    private var lastDegrees = Float.NaN
    private var lastCenterX = Float.NaN
    private var lastCenterY = Float.NaN

    fun update(degrees: Float, centerX: Float, centerY: Float) {
        if (degrees != lastDegrees || centerX != lastCenterX || centerY != lastCenterY) {
            matrix.setRotate(degrees)
            matrix.postTranslate(centerX, centerY)
            shader.setLocalMatrix(matrix)
            lastDegrees = degrees
            lastCenterX = centerX
            lastCenterY = centerY
        }
    }

    override fun createShader(size: Size): Shader = shader
}

private class TileLightBrush : ShaderBrush() {
    private val shader =
        RadialGradient(0f, 0f, 1f, TILE_LIGHT_COLORS, TILE_LIGHT_STOPS, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private var lastRadius = Float.NaN
    private var lastCenterX = Float.NaN
    private var lastCenterY = Float.NaN

    fun update(radius: Float, centerX: Float, centerY: Float) {
        if (radius != lastRadius || centerX != lastCenterX || centerY != lastCenterY) {
            matrix.setScale(radius, radius)
            matrix.postTranslate(centerX, centerY)
            shader.setLocalMatrix(matrix)
            lastRadius = radius
            lastCenterX = centerX
            lastCenterY = centerY
        }
    }

    override fun createShader(size: Size): Shader = shader
}

@Composable
internal fun rememberRainbowMarquee(enabled: Boolean): RainbowMarquee? {
    // The ring is decorative; the tab's working bar still carries the state.
    if (!enabled || !LocalMotionEnabled.current) return null
    val transition = rememberInfiniteTransition(label = "Rainbow marquee")
    val hue =
        transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(4200, easing = LinearEasing)),
            label = "Marquee hue",
        )
    val glint =
        transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(1700, easing = LinearEasing)),
            label = "Marquee glint",
        )
    return remember(transition) { RainbowMarquee(hue, glint) }
}

/**
 * Paints the rainbow ring on top of the content this modifier wraps. Place it before
 * `clip`/`background` in the chain so the ring is drawn unclipped.
 */
internal fun Modifier.rainbowMarquee(
    marquee: RainbowMarquee?,
    corner: Dp,
    thickness: Dp = RingThickness,
): Modifier =
    if (marquee == null) this
    else
        drawWithContent {
            drawContent()
            drawRainbowMarquee(marquee, corner.toPx(), thickness.toPx())
        }

/** Soft light wash for the tile artwork. Radial so it never paints the tile edge. */
internal fun DrawScope.drawTileLight(marquee: RainbowMarquee?) {
    if (marquee == null) return
    val radius = min(size.width, size.height) * .62f
    if (radius <= 0f) return
    val brush = marquee.prepareTileLight(radius, size.width / 2f, size.height / 2f)
    drawRect(brush, alpha = marquee.light)
}

internal fun DrawScope.drawRainbowMarquee(
    marquee: RainbowMarquee,
    cornerPx: Float,
    thicknessPx: Float,
) {
    val width = min(size.width, size.height)
    if (width <= 0f || thicknessPx <= 0f) return
    val radius = cornerPx.coerceIn(0f, width / 2f)
    val ringWidth = thicknessPx.coerceAtMost(width / 3f)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val rainbow = marquee.prepareRainbow((1f - marquee.hue) * 360f, cx, cy)
    // Two soft passes read as bloom without a saveLayer.
    strokeRing(radius, ringWidth * 3f, rainbow, .16f)
    strokeRing(radius, ringWidth * 1.9f, rainbow, .28f)
    strokeRing(radius, ringWidth, rainbow, 1f)
    val glint = marquee.prepareGlint(marquee.glint * 360f, cx, cy)
    strokeRing(radius, ringWidth, glint, 1f)
}

/**
 * Paints a compact breathing rainbow LED dot inside a square canvas without per-frame allocations.
 */
internal fun DrawScope.drawRainbowDot(marquee: RainbowMarquee) {
    val minDim = min(size.width, size.height)
    if (minDim <= 0f) return
    val breath = (sin(marquee.hue * 2f * TWO_PI) + 1f) * .5f
    val pulse = breath * breath
    val coreRadius = minDim * (.25f + .05f * pulse)
    val haloInner = (coreRadius * (1.20f + .10f * pulse)).coerceAtMost(minDim / 2f)
    val haloOuter = (coreRadius * (1.42f + .16f * pulse)).coerceAtMost(minDim / 2f)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val center = Offset(cx, cy)
    val rainbow = marquee.prepareRainbow((1f - marquee.hue) * 360f, cx, cy)
    // Two soft concentric passes create a breathing LED halo without saveLayer.
    drawCircle(rainbow, radius = haloOuter, center = center, alpha = .08f + .16f * pulse)
    drawCircle(rainbow, radius = haloInner, center = center, alpha = .14f + .20f * pulse)
    drawCircle(rainbow, radius = coreRadius, center = center, alpha = .72f + .28f * pulse)
    val coreLight = marquee.prepareTileLight(coreRadius * .72f, cx, cy)
    drawCircle(coreLight, radius = coreRadius * .72f, center = center, alpha = .18f + .24f * pulse)
}

private fun DrawScope.strokeRing(radiusPx: Float, widthPx: Float, brush: Brush, alpha: Float) {
    val w = size.width - widthPx
    val h = size.height - widthPx
    if (w <= 0f || h <= 0f) return
    val half = widthPx / 2f
    drawRoundRect(
        brush,
        Offset(half, half),
        Size(w, h),
        CornerRadius((radiusPx - half).coerceAtLeast(0f)),
        alpha = alpha,
        style = Stroke(widthPx),
    )
}
