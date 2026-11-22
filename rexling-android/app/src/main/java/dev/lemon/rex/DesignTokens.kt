package dev.lemon.rex

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's design tokens.
 *
 * Everything that decides how Rexling looks lives here: type roles, shape tiers,
 * the 4/8 spacing scale, motion specs, target sizes and the intent -> color role
 * map. Screens read tokens; they never inline a `fontSize`, a radius, a raw
 * spring or an alpha-modified color.
 *
 * The values here are the contract: keep them in this file rather than in a
 * separate design document, so a screen cannot drift from what is defined.
 */
internal object RexTokens {
    val type: Typography = rexTypography()
    val mono: RexMonoType = RexMonoType
    val shapes: Shapes = rexShapes()
    val shape: RexShapeTiers = RexShapeTiers
    val space: RexSpace = RexSpace
    val motion: RexMotion = RexMotion
    val frost: RexFrostTokens = RexFrostTokens
}

/** Visual tokens for translucent frosted surfaces. */
internal object RexFrostTokens {
    val blur = 20.dp
    val drawerBlur = 24.dp
    val edgeWidth = 0.5.dp
    val darkSurface = Color(0xff171a20)
    const val defaultOpacity = 0.32f
    const val activeOpacity = 0.46f
    const val darkSurfaceOpacity = 0.60f
    const val drawerOpacity = 0.72f
    const val fallbackOpacity = 0.92f
    const val edgeAlpha = 0.10f
    const val noiseFactor = 0.02f
}

/** Machine text: paths, keycap legends, ports and dimensions. Never prose. */
internal val MonoUi = FontFamily(
    Font(R.font.jetbrains_mono_regular),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** JetBrains Mono styles for the three machine-text sizes the chrome uses. */
internal object RexMonoType {
    /** 12sp metadata: directories, screen dimensions, badge counts. */
    val metadata = TextStyle(fontFamily = MonoUi, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)

    /** 12sp keycap legend. */
    val keycap = TextStyle(fontFamily = MonoUi, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp)

    /** 12sp inline value inside prose (ports, shortcuts, ids). */
    val value = TextStyle(fontFamily = MonoUi, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
}

/**
 * The Material role scale with Rexling's sizes. Sizes and line heights are the
 * contract; weights give emphasis without changing size, so a selected row never
 * reflows. 12sp is the floor for visible text.
 */
private fun rexTypography(): Typography {
    val base = Typography()
    fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )
    return base.copy(
        displaySmall = style(36, 44, FontWeight.Bold),
        headlineSmall = style(24, 32, FontWeight.SemiBold),
        titleLarge = style(20, 26, FontWeight.SemiBold),
        titleMedium = style(16, 22, FontWeight.Medium),
        titleSmall = style(14, 20, FontWeight.Medium),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, FontWeight.Normal),
        labelLarge = style(14, 20, FontWeight.Medium),
        labelMedium = style(12, 16, FontWeight.Medium),
        labelSmall = style(12, 16, FontWeight.Medium),
    )
}

/** Six tiers. Nested radii use outer minus padding; see the token proposal. */
internal object RexShapeTiers {
    val badge = RoundedCornerShape(8.dp)
    val keycap = RoundedCornerShape(12.dp)
    val control = RoundedCornerShape(12.dp)
    val tabCell = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(28.dp)
    val dock = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = RoundedCornerShape(percent = 50)
    val dot = CircleShape
}

private fun rexShapes(): Shapes = Shapes(
    extraSmall = RexShapeTiers.badge,
    small = RexShapeTiers.control,
    medium = RexShapeTiers.tabCell,
    large = RexShapeTiers.card,
    extraLarge = RexShapeTiers.sheet,
)

/** The 4/8 spacing scale. Chrome only; the terminal grid is content. */
internal object RexSpace {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp

    val screen = x4
    val section = x3
    val rowVertical = x3
    val actionGap = x2
    val groupGap = x4
}

/**
 * Motion specs shaped like Material's motion scheme, so an upgrade to
 * `MotionScheme` is a rename. Spatial specs overshoot; effects specs never do.
 */
internal object RexMotion {
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = .8f, stiffness = 1400f)
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = .85f, stiffness = 700f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = .9f, stiffness = 320f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = tween(120, easing = FastOutLinearInEasing)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = tween(220, easing = LinearOutSlowInEasing)
}

/**
 * Intent -> color role. Screens ask for the job, not for "primary", so the same
 * job keeps one color and different jobs cannot accidentally share one.
 */
internal data class RexColorRoles(
    val action: Color,
    val onAction: Color,
    val selectionContainer: Color,
    val onSelection: Color,
    val working: Color,
    val workingContainer: Color,
    val onWorkingContainer: Color,
    val attention: Color,
    val quietText: Color,
    val disabled: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
) {
    companion object {
        fun of(scheme: ColorScheme) = RexColorRoles(
            action = scheme.primary,
            onAction = scheme.onPrimary,
            selectionContainer = scheme.secondaryContainer,
            onSelection = scheme.onSecondaryContainer,
            // Progress reuses the action hue; see terminalProgressColor for why.
            working = scheme.primary,
            workingContainer = scheme.primaryContainer,
            onWorkingContainer = scheme.onPrimaryContainer,
            attention = scheme.primary,
            quietText = scheme.onSurfaceVariant,
            disabled = scheme.onSurfaceVariant,
            danger = scheme.error,
            onDanger = scheme.onError,
            dangerContainer = scheme.errorContainer,
            onDangerContainer = scheme.onErrorContainer,
        )
    }
}

internal val ColorScheme.roles: RexColorRoles get() = RexColorRoles.of(this)

/**
 * [ink] at [fraction] over an opaque [surface], resolved to a solid color.
 *
 * This is the same pixels an alpha-composited ink would produce over that known
 * surface, resolved once so the value cannot be reused over an unknown
 * background and silently lose contrast.
 */
internal fun tintOver(ink: Color, surface: Color, fraction: Float): Color =
    if (fraction >= 1f) ink else lerp(surface, ink, fraction.coerceIn(0f, 1f))

/**
 * False when the platform's animator duration scale is off. Decorative motion
 * and the working pulse read this and snap instead of running.
 */
internal val LocalMotionEnabled = staticCompositionLocalOf { true }

/** Keep live backdrop capture and translucent frost optional; opaque surfaces need neither. */
internal val LocalFrostedEffectsEnabled = staticCompositionLocalOf { true }