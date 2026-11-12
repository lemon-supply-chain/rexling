package dev.shulhi.rex

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Optional group colour, written as `[Git:green]`. Each has a light and a dark version; the dark
 * one is lighter and less saturated. [fill] tints the sector at [WheelColors.CATEGORY_FILL]. The
 * [marker] (the sector's outer edge) uses the same colour, deepened in light mode or lifted in
 * dark mode only where the original misses 3:1 on its own tinted sector.
 */
internal enum class WheelCategory(val key: String,private val lightFill: Long,private val darkFill: Long,private val lightMarker: Long,private val darkMarker: Long) {
    BLUE("blue",0xff3b5bdb,0xff8da2ff,0xff3b5bdb,0xffa0b2ff),
    GREEN("green",0xff2f9e44,0xff7fd99a,0xff257b35,0xff7fd99a),
    ORANGE("orange",0xffe8590c,0xffffa270,0xffb74609,0xffffa270),
    PURPLE("purple",0xff7048e8,0xffb79cff,0xff7048e8,0xffc0a8ff);
    fun fill(dark: Boolean)=Color(if(dark) darkFill else lightFill)
    fun marker(dark: Boolean)=Color(if(dark) darkMarker else lightMarker)
    companion object {
        fun fromKey(key: String?)=entries.firstOrNull {it.key.equals(key?.trim(),ignoreCase=true)}
    }
}

/**
 * Colour roles for floating controls (shortcut wheel, joystick and speech bubbles). The hierarchy
 * is page < sector < button: sectors are neutral surfaces and the primary colour is reserved for
 * the hub, highlight and active controls. Light themes separate layers with shadow, dark themes
 * with a brighter surface and a translucent stroke. Every interaction state is a [state] overlay of
 * the content colour, so it follows both themes without per-theme picks.
 */
@Immutable internal data class WheelColors(
    val dark: Boolean,
    /** Opaque neutral sector colour. The same surface shows over every program. */
    val sector: Color,
    /** Groups get a content overlay rather than a separate hue. */
    val group: Color,
    val stroke: Color,
    val content: Color,
    val contentSecondary: Color,
    val primary: Color,
    val onPrimary: Color,
    /** Hover, pressed and selected wash: always content at 8 %. */
    val state: Color,
    /** Light themes lift sectors with shadow; dark themes rely on the brighter surface instead. */
    val shadow: Boolean,
) {
    fun categoryFill(category: WheelCategory?)=category?.fill(dark)?.copy(alpha=CATEGORY_FILL)
    fun categoryMarker(category: WheelCategory?)=category?.marker(dark)
    /** The colour a sector label is read against: the opaque sector plus its state layers. */
    fun sectorOver(group: Boolean=false,stateOn: Boolean=false,category: WheelCategory?=null): Color {
        var c=sector
        // A coloured group already stands out, so it takes the category tint instead of the group wash.
        categoryFill(category)?.let {c=it.compositeOver(c)} ?: run {if(group) c=this.group.compositeOver(c)}
        if(stateOn) c=state.compositeOver(c)
        return c
    }

    companion object {
        const val STATE=.08f
        /** Category sectors use their own colour at 12 %, the low end of the 12 % to 18 % band. */
        const val CATEGORY_FILL=.12f

        fun from(scheme: ColorScheme): WheelColors {
            val dark=scheme.surface.luminance()<.5f
            val content=scheme.onSurface
            // Dark mode is not an inversion: the sector is a step brighter than the page surface,
            // and the stroke is a translucent light edge rather than a hard line.
            val sector=if(dark) scheme.surfaceVariant else scheme.surface
            val onPrimary=if(dark) darkInk(scheme.background) else scheme.onPrimary
            val primary=if(dark) softenForDark(scheme.primary,onPrimary) else separate(scheme.primary,onPrimary)
            return WheelColors(dark=dark,sector=sector,
                group=content.copy(alpha=.06f),
                stroke=content.copy(alpha=if(dark) .16f else .10f),
                content=content,contentSecondary=scheme.onSurfaceVariant,
                primary=primary,onPrimary=onPrimary,
                state=content.copy(alpha=STATE),shadow=!dark)
        }

        /** Lighten and desaturate the dark-theme primary until dark ink on it reaches 4.5:1. */
        internal fun softenForDark(primary: Color,ink: Color): Color {
            var amount=.20f
            fun at(t: Float)=desaturate(lerp(primary,Color.White,t),.15f)
            var c=at(amount)
            while(amount<.9f && (c.luminance()<.30f || contrast(c,ink)<4.5f || contrast(ink.copy(alpha=STATE).compositeOver(c),ink)<4.5f)) {amount+=.02f;c=at(amount)}
            return c
        }
        /** Deep tinted ink for icons on the dark-theme primary, never pure black. */
        private fun darkInk(background: Color)=if(background.luminance() in .002f..<.05f) background else Color(0xff0b1020)
        /**
         * Keep the theme's own on-primary colour and nudge the fill until text on it reaches 4.5:1,
         * including under the pressed overlay, which tints the fill towards the text colour.
         */
        internal fun separate(primary: Color,on: Color): Color {
            val target=if(on.luminance()>.5f) Color.Black else Color.White
            fun readable(c: Color)=contrast(c,on)>=4.5f && contrast(on.copy(alpha=STATE).compositeOver(c),on)>=4.5f
            var amount=0f;var c=primary
            while(amount<.6f && !readable(c)) {amount+=.02f;c=lerp(primary,target,amount)}
            return c
        }
        private fun desaturate(c: Color,amount: Float): Color {
            val grey=c.red*.2126f+c.green*.7152f+c.blue*.0722f
            return Color(c.red+(grey-c.red)*amount,c.green+(grey-c.green)*amount,c.blue+(grey-c.blue)*amount,c.alpha)
        }
        internal fun contrast(a: Color,b: Color): Float {
            val x=a.luminance();val y=b.luminance()
            return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)
        }
    }
}

@Composable internal fun rememberWheelColors(): WheelColors {
    val scheme=MaterialTheme.colorScheme
    return remember(scheme) {WheelColors.from(scheme)}
}
