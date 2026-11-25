package dev.lemon.rex

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ProgramIcon(
    program: ProgramIdentity,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val assets = LocalContext.current.assets
    val tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val glyph = remember(program, assets) { ProgramCatalog.glyph(program, assets) }
    val picture =
        remember(assets, glyph, tint) {
            glyph?.let { ProgramVectorPicture.loadGlyph(assets, it, tint.toArgb()) }
        }
    Canvas(modifier.semantics { contentDescription = program.label }) {
        if (picture != null) {
            picture.draw(drawContext.canvas.nativeCanvas, size.width, size.height)
        } else {
            val u = size.width / 24f
            fun line(x: Float, y: Float, x2: Float, y2: Float) =
                drawLine(
                    tint,
                    Offset(x * u, y * u),
                    Offset(x2 * u, y2 * u),
                    1.8f * u,
                    StrokeCap.Round,
                )
            line(3f, 5f, 9f, 11f)
            line(9f, 11f, 3f, 17f)
            line(12f, 18f, 21f, 18f)
        }
    }
}

@Composable
internal fun CountdownIcon(
    progress: TerminalProgress?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val progressColor = terminalProgressColor(progress)
    val percent = progress?.percent

    val spin =
        if (percent == null && progress?.busy == true && LocalMotionEnabled.current) {
            val transition = rememberInfiniteTransition(label = "countdown_indeterminate")
            val angle by
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    label = "spin",
                )
            angle
        } else 0f

    // TerminalProgress.description is English-only; the dial is announced through the same
    // localized copy the activity rows use.
    val countdownDescription =
        if (progress == null) stringResource(R.string.a11y_countdown)
        else terminalProgressDescription(progress)
    Canvas(modifier.semantics { contentDescription = countdownDescription }) {
        val u = size.minDimension / 24f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = 8.5f * u
        val strokeW = 1.6f * u

        // Outer dial track
        drawCircle(
            tint.copy(alpha = 0.22f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(strokeW),
        )

        if (percent != null) {
            val sweep = (percent / 100f).coerceIn(0f, 1f) * 360f
            if (sweep > 0f) {
                drawCircle(
                    progressColor.copy(alpha = 0.12f),
                    radius = r - strokeW * 0.5f,
                    center = Offset(cx, cy),
                )
                val pad = strokeW * 0.4f
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = Offset(cx - r + pad, cy - r + pad),
                    size = Size((r - pad) * 2f, (r - pad) * 2f),
                )
            }
            val angleRad = Math.toRadians((-90f + sweep).toDouble())
            val needleLen = r * 0.82f
            val nx = cx + (needleLen * cos(angleRad)).toFloat()
            val ny = cy + (needleLen * sin(angleRad)).toFloat()
            drawLine(tint, Offset(cx, cy), Offset(nx, ny), 1.4f * u, StrokeCap.Round)
        } else if (progress?.busy == true) {
            val pad = strokeW * 0.4f
            drawArc(
                color = progressColor,
                startAngle = -90f + spin,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(cx - r + pad, cy - r + pad),
                size = Size((r - pad) * 2f, (r - pad) * 2f),
            )
        } else {
            // Idle needle at 12 o'clock
            drawLine(tint, Offset(cx, cy), Offset(cx, cy - r * 0.82f), 1.4f * u, StrokeCap.Round)
        }

        // Center pivot dot
        drawCircle(tint, radius = 1.4f * u, center = Offset(cx, cy))
    }
}
