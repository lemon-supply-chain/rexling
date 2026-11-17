package dev.lemon.rex

/** Rex names the axis of the layout, rather than the orientation of the divider. */
internal enum class PaneSplit(val label: String,val direction: String) {
    RIGHT("Split right","horizontal"), BELOW("Split below","vertical")
}
