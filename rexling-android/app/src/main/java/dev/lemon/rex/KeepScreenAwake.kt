package dev.lemon.rex

import android.view.View
import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class KeepScreenAwake(
    val label: String,
    val detail: String,
    @get:StringRes val labelRes: Int,
    @get:StringRes val detailRes: Int,
) {
    SYSTEM(
        "System default",
        "Use the normal screen timeout",
        R.string.keep_screen_awake_system,
        R.string.keep_screen_awake_system_desc,
    ),
    VISIBLE(
        "While a terminal is visible",
        "Stay awake even when the terminal is idle",
        R.string.keep_screen_awake_visible,
        R.string.keep_screen_awake_visible_desc,
    ),
    ACTIVE(
        "While the visible terminal is active",
        "Typing and new output refresh the normal screen timeout",
        R.string.keep_screen_awake_active,
        R.string.keep_screen_awake_active_desc,
    );

    fun enabled(visible: Boolean, active: Boolean): Boolean =
        visible &&
            when (this) {
                SYSTEM -> false
                VISIBLE -> true
                ACTIVE -> active
            }
}

internal val LocalKeepScreenAwake = staticCompositionLocalOf { KeepScreenAwake.SYSTEM }

/** A view flag, never a wake lock. Only the visible, focused app window can hold it. */
internal class TerminalAwake(private val view: View) {
    var mode = KeepScreenAwake.SYSTEM
        set(value) {
            if (field != value) {
                field = value
                active = false
                update()
            }
        }

    private var active = false
    private val release = Runnable {
        active = false
        update()
    }
    private val afterFrame = Runnable { view.postOnAnimation(release) }

    fun activity() {
        if (mode == KeepScreenAwake.SYSTEM || !view.isShown || !view.hasWindowFocus()) return
        active = true
        update()
        // Release as soon as this display frame ends. Android then owns the
        // idle countdown; there is no app grace period before its timeout.
        view.postOnAnimation(afterFrame)
    }

    fun update() {
        view.removeCallbacks(afterFrame)
        view.removeCallbacks(release)
        val visible =
            view.isAttachedToWindow &&
                view.isShown &&
                view.windowVisibility == View.VISIBLE &&
                view.hasWindowFocus()
        if (!visible) active = false
        view.keepScreenOn = mode.enabled(visible, active)
    }

    fun detach() {
        view.removeCallbacks(afterFrame)
        view.removeCallbacks(release)
        active = false
        view.keepScreenOn = false
    }
}
