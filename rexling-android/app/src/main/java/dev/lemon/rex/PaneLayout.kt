package dev.lemon.rex

import org.json.JSONArray
import org.json.JSONObject

data class PaneBounds(val x: Float, val y: Float, val width: Float, val height: Float) {
    internal fun move(dx: Float, dy: Float) =
        copy(
            x = (x + dx).coerceIn(0f, (1f - width).coerceAtLeast(0f)),
            y = (y + dy).coerceIn(0f, (1f - height).coerceAtLeast(0f)),
        )

    internal fun resize(dx: Float, dy: Float, minWidth: Float, minHeight: Float) =
        copy(
            width = (width + dx).coerceIn(minWidth.coerceAtMost(1f - x), 1f - x),
            height = (height + dy).coerceIn(minHeight.coerceAtMost(1f - y), 1f - y),
        )

    internal fun positionIn(original: PaneBounds, pane: PanePlacement) =
        pane.copy(
            x = x + (pane.x - original.x) * width / original.width,
            y = y + (pane.y - original.y) * height / original.height,
            width = pane.width * width / original.width,
            height = pane.height * height / original.height,
        )

    companion object {
        internal fun parse(rect: JSONArray?): PaneBounds? {
            if (rect?.length() != 4) return null
            val n = (0..3).map { rect.optDouble(it, Double.NaN) }
            if (n.any { !it.isFinite() } || n[2] <= 0 || n[3] <= 0) return null
            val right = n[0] + n[2]
            val bottom = n[1] + n[3]
            if (!right.isFinite() || !bottom.isFinite()) return null
            val x = n[0].coerceIn(0.0, 1.0)
            val y = n[1].coerceIn(0.0, 1.0)
            val width = (right.coerceIn(0.0, 1.0) - x).toFloat()
            val height = (bottom.coerceIn(0.0, 1.0) - y).toFloat()
            if (width <= 0 || height <= 0) return null
            return PaneBounds(x.toFloat(), y.toFloat(), width, height)
        }
    }
}

/** Visible intersection of a server-reported pane with its window, in window fractions. */
data class PanePlacement(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val layer: Int = 0,
    val floating: Boolean = false,
    val layerId: String = "",
    val layerBounds: PaneBounds? = null,
    val minimized: Boolean = false,
) {
    companion object {
        fun parse(value: JSONObject?): PanePlacement? {
            value ?: return null
            val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
            val bounds = PaneBounds.parse(value.optJSONArray("rect")) ?: return null
            return PanePlacement(
                id,
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
                value.optInt("layer").coerceIn(0, 255),
                value.optBoolean("floating"),
                value.optString("layerId"),
                PaneBounds.parse(value.optJSONArray("layerBounds")),
                value.optBoolean("floating") && value.optBoolean("minimized"),
            )
        }
    }
}

/**
 * A border between tiled panes that can be dragged: [horizontal] splits are side by side (a
 * vertical border), others stacked. [ratio] is the share of the split's rect ([x], [y], [width],
 * [height], in window fractions) that the [before] side gets; [before] and [after] are one pane on
 * each side.
 */
data class PaneSplitLine(
    val id: String,
    val horizontal: Boolean,
    val ratio: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val before: String,
    val after: String,
) {
    companion object {
        fun parse(value: JSONObject?): PaneSplitLine? {
            value ?: return null
            val before = value.optString("before").takeIf { it.isNotBlank() } ?: return null
            val after =
                value.optString("after").takeIf { it.isNotBlank() && it != before } ?: return null
            val direction = value.optString("direction")
            if (direction != "horizontal" && direction != "vertical") return null
            val ratio =
                value.optDouble("ratio", Double.NaN).takeIf { it.isFinite() && it > 0 && it < 1 }
                    ?: return null
            val rect = value.optJSONArray("rect")?.takeIf { it.length() == 4 } ?: return null
            val n = (0..3).map { rect.optDouble(it, Double.NaN) }
            if (n.any { !it.isFinite() } || n[2] <= 0 || n[3] <= 0) return null
            return PaneSplitLine(
                value.optString("id"),
                direction == "horizontal",
                ratio.toFloat(),
                n[0].toFloat(),
                n[1].toFloat(),
                n[2].toFloat(),
                n[3].toFloat(),
                before,
                after,
            )
        }
    }
}

/**
 * The layout with [split] moved to [ratio], as Rex will lay it out: everything inside the split's
 * rect on either side of the border is stretched or squeezed along the split's axis in proportion,
 * nested splits included. Used to reflow panes live while a border is dragged.
 */
internal fun movedSplit(
    placements: List<PanePlacement>,
    splits: List<PaneSplitLine>,
    split: PaneSplitLine,
    ratio: Float,
): Pair<List<PanePlacement>, List<PaneSplitLine>> {
    val start = if (split.horizontal) split.x else split.y
    val size = if (split.horizontal) split.width else split.height
    val old = start + size * split.ratio
    val new = start + size * ratio
    fun map(v: Float): Float =
        when {
            v <= old -> start + (v - start) * (new - start) / (old - start).coerceAtLeast(1e-6f)
            else ->
                new + (v - old) * (start + size - new) / (start + size - old).coerceAtLeast(1e-6f)
        }
    val eps = 1e-4f
    fun inside(x: Float, y: Float, w: Float, h: Float) =
        x >= split.x - eps &&
            y >= split.y - eps &&
            x + w <= split.x + split.width + eps &&
            y + h <= split.y + split.height + eps
    fun axis(a: Float, b: Float): Pair<Float, Float> {
        val from = map(a)
        return from to (map(a + b) - from)
    }
    val panes = placements.map { p ->
        if (p.floating || !inside(p.x, p.y, p.width, p.height)) p
        else if (split.horizontal) axis(p.x, p.width).let { (x, w) -> p.copy(x = x, width = w) }
        else axis(p.y, p.height).let { (y, h) -> p.copy(y = y, height = h) }
    }
    val lines = splits.map { s ->
        if (s.id == split.id) s.copy(ratio = ratio)
        else if (!inside(s.x, s.y, s.width, s.height)) s
        else if (split.horizontal) axis(s.x, s.width).let { (x, w) -> s.copy(x = x, width = w) }
        else axis(s.y, s.height).let { (y, h) -> s.copy(y = y, height = h) }
    }
    return panes to lines
}
