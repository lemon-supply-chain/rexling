package dev.lemon.rex

import android.content.res.AssetManager
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal fun programStackPaintedWidth(count: Int, framed: Boolean = false): Dp {
    val backTiles = count.coerceIn(1, 3) - 1
    return if (backTiles == 0) {
        if (framed) 34.dp else 22.dp
    } else {
        (if (framed) 32.dp else 21.dp) + (if (framed) 6.dp else 4.dp) * backTiles
    }
}

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
    val paintedWidth = programStackPaintedWidth(items.size, framed)
    val start = if (fixedSlot) (slotWidth - paintedWidth) / 2 else 0.dp
    val paneCount = pluralStringResource(R.plurals.pane_count, items.size, items.size)
    Box(
        Modifier.size(slotWidth, if (framed) 35.dp else 25.dp).clearAndSetSemantics {
            contentDescription = active.label + (if (items.size > 1) ", $paneCount" else "")
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
    val dark = LocalTerminalPalette.current.dark
    val skin = remember(program, assets) { ProgramCatalog.skin(program, assets) }
    val catalog = remember(program, assets) { ProgramCatalog.tile(program, assets) }
    val showCountdown = progress != null && (progress.percent != null || progress.busy)
    if (!showCountdown && catalog != null) {
        val picture = remember(assets, catalog) { ProgramVectorPicture.load(assets, catalog) }
        // No clip here: the artwork carries its own rounded edge and must keep it.
        Canvas(
            modifier
                .graphicsLayer { rotationZ = rotation }
                .rainbowMarquee(marquee, if (large) 7.dp else 5.dp),
        ) {
            val canvas = drawContext.canvas.nativeCanvas
            picture.drawTile(canvas, size.width, size.height, 0f, dark = dark)
            drawTileLight(marquee)
        }
        return
    }
    val colors =
        when {
            showCountdown -> countdownColors
            skin.neutral || skin.colors.isEmpty() -> neutralColors(dark)
            else -> skin.colors.map(::Color)
        }
    val ink =
        when {
            showCountdown -> Color.White
            skin.neutral && !dark -> Color(0xff323e50)
            else -> Color.White
        }
    val radius = if (large) 7.dp else 5.dp
    val fillInts = remember(colors) { colors.map { it.toArgb() }.toIntArray() }
    val chrome =
        remember(assets, fillInts) {
            SkeuomorphicCardChrome.forAccent(assets, fillInts.firstOrNull() ?: 0)
        }
    val fillPaint = remember(fillInts) { SkeuomorphicCardChrome.plateFill(fillInts) }
    Box(
        modifier.graphicsLayer { rotationZ = rotation }.rainbowMarquee(marquee, radius),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val native = drawContext.canvas.nativeCanvas
            if (chrome != null) {
                chrome.drawCard(native, size.width, size.height, fillPaint, dark = dark)
            } else {
                // A catalog without a parseable plate still gets the rounded tile, not a square.
                drawRoundRect(
                    colors.firstOrNull() ?: Color.Gray,
                    cornerRadius = CornerRadius(radius.toPx()),
                )
            }
            drawTileLight(marquee)
        }
        // The dial is a live progress visualization; surfaces without progress
        // data (activity rows, switchers, docks) keep the program's own icon.
        if (showCountdown) {
            CountdownIcon(progress, Modifier.size(if (large) 19.dp else 13.dp), ink)
        } else {
            ProgramIcon(program, Modifier.size(if (large) 19.dp else 13.dp), ink)
        }
    }
}

private val countdownColors = listOf(Color(0xff242220), Color.Black, Color.Black)

private fun neutralColors(dark: Boolean): List<Color> =
    if (dark) listOf(0xff65788f, 0xff3f5067, 0xff253347).map(::Color)
    else listOf(0xffffffff, 0xfff0f3f7, 0xffd3dbe6).map(::Color)

/**
 * Rounded contour approximating the card tile's outer rim in 61x48 artboard coordinates so
 * elevation shadows match the rendered card silhouette on API 28+.
 */
internal val ProgramTileShadowShape: Shape =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline {
            if (size.width <= 0f || size.height <= 0f) {
                return Outline.Rectangle(Rect.Zero)
            }
            val sx = size.width / SkeuomorphicCardChrome.ARTBOARD_WIDTH
            val sy = size.height / SkeuomorphicCardChrome.ARTBOARD_HEIGHT
            val insetX = SkeuomorphicCardChrome.OUTER_RIM_INSET * sx
            val insetY = SkeuomorphicCardChrome.OUTER_RIM_INSET * sy
            val left = insetX
            val top = insetY
            val right = (size.width - insetX).coerceAtLeast(left)
            val bottom = (size.height - insetY).coerceAtLeast(top)
            return Outline.Rounded(
                RoundRect(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    cornerRadius =
                        CornerRadius(
                            SkeuomorphicCardChrome.OUTER_RIM_CORNER_RADIUS * sx,
                            SkeuomorphicCardChrome.OUTER_RIM_CORNER_RADIUS * sy,
                        ),
                ),
            )
        }
    }

/**
 * Strokes the exact scaled squircle plate contour from [SkeuomorphicCardChrome.scaledPlatePath]
 * over the tile content, falling back to an inset rounded rectangle if the catalog plate is
 * unavailable.
 */
internal fun Modifier.programTileBorder(
    assets: AssetManager,
    color: Color,
    width: Dp,
    fallbackCornerRadius: Dp = 7.dp,
): Modifier = drawWithCache {
    val strokePx = width.toPx()
    val stroke = Stroke(width = strokePx, join = StrokeJoin.Round, cap = StrokeCap.Round)
    val platePath =
        SkeuomorphicCardChrome.scaledPlatePath(assets, size.width, size.height)?.asComposePath()
    val fallbackRadiusPx = fallbackCornerRadius.toPx()
    onDrawWithContent {
        drawContent()
        if (size.width <= 0f || size.height <= 0f) return@onDrawWithContent
        if (platePath != null) {
            drawPath(path = platePath, color = color, style = stroke)
        } else {
            val half = strokePx / 2f
            val w = (size.width - strokePx).coerceAtLeast(0f)
            val h = (size.height - strokePx).coerceAtLeast(0f)
            drawRoundRect(
                color = color,
                topLeft = Offset(half, half),
                size = Size(w, h),
                cornerRadius = CornerRadius((fallbackRadiusPx - half).coerceAtLeast(0f)),
                style = stroke,
            )
        }
    }
}
