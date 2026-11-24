package dev.lemon.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun ProgramStack(
    programs: List<ProgramIdentity>,
    selected: Int,
    framed: Boolean = false,
    fixedSlot: Boolean = false,
    highlighted: Boolean = false,
    marquee: RainbowMarquee? = null,
    isPaneWorking: (Int) -> Boolean = { it == selected },
    progress: TerminalProgress? = null,
) {
    val floatingShape = RoundedCornerShape(if (framed) 7.dp else 5.dp)
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    val tileHighlight =
        if (highlighted) Modifier.border(hairline, MaterialTheme.colorScheme.primary, floatingShape)
        else Modifier
    val items = programs.ifEmpty { listOf(ProgramIdentity.Terminal) }
    val front = selected.coerceIn(items.indices)
    val others = (1 until minOf(items.size, 3)).map { (front + it) % items.size }
    val active = items[front]
    // Background tiles keep the working treatment without owning per-frame shader work.
    val settledMarquee = remember { RainbowMarquee(mutableStateOf(0f), mutableStateOf(0f)) }
    val frontY = if (framed) 4.dp else 3.5.dp
    val step = if (framed) 6.dp else 4.dp
    val slotWidth = (if (framed) 40.dp else 28.dp) + step * if (fixedSlot) 2 else others.size
    val paintedWidth =
        if (others.isEmpty()) {
            if (framed) 34.dp else 22.dp
        } else (if (framed) 32.dp else 21.dp) + step * others.size
    val start = if (fixedSlot) (slotWidth - paintedWidth) / 2 else 0.dp
    Box(
        Modifier.size(slotWidth, if (framed) 35.dp else 25.dp).clearAndSetSemantics {
            contentDescription =
                active.label + (if (items.size > 1) ", ${items.size} panes" else "")
        },
    ) {
        for ((layer, index) in others.reversed().withIndex()) {
            val shift = step * (others.size - layer)
            PaneProgramTile(
                items[index],
                Modifier.offset(x = start + shift, y = frontY + 1.dp)
                    .size(if (framed) 32.dp else 21.dp, if (framed) 25.dp else 16.dp),
                framed,
                3f + (others.size - layer - 1) * 3f,
                marquee = if (isPaneWorking(index) && marquee != null) settledMarquee else null,
            )
        }
        PaneProgramTile(
            active,
            Modifier.offset(x = start, y = frontY)
                .size(if (framed) 34.dp else 22.dp, if (framed) 27.dp else 18.dp)
                .then(tileHighlight),
            framed,
            if (others.isNotEmpty()) -6f else 0f,
            marquee = if (isPaneWorking(front) || progress?.busy == true) marquee else null,
            progress = progress,
        )
    }
}

@Composable
internal fun PaneProgramTile(
    program: ProgramIdentity,
    modifier: Modifier,
    large: Boolean,
    rotation: Float,
    marquee: RainbowMarquee? = null,
    progress: TerminalProgress? = null,
) {
    if (program.pending) {
        val shape = RoundedCornerShape(if (large) 7.dp else 5.dp)
        Box(
            modifier
                .graphicsLayer { rotationZ = rotation }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f), shape),
        )
        return
    }
    val assets = LocalContext.current.assets
    val skin = remember(program, assets) { ProgramCatalog.skin(program, assets) }
    val catalog = remember(program, assets) { ProgramCatalog.tile(program, assets) }
    val shape = RoundedCornerShape(if (large) 7.dp else 5.dp)
    if (catalog != null) {
        val picture = remember(assets, catalog) { ProgramVectorPicture.load(assets, catalog) }
        // No clip here: the artwork carries its own rounded edge and must keep it.
        Canvas(
            modifier
                .graphicsLayer { rotationZ = rotation }
                .rainbowMarquee(marquee, if (large) 7.dp else 5.dp),
        ) {
            val canvas = drawContext.canvas.nativeCanvas
            picture.drawTile(canvas, size.width, size.height, 0f)
            drawTileLight(marquee)
        }
        return
    }
    val dark = LocalTerminalPalette.current.dark
    val colors =
        if (skin.neutral || skin.colors.isEmpty()) neutralColors(dark) else skin.colors.map(::Color)
    val ink = if (skin.neutral && !dark) Color(0xff323e50) else Color.White
    val radius = if (large) 7.dp else 5.dp
    val rimColors =
        if (colors.first().luminance() > .72f)
            listOf(
                Color.Black.copy(alpha = .12f),
                Color.Black.copy(alpha = .055f),
                Color.Transparent,
            )
        else
            listOf(
                Color.White.copy(alpha = .40f),
                Color.White.copy(alpha = .12f),
                Color.Transparent,
            )
    Box(
        modifier
            .graphicsLayer { rotationZ = rotation }
            .rainbowMarquee(marquee, radius)
            .clip(shape)
            .background(Brush.verticalGradient(colors))
            .drawWithContent {
                drawContent()
                drawTileLight(marquee)
                val stroke = 1f.toDp().toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    Brush.verticalGradient(rimColors),
                    Offset(inset, inset),
                    Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f),
                    ),
                    CornerRadius((radius.toPx() - inset).coerceAtLeast(0f)),
                    style = Stroke(stroke),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // The dial is a live progress visualization; surfaces without progress
        // data (activity rows, switchers, docks) keep the program's own icon.
        if (progress != null && (progress.percent != null || progress.busy)) {
            CountdownIcon(progress, Modifier.size(if (large) 19.dp else 13.dp), ink)
        } else {
            ProgramIcon(program, Modifier.size(if (large) 19.dp else 13.dp), ink)
        }
    }
}

private fun neutralColors(dark: Boolean): List<Color> =
    if (dark) listOf(0xff65788f, 0xff3f5067, 0xff253347).map(::Color)
    else listOf(0xffffffff, 0xfff0f3f7, 0xffd3dbe6).map(::Color)
