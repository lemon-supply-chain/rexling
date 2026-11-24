package dev.lemon.rex

import androidx.annotation.StringRes

/** Rex names the axis of the layout, rather than the orientation of the divider. */
internal enum class PaneSplit(@StringRes val labelRes: Int, val direction: String) {
    RIGHT(R.string.split_right, "horizontal"),
    BELOW(R.string.split_below, "vertical"),
}
