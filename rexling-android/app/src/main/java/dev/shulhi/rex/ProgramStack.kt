package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable internal fun ProgramStack(programs: List<ProgramIdentity>, selected: Int, framed: Boolean=false, fixedSlot: Boolean=false,floatingShadow: Boolean=false,highlighted: Boolean=false) {
    val floatingShape=RoundedCornerShape(5.dp)
    val tileShadow=if(floatingShadow) Modifier.shadow(4.dp,floatingShape,clip=false) else Modifier
    val tileHighlight=if(highlighted) Modifier.border(1.dp,MaterialTheme.colorScheme.primary,floatingShape) else Modifier
    val items=programs.ifEmpty {listOf(ProgramIdentity.Terminal)}
    val front=selected.coerceIn(items.indices)
    val others=(1 until minOf(items.size,3)).map {(front+it)%items.size}
    val active=items[front]
    val frontY=if(framed) 4.dp else 3.5.dp
    val step=if(framed) 6.dp else 4.dp
    val slotWidth=(if(framed) 40.dp else 28.dp)+step*if(fixedSlot) 2 else others.size
    val paintedWidth=if(others.isEmpty()) {if(framed) 34.dp else 22.dp} else (if(framed) 32.dp else 21.dp)+step*others.size
    val start=if(fixedSlot) (slotWidth-paintedWidth)/2 else 0.dp
    Box(Modifier.size(slotWidth,if(framed) 35.dp else 25.dp)
        .clearAndSetSemantics {contentDescription=active.label+(if(items.size>1) ", ${items.size} panes" else "")}) {
        for((layer,index) in others.reversed().withIndex()) {
            val shift=step*(others.size-layer)
            PaneProgramTile(items[index],Modifier.offset(x=start+shift,y=frontY+1.dp)
                .size(if(framed) 32.dp else 21.dp,if(framed) 25.dp else 16.dp).then(tileShadow),framed,3f+(others.size-layer-1)*3f)
        }
        PaneProgramTile(active,Modifier.offset(x=start,y=frontY)
            .size(if(framed) 34.dp else 22.dp,if(framed) 27.dp else 18.dp).then(tileShadow).then(tileHighlight),framed,if(others.isNotEmpty()) -6f else 0f)
    }
}

@Composable internal fun PaneProgramTile(program: ProgramIdentity, modifier: Modifier, large: Boolean,rotation: Float) {
    if(program.pending) {
        val shape=RoundedCornerShape(if(large) 7.dp else 5.dp)
        Box(modifier.graphicsLayer {rotationZ=rotation}.clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.5f),shape))
        return
    }
    val assets=LocalContext.current.assets
    val skin=ProgramCatalog.skin(program,assets)
    val catalog=ProgramCatalog.tile(program,assets)
    if(catalog!=null) {
        val picture=remember(assets,catalog) {ProgramVectorPicture.load(assets,catalog)}
        Canvas(modifier) {picture.draw(drawContext.canvas.nativeCanvas,size.width,size.height,rotation)}
        return
    }
    val dark=LocalTerminalPalette.current.dark
    val colors=if(skin.neutral || skin.colors.isEmpty()) neutralColors(dark) else skin.colors.map(::Color)
    val ink=if(skin.neutral && !dark) Color(0xff323e50) else Color.White
    val radius=if(large) 7.dp else 5.dp
    val shape=RoundedCornerShape(radius)
    val reflection=Brush.verticalGradient(0f to Color.White.copy(alpha=.34f),.22f to Color.White.copy(alpha=.08f),
        .5f to Color.Transparent,1f to Color.Black.copy(alpha=.06f))
    Box(modifier.graphicsLayer {rotationZ=rotation}.shadow(if(large) 3.dp else 1.dp,shape).clip(shape)
        .background(Brush.verticalGradient(colors)).background(reflection)
        .border(1.dp,Color.White.copy(alpha=if(dark) .85f else 1f),shape)
        .drawWithContent {
            drawContent()
            val inset=1.5.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha=.65f),Color.White.copy(alpha=.05f))),
                Offset(inset,inset),Size((size.width-inset*2).coerceAtLeast(0f),(size.height-inset*2).coerceAtLeast(0f)),
                CornerRadius((radius.toPx()-inset).coerceAtLeast(0f)),style=Stroke(.65.dp.toPx()))
        },contentAlignment=Alignment.Center) {
        ProgramIcon(program,Modifier.size(if(large) 19.dp else 13.dp),ink)
    }
}

private fun neutralColors(dark: Boolean): List<Color> =
    if(dark) listOf(0xff65788f,0xff3f5067,0xff253347).map(::Color)
    else listOf(0xffffffff,0xfff0f3f7,0xffd3dbe6).map(::Color)
