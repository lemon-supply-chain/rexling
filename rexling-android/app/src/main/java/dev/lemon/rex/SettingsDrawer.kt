package dev.lemon.rex

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState

internal class DrawerPull(
    val start: () -> Unit,
    val move: (Float) -> Unit,
    val finish: (Boolean) -> Unit,
)

internal val LocalDrawerPull = staticCompositionLocalOf<DrawerPull?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawerSheet(
    onClose: () -> Unit,
    animated: Boolean = true,
    dismissOnBack: Boolean = false,
    content: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    var closing by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(!animated) }
    var returning by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var drawerHeight by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(closing) {
        if (animated || closing)
            progress.animateTo(if (closing) 0f else 1f, tween(if (closing) 160 else 220))
        if (closing) close() else entered = true
    }
    LaunchedEffect(returning) {
        if (returning) {
            animate(dragOffset, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 650f)) {
                value,
                _ ->
                if (returning) dragOffset = value
            }
            returning = false
        }
    }
    val dismiss: () -> Unit = {
        returning = false
        closing = true
    }
    val threshold = with(LocalDensity.current) { 52.dp.toPx() }
    val pull =
        DrawerPull(
            start = { returning = false },
            move = { delta ->
                if (entered && !closing)
                    dragOffset = (dragOffset + delta).coerceIn(0f, drawerHeight.toFloat())
            },
            finish = { released ->
                if (
                    released &&
                        dragOffset >= minOf(threshold, drawerHeight * .3f) &&
                        dragOffset > 0f
                )
                    dismiss()
                else if (!closing && dragOffset > 0f) returning = true
            },
        )
    val scrim = BottomSheetDefaults.ScrimColor
    val immersive = LocalImmersiveStatusBar.current
    Dialog(
        onDismissRequest = { if (dismissOnBack) dismiss() else onClose() },
        properties =
            DialogProperties(
                dismissOnBackPress = dismissOnBack,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        LaunchedEffect(window, immersive) { window?.applyImmersiveStatusBar(immersive) }
        Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    val shown = (1f - dragOffset / drawerHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
                    // alpha-ok sheet scrim fade
                    drawRect(scrim.copy(alpha = scrim.alpha * progress.value * shown))
                }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .imePadding(),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalDrawerPull provides pull,
            ) {
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                        .fillMaxWidth()
                        .onSizeChanged { drawerHeight = it.height }
                        .graphicsLayer {
                            translationY =
                                size.height -
                                    progress.value *
                                        (size.height - dragOffset.coerceAtMost(size.height))
                        },
                ) {
                    content(dismiss)
                }
            }
        }
    }
}

@Composable
internal fun DrawerHandle(
    onDismiss: (() -> Unit)? = null,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dismissDrawer by rememberUpdatedState(onDismiss)
    val pull by rememberUpdatedState(LocalDrawerPull.current)
    val handleLabel = stringResource(R.string.a11y_drawer_handle)
    val motion = LocalMotionEnabled.current
    val color by
        animateColorAsState(
            if (active) MaterialTheme.colorScheme.roles.action
            else MaterialTheme.colorScheme.outlineVariant,
            if (motion) RexTokens.motion.fastEffects() else snap(),
            label = "DrawerHandle color",
        )
    val width by
        animateDpAsState(
            if (active) 36.dp else 28.dp,
            if (motion) RexTokens.motion.fastSpatial() else snap(),
            label = "DrawerHandle width",
        )
    Box(modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(80.dp)
                .fillMaxHeight()
                .semantics {
                    contentDescription = handleLabel
                    dismissDrawer?.let { handler ->
                        dismiss {
                            handler()
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { pull?.start?.invoke() },
                        onDragEnd = { pull?.finish?.invoke(true) },
                        onDragCancel = { pull?.finish?.invoke(false) },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            pull?.move?.invoke(amount)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(width).height(3.dp).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

/** Shared drawer chrome; scrolling stays in the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDrawer(
    title: String,
    closeDescription: String,
    onClose: () -> Unit,
    hazeState: HazeState? = null,
    onBack: (() -> Unit)? = null,
    fixed: Boolean = false,
    backDescription: String = stringResource(R.string.back_to_appearance),
    subtitle: String? = null,
    surfaceColor: Color = Color.Transparent,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val localHaze = remember { HazeState() }
    val shape = BottomSheetDefaults.ExpandedShape
    // Built outside the sheet on purpose: drawerFrost binds to the host window's ViewTreeObserver
    // to
    // capture backdrop blur on open, disarming once captured so host redraws do not loop.
    val frost =
        if (!fixed) Modifier.drawerFrost(hazeState, shape)
        else
            Modifier.paneFrost(
                hazeState ?: localHaze,
                shape,
                edge = false,
                opacity = RexTokens.frost.drawerOpacity,
                blur = RexTokens.frost.drawerBlur,
            )
    val surface =
        if (surfaceColor == Color.Transparent) frost
        else Modifier.background(surfaceColor, shape).then(frost)
    DrawerSheet(onClose, animated = !fixed) { dismiss ->
        Column(
            surface
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            DrawerHandle(dismiss)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onBack?.let { ChromeButton(ChromeIcon.LEFT, backDescription, onClick = it) }
                Column(Modifier.weight(1f)) {
                    if (subtitle == null)
                        Text(
                            title,
                            style = SettingsType.row,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    else {
                        Text(
                            title,
                            style = SettingsType.row,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = SettingsType.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                headerActions()
                ChromeButton(ChromeIcon.CLOSE, closeDescription, onClick = dismiss)
            }
            content()
        }
    }
}
