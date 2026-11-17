package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider

private val bubbleTail=8.dp

/** One outline keeps the pointer, border and shadow joined without a seam. */
private class SpeechBubbleShape(val tip: Float,val above: Boolean): Shape {
    override fun createOutline(size: Size,layoutDirection: LayoutDirection,density: Density): Outline {
        val tail=with(density) {bubbleTail.toPx()}.coerceAtMost(size.height/2)
        val top=if(above) 0f else tail
        val bottom=if(above) size.height-tail else size.height
        val r=minOf(with(density) {18.dp.toPx()},size.width/2,(bottom-top)/2)
        val half=with(density) {9.dp.toPx()}.coerceAtMost((size.width-2*r)/2)
        val x=tip.coerceIn(r+half,size.width-r-half)
        return Outline.Generic(Path().apply {
            moveTo(r,top)
            if(!above) {lineTo(x-half,top);lineTo(x,0f);lineTo(x+half,top)}
            lineTo(size.width-r,top);quadraticTo(size.width,top,size.width,top+r)
            lineTo(size.width,bottom-r);quadraticTo(size.width,bottom,size.width-r,bottom)
            if(above) {lineTo(x+half,bottom);lineTo(x,size.height);lineTo(x-half,bottom)}
            lineTo(r,bottom);quadraticTo(0f,bottom,0f,bottom-r)
            lineTo(0f,top+r);quadraticTo(0f,top,r,top);close()
        })
    }
}

internal class SpeechBubblePositioner(private val edge: Int,private val tail: Int,private val onBodyBounds: (IntRect)->Unit): PopupPositionProvider {
    var tip by mutableFloatStateOf(0f);private set
    var above by mutableStateOf(true);private set
    override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize): IntOffset {
        val x=(anchorBounds.center.x-popupContentSize.width/2).coerceIn(edge,maxOf(edge,windowSize.width-popupContentSize.width-edge))
        val upper=anchorBounds.top-popupContentSize.height-edge
        above=upper>=edge
        val y=(if(above) upper else anchorBounds.bottom+edge).coerceIn(edge,maxOf(edge,windowSize.height-popupContentSize.height-edge))
        tip=(anchorBounds.center.x-x).toFloat()
        // Ctrl's slide-to-select geometry must exclude the decorative pointer.
        onBodyBounds(IntRect(x,y+if(above) 0 else tail,x+popupContentSize.width,y+popupContentSize.height-if(above) tail else 0))
        return IntOffset(x,y)
    }
}

@Composable internal fun rememberSpeechBubblePositioner(onBodyBounds: (IntRect)->Unit={}): SpeechBubblePositioner {
    val latestBounds by rememberUpdatedState(onBodyBounds)
    val density=LocalDensity.current
    val edge=with(density) {8.dp.roundToPx()}
    val tail=with(density) {bubbleTail.roundToPx()}
    return remember(edge,tail) {SpeechBubblePositioner(edge,tail) {latestBounds(it)}}
}

@Composable internal fun SpeechBubble(positioner: SpeechBubblePositioner,modifier: Modifier=Modifier,shadowElevation: Dp=10.dp,content: @Composable ()->Unit) {
    val shape=remember(positioner.tip,positioner.above) {SpeechBubbleShape(positioner.tip,positioner.above)}
    Box(modifier.shadow(shadowElevation,shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp,MaterialTheme.colorScheme.outline,shape)
        .padding(top=if(positioner.above) 0.dp else bubbleTail,bottom=if(positioner.above) bubbleTail else 0.dp)) {content()}
}
