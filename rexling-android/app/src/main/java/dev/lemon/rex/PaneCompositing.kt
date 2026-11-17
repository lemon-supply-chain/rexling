package dev.lemon.rex

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color

/** Blend the opaque card toward its backdrop without group-alpha offscreen compositing. */
internal fun Modifier.paneScrim(backdrop: Color,amount: ()->Float)=drawWithContent {
    drawContent()
    val alpha=amount().coerceIn(0f,1f)
    if(alpha>0f) drawRect(backdrop.copy(alpha=alpha))
}
