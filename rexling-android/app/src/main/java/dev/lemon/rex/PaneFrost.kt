@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** Blur the terminal backdrop, keeping foreground icons and labels crisp. */
@Composable
internal fun Modifier.paneFrost(
    haze: HazeState,
    shape: Shape,
    active: Boolean = false,
    darkSurface: Boolean = false,
    edge: Boolean = true,
    tint: Color? = null,
    invalidator: PopupFrostInvalidator? = null,
    opacity: Float? = null,
    blur: androidx.compose.ui.unit.Dp = RexTokens.frost.blur,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val surface = if (darkSurface) RexTokens.frost.darkSurface else scheme.surface
    val resolvedTint = tint ?: if (active) scheme.primaryContainer else surface
    val outline =
        if (edge)
            Modifier.border(
                RexTokens.frost.edgeWidth,
                scheme.onSurface.copy(alpha = RexTokens.frost.edgeAlpha),
                shape,
            )
        else Modifier
    if (!LocalFrostedEffectsEnabled.current)
        return clip(shape).background(resolvedTint.copy(alpha = 1f)).then(outline)
    val resolvedOpacity =
        opacity
            ?: if (darkSurface || tint != null) RexTokens.frost.darkSurfaceOpacity
            else if (active) RexTokens.frost.activeOpacity else RexTokens.frost.defaultOpacity
    val style =
        remember(scheme, active, darkSurface, tint, opacity, blur) {
            HazeStyle(
                backgroundColor = surface,
                tints = listOf(HazeTint(resolvedTint.copy(alpha = resolvedOpacity))),
                blurRadius = blur,
                noiseFactor = RexTokens.frost.noiseFactor,
                fallbackTint = HazeTint(resolvedTint.copy(alpha = RexTokens.frost.fallbackOpacity)),
            )
        }
    return clip(shape)
        .then(
            if (invalidator == null) Modifier.hazeEffect(haze, style = style)
            else Modifier.then(PopupFrostElement(haze, style, invalidator)),
        )
        .then(outline)
}

/** Capture a backdrop only while frost can consume it. */
@Composable
internal fun Modifier.frostSource(haze: HazeState, visible: Boolean): Modifier =
    if (visible && LocalFrostedEffectsEnabled.current) hazeSource(haze) else this

/**
 * A Popup has a separate draw tree: capture its backdrop blur once on open, then disarm to avoid
 * re-rendering on every background host draw.
 */
internal class PopupFrostInvalidator(private val host: android.view.View) {
    private val effects = mutableSetOf<dev.chrisbanes.haze.HazeEffectNode>()
    private var observer: android.view.ViewTreeObserver? = null
    private var listening = false
    private var framesRemaining = 0

    @androidx.annotation.VisibleForTesting
    internal var invalidationCount = 0
        private set

    @androidx.annotation.VisibleForTesting
    internal val isListening: Boolean
        get() = listening

    private val listener =
        android.view.ViewTreeObserver.OnPreDrawListener {
            val hasSourceLayer = effects.any { effect ->
                effect.isAttached && effect.state.areas.any { area -> area.contentLayer != null }
            }
            var invalidated = false
            effects.forEach {
                if (it.isAttached) {
                    it.invalidateDraw()
                    invalidated = true
                }
            }
            if (invalidated) {
                invalidationCount++
                if (hasSourceLayer) {
                    framesRemaining--
                    if (framesRemaining <= 0) stop()
                }
            }
            true
        }

    fun start() {
        if (listening) return
        val vto = host.viewTreeObserver
        if (!vto.isAlive) return
        listening = true
        framesRemaining = 2
        observer = vto
        vto.addOnPreDrawListener(listener)
    }

    fun stop() {
        if (!listening) return
        listening = false
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        observer = null
    }

    fun add(effect: dev.chrisbanes.haze.HazeEffectNode) {
        effects += effect
        if (!listening && effect.isAttached) start()
    }

    fun remove(effect: dev.chrisbanes.haze.HazeEffectNode) {
        effects -= effect
        if (effects.isEmpty()) stop()
    }
}

@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
private data class PopupFrostElement(
    val haze: HazeState,
    val style: HazeStyle,
    val invalidator: PopupFrostInvalidator,
) : androidx.compose.ui.node.ModifierNodeElement<PopupFrostNode>() {
    override fun create() = PopupFrostNode(haze, style, invalidator)

    override fun update(node: PopupFrostNode) {
        node.update(haze, style, invalidator)
    }

    override fun androidx.compose.ui.platform.InspectorInfo.inspectableProperties() {
        name = "livePopupFrost"
    }
}

@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
private class PopupFrostNode(
    haze: HazeState,
    style: HazeStyle,
    var invalidator: PopupFrostInvalidator,
) : androidx.compose.ui.node.DelegatingNode() {
    private val effect = delegate(dev.chrisbanes.haze.HazeEffectNode(haze, style))

    override fun onAttach() {
        invalidator.add(effect)
    }

    override fun onDetach() {
        invalidator.remove(effect)
    }

    fun update(haze: HazeState, style: HazeStyle, next: PopupFrostInvalidator) {
        if (invalidator !== next) {
            invalidator.remove(effect)
            invalidator = next
            if (isAttached) invalidator.add(effect)
        }
        effect.state = haze
        effect.style = style
        if (effect.isAttached) effect.onObservedReadsChanged()
    }
}

/** Stronger frosted surfaces share the workspace backdrop across dialog/popup windows. */
@Composable
internal fun Modifier.drawerFrost(
    hazeState: HazeState?,
    shape: Shape,
    visible: Boolean = true,
): Modifier {
    val localHaze = remember { HazeState() }
    val host = androidx.compose.ui.platform.LocalView.current
    val invalidator = remember(host) { PopupFrostInvalidator(host) }
    val frosted = LocalFrostedEffectsEnabled.current
    androidx.compose.runtime.DisposableEffect(invalidator, visible, frosted) {
        if (visible && frosted) invalidator.start()
        onDispose { invalidator.stop() }
    }
    return testTag("Frosted overlay")
        .paneFrost(
            hazeState ?: localHaze,
            shape,
            edge = false,
            invalidator = invalidator,
            opacity = RexTokens.frost.drawerOpacity,
            blur = RexTokens.frost.drawerBlur,
        )
}

/** Retain the captured backdrop through sheet/menu exit animations. */
@Composable
internal fun rememberBackdropCapture(visible: Boolean): Boolean {
    var capture by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(visible) }
    androidx.compose.runtime.LaunchedEffect(visible) {
        if (!visible) kotlinx.coroutines.delay(220)
        capture = visible
    }
    return visible || capture
}

@Composable
internal fun DrawerHandle() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().height(16.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.width(28.dp)
                .height(3.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                ),
        )
    }
}
