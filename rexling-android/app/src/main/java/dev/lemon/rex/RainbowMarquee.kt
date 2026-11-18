package dev.lemon.rex

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Marquee driver. Holds the phases as state so tile parameters stay equal and
 * only the draw phase is invalidated on every frame.
 */
internal class RainbowMarquee(private val hueState: State<Float>,private val glintState: State<Float>) {
    val hue: Float get()=hueState.value
    val glint: Float get()=glintState.value
    /** Soft white wash on the tile, breathing in step with the chase. */
    val light: Float get() {
        val breath=(sin(glint*2f*PI.toFloat())+1f)*.5f
        return .1f+.07f*breath*breath
    }
}

private const val HUE_STEPS = 24
private const val GLINT_STEPS = 40
private const val GLINT_ARC = .055f
private val RingThickness = 1.1.dp

@Composable internal fun rememberRainbowMarquee(enabled: Boolean): RainbowMarquee? {
    // The ring is decorative; the tab's working bar still carries the state.
    if(!enabled || !LocalMotionEnabled.current) return null
    val transition=rememberInfiniteTransition(label="Rainbow marquee")
    val hue=transition.animateFloat(0f,1f,infiniteRepeatable(tween(4200,easing=LinearEasing)),label="Marquee hue")
    val glint=transition.animateFloat(0f,1f,infiniteRepeatable(tween(1700,easing=LinearEasing)),label="Marquee glint")
    return remember(transition) {RainbowMarquee(hue,glint)}
}

/**
 * Paints the rainbow ring on top of the content this modifier wraps. Place it
 * before `clip`/`background` in the chain so the ring is drawn unclipped.
 */
internal fun Modifier.rainbowMarquee(marquee: RainbowMarquee?,corner: Dp,thickness: Dp = RingThickness): Modifier =
    if(marquee==null) this else drawWithContent {
        drawContent()
        drawRainbowMarquee(marquee,corner.toPx(),thickness.toPx())
    }

/** Soft light wash for the tile artwork. Radial so it never paints the tile edge. */
internal fun DrawScope.drawTileLight(marquee: RainbowMarquee?) {
    if(marquee==null) return
    val radius=min(size.width,size.height)*.62f
    if(radius<=0f) return
    drawRect(Brush.radialGradient(0f to Color.White.copy(alpha=marquee.light),1f to Color.Transparent,
        center=Offset(size.width/2f,size.height/2f),radius=radius))
}

internal fun DrawScope.drawRainbowMarquee(marquee: RainbowMarquee,cornerPx: Float,thicknessPx: Float) {
    val width=min(size.width,size.height)
    if(width<=0f || thicknessPx<=0f) return
    val radius=cornerPx.coerceIn(0f,width/2f)
    val ringWidth=thicknessPx.coerceAtMost(width/3f)
    val center=Offset(size.width/2f,size.height/2f)
    val hue=marquee.hue
    // Two soft passes read as bloom without a saveLayer.
    strokeRing(radius,ringWidth*3f,rainbowBrush(hue,center,.16f))
    strokeRing(radius,ringWidth*1.9f,rainbowBrush(hue,center,.28f))
    strokeRing(radius,ringWidth,rainbowBrush(hue,center,1f))
    strokeRing(radius,ringWidth,glintBrush(marquee.glint,center))
}

private fun DrawScope.strokeRing(radiusPx: Float,widthPx: Float,brush: Brush) {
    val w=size.width-widthPx
    val h=size.height-widthPx
    if(w<=0f || h<=0f) return
    val half=widthPx/2f
    drawRoundRect(brush,Offset(half,half),Size(w,h),CornerRadius((radiusPx-half).coerceAtLeast(0f)),style=Stroke(widthPx))
}

private fun rainbowBrush(phase: Float,center: Offset,alpha: Float): Brush = Brush.sweepGradient(List(HUE_STEPS+1) {index->
    val hue=((index.toFloat()/HUE_STEPS+phase)%1f)*360f
    Color.hsv(hue,.92f,1f).copy(alpha=alpha)
},center)

/** White comet head that chases the ring one lap at a time. */
private fun glintBrush(phase: Float,center: Offset): Brush = Brush.sweepGradient(List(GLINT_STEPS+1) {index->
    val t=index.toFloat()/GLINT_STEPS
    val gap=abs(t-phase)
    val distance=min(gap,1f-gap)/GLINT_ARC
    Color.White.copy(alpha=(exp(-distance*distance)*.95f).coerceIn(0f,1f))
},center)