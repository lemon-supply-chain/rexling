package dev.lemon.rex

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

/** Cached vector commands; unrotated tiles are rasterized once per exact size. */
internal class ProgramVectorPicture
private constructor(
    private val key: String,
    private val flat: Picture,
    private val surface: Picture?,
    private val symbol: Picture?,
    private val width: Float,
    private val height: Float,
    private val accent: Int,
    private val outline: Path?,
) {
    private val symbolRim =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            alpha = 84
        }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Evicted bitmaps are not recycled: an older display list may still reference them.
    private val tileBitmaps =
        object : LinkedHashMap<Long, Bitmap>(8, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) =
                size > 4
        }
    private val rimPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = outline?.let { path ->
                val bounds = RectF()
                path.computeBounds(bounds, true)
                val stops =
                    if (luminance(accent) > .72f)
                        intArrayOf(
                            Color.argb(30, 0, 0, 0),
                            Color.argb(14, 0, 0, 0),
                            Color.argb(0, 0, 0, 0),
                        )
                    else
                        intArrayOf(
                            Color.argb(115, 255, 255, 255),
                            Color.argb(36, 255, 255, 255),
                            Color.argb(0, 255, 255, 255),
                        )
                LinearGradient(
                    0f,
                    bounds.top,
                    0f,
                    bounds.bottom,
                    stops,
                    floatArrayOf(0f, .4f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }

    fun draw(canvas: Canvas, width: Float, height: Float, rotation: Float = 0f) {
        val save = canvas.save()
        canvas.rotate(rotation, width / 2, height / 2)
        canvas.scale(width / this.width, height / this.height)
        canvas.drawPicture(flat)
        symbol?.let(canvas::drawPicture)
        canvas.restoreToCount(save)
    }

    /**
     * Tile material: flat plate at 18/27dp, ceramic surface at 34dp, a rim calibrated in device
     * pixels, and a logo that gains depth only when the tile is large enough to resolve it.
     */
    fun drawTile(canvas: Canvas, width: Float, height: Float, rotation: Float = 0f) {
        if (rotation == 0f) {
            val key = (width.toBits().toLong() shl 32) or (height.toBits().toLong() and 0xffffffffL)
            val cached =
                tileBitmaps[key]
                    ?: run {
                        val bitmap =
                            Bitmap.createBitmap(
                                ceil(width).toInt().coerceAtLeast(1),
                                ceil(height).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        drawTileDirect(Canvas(bitmap), width, height)
                        tileBitmaps[key] = bitmap
                        bitmap
                    }
            canvas.drawBitmap(cached, 0f, 0f, tilePaint)
            return
        }
        drawTileDirect(canvas, width, height, rotation)
    }

    /** Vector source for the size-specific bitmap cache and the rare rotated direct draw. */
    private fun drawTileDirect(canvas: Canvas, width: Float, height: Float, rotation: Float = 0f) {
        val density = Resources.getSystem().displayMetrics.density.coerceAtLeast(1f)
        val symbol = symbol
        val outline = outline
        val detailed = width / density >= DETAILED_WIDTH_DP
        val save = canvas.save()
        canvas.rotate(rotation, width / 2, height / 2)
        canvas.scale(width / this.width, height / this.height)
        val unitsToPx = (width / this.width).coerceAtLeast(.01f)
        canvas.drawPicture(if (detailed && surface != null) surface else flat)
        if (outline != null) drawRim(canvas, outline, unitsToPx)
        if (symbol != null) {
            // Everything the logo adds is clipped to the plate so nothing can spill
            // past the rounded edge.
            val clipped = canvas.save()
            if (outline != null) canvas.clipPath(outline)
            if (detailed) {
                offsetSymbol(canvas, symbol, -RIM_DY / unitsToPx, symbolRim)
            }
            canvas.drawPicture(symbol)
            canvas.restoreToCount(clipped)
        }
        canvas.restoreToCount(save)
    }

    private fun offsetSymbol(canvas: Canvas, symbol: Picture, dy: Float, paint: Paint) {
        val layer = canvas.saveLayer(0f, 0f, this.width, this.height, paint)
        canvas.translate(0f, dy)
        canvas.drawPicture(symbol)
        canvas.restoreToCount(layer)
    }

    private fun drawRim(canvas: Canvas, outline: Path, unitsToPx: Float) {
        val layer = canvas.save()
        canvas.clipPath(outline)
        rimPaint.strokeWidth = 2f / unitsToPx
        canvas.drawPath(outline, rimPaint)
        canvas.restoreToCount(layer)
    }

    companion object {
        private const val RIM_DY = 1f
        private const val DETAILED_WIDTH_DP = 30f
        private const val DEFAULT_ACCENT = 0xFF3B78F0.toInt()
        private val cache = mutableMapOf<String, ProgramVectorPicture>()
        private val glyphCache = mutableMapOf<String, ProgramVectorPicture>()

        @Synchronized
        fun load(assets: AssetManager, name: String): ProgramVectorPicture =
            cache.getOrPut(name) {
                val commands = ProgramCatalog.icons(assets).getJSONArray(name)
                val prelude = preludeMatrix(commands)
                record(
                    name,
                    commands,
                    61f,
                    48f,
                    layers = ProgramCatalog.layers(assets).optJSONObject(name),
                    accent =
                        ProgramCatalog.accents(assets)
                            .optString(name)
                            .takeIf(String::isNotEmpty)
                            ?.let {
                                runCatching { Color.parseColor(it) }.getOrDefault(DEFAULT_ACCENT)
                            } ?: DEFAULT_ACCENT,
                    outline =
                        ProgramCatalog.plate(assets)?.let { Path(it).apply { transform(prelude) } },
                )
            }

        @Synchronized
        fun loadGlyph(assets: AssetManager, name: String, tint: Int): ProgramVectorPicture =
            glyphCache.getOrPut("$name:$tint") {
                val glyph = ProgramCatalog.glyphs(assets).getJSONObject(name)
                record(
                    "$name:$tint",
                    glyph.getJSONArray("commands"),
                    glyph.getDouble("width").toFloat(),
                    glyph.getDouble("height").toFloat(),
                    tint,
                )
            }

        private fun record(
            key: String,
            commands: JSONArray,
            width: Float,
            height: Float,
            tint: Int? = null,
            layers: JSONObject? = null,
            accent: Int = DEFAULT_ACCENT,
            outline: Path? = null,
        ): ProgramVectorPicture {
            // A tile stream opens with global transforms (the artwork crop) before any
            // save, so every layer has to replay that prelude as well.
            var prelude = 0
            while (
                prelude < commands.length() &&
                    commands.getJSONObject(prelude).getString("op") !in setOf("save", "restore")
            ) prelude++
            val flatStart = if (tint == null) layers?.optInt("flat", prelude) ?: prelude else 0
            val surfaceStart = if (tint == null) layers?.optInt("surface", -1) ?: -1 else -1
            val symbolStart = if (tint == null) layers?.optInt("symbol", -1) ?: -1 else -1
            val flatEnd = if (surfaceStart >= 0) surfaceStart else commands.length()
            val flat =
                replay(
                    commands,
                    width,
                    height,
                    tint,
                    flatStart,
                    flatEnd,
                    if (tint == null) prelude else 0,
                )
            val surface =
                if (surfaceStart >= 0 && symbolStart > surfaceStart)
                    replay(commands, width, height, tint, surfaceStart, symbolStart, prelude)
                else null
            val symbol =
                if (symbolStart >= 0)
                    replay(commands, width, height, tint, symbolStart, commands.length(), prelude)
                else null
            return ProgramVectorPicture(
                key,
                flat,
                surface,
                symbol,
                width,
                height,
                accent,
                if (symbol == null) null else outline,
            )
        }

        private fun replay(
            commands: JSONArray,
            width: Float,
            height: Float,
            tint: Int?,
            from: Int,
            to: Int,
            prelude: Int = 0,
        ): Picture {
            val picture = Picture()
            val canvas = picture.beginRecording(width.toInt(), height.toInt())
            val save = canvas.save()
            for (i in 0 until prelude) applyCommand(canvas, commands.getJSONObject(i), tint)
            for (i in from until to) applyCommand(canvas, commands.getJSONObject(i), tint)
            canvas.restoreToCount(save)
            picture.endRecording()
            return picture
        }

        private fun applyCommand(canvas: Canvas, command: JSONObject, tint: Int?) {
            when (command.getString("op")) {
                "save" -> canvas.save()
                "restore" -> canvas.restore()
                "translate" -> canvas.translate(command.float("x"), command.float("y"))
                "scale" -> canvas.scale(command.float("x"), command.float("y"))
                "transform" -> canvas.concat(transform(command.getString("value")))
                "clip" -> canvas.clipPath(path(command))
                "path" -> {
                    val path = path(command)
                    val bounds = RectF()
                    path.computeBounds(bounds, true)
                    canvas.drawPath(path, paint(command.getJSONObject("paint"), bounds, tint))
                }
                else -> error("Unsupported vector command")
            }
        }

        private fun preludeMatrix(commands: JSONArray): Matrix {
            val matrix = Matrix()
            for (i in 0 until commands.length()) {
                val command = commands.getJSONObject(i)
                val step =
                    when (command.getString("op")) {
                        "translate" ->
                            Matrix().apply { setTranslate(command.float("x"), command.float("y")) }
                        "scale" ->
                            Matrix().apply { setScale(command.float("x"), command.float("y")) }
                        "transform" -> transform(command.getString("value"))
                        else -> break
                    }
                matrix.preConcat(step)
            }
            return matrix
        }

        private fun luminance(color: Int): Float =
            (.299f * Color.red(color) + .587f * Color.green(color) + .114f * Color.blue(color)) /
                255f

        private fun JSONObject.float(key: String) = getDouble(key).toFloat()

        private fun path(command: JSONObject) =
            requireNotNull(PathParser.createPathFromPathData(command.getString("path"))).apply {
                fillType =
                    if (command.optBoolean("evenOdd")) Path.FillType.EVEN_ODD
                    else Path.FillType.WINDING
            }

        private val numbers = Regex("[-+]?(?:\\d*\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?")
        private val transforms = Regex("(matrix|translate|scale|rotate)\\s*\\(([^)]*)\\)")

        private fun transform(value: String): Matrix {
            val matrix = Matrix()
            for (match in transforms.findAll(value)) {
                val v = numbers.findAll(match.groupValues[2]).map { it.value.toFloat() }.toList()
                val next = Matrix()
                when (match.groupValues[1]) {
                    "translate" -> next.setTranslate(v[0], v.getOrElse(1) { 0f })
                    "scale" -> next.setScale(v[0], v.getOrElse(1) { v[0] })
                    "rotate" -> next.setRotate(v[0], v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                    "matrix" ->
                        next.setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f))
                }
                matrix.preConcat(next)
            }
            return matrix
        }

        private fun color(value: String, opacity: Float = 1f): Int {
            val values =
                numbers
                    .findAll(
                        if (value.startsWith("color(display-p3")) value.substringAfter("display-p3")
                        else value,
                    )
                    .map { it.value.toFloat() }
                    .toList()
            val color =
                when {
                    value.startsWith("color(display-p3") ->
                        Color.valueOf(
                                values[0],
                                values[1],
                                values[2],
                                1f,
                                ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
                            )
                            .toArgb()
                    value.startsWith("rgba(") ->
                        Color.argb(
                            (values[3] * 255).toInt(),
                            values[0].toInt(),
                            values[1].toInt(),
                            values[2].toInt(),
                        )
                    value == "currentColor" -> Color.BLACK
                    else -> Color.parseColor(value)
                }
            return Color.argb(
                (Color.alpha(color) * opacity).toInt().coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        }

        private fun coordinate(value: String) =
            if (value.endsWith('%')) value.dropLast(1).toFloat() / 100f else value.toFloat()

        private fun paint(value: JSONObject, bounds: RectF, tint: Int? = null): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                if (value.optBoolean("stroke")) {
                    style = Paint.Style.STROKE
                    strokeWidth = value.optDouble("strokeWidth", 1.0).toFloat()
                    strokeCap =
                        when (value.optString("strokeCap")) {
                            "round" -> Paint.Cap.ROUND
                            "square" -> Paint.Cap.SQUARE
                            else -> Paint.Cap.BUTT
                        }
                    strokeJoin =
                        when (value.optString("strokeJoin")) {
                            "round" -> Paint.Join.ROUND
                            "bevel" -> Paint.Join.BEVEL
                            else -> Paint.Join.MITER
                        }
                }
                if (tint != null) {
                    color =
                        Color.argb(
                            (Color.alpha(tint) * value.float("alpha")).toInt().coerceIn(0, 255),
                            Color.red(tint),
                            Color.green(tint),
                            Color.blue(tint),
                        )
                    return@apply
                }
                if (!value.has("gradient")) {
                    color = color(value.getString("color"), value.float("alpha"))
                    return@apply
                }
                val attributes = value.getJSONObject("attributes")
                val stops = value.getJSONArray("stops")
                val positions = FloatArray(stops.length())
                val colors = IntArray(stops.length())
                for (i in positions.indices) {
                    val stop = stops.getJSONObject(i)
                    positions[i] = coordinate(stop.getString("offset"))
                    colors[i] =
                        color(
                            stop.getString("stop-color"),
                            stop.optDouble("stop-opacity", 1.0).toFloat(),
                        )
                }
                val local = Matrix()
                if (attributes.optString("gradientUnits") != "userSpaceOnUse") {
                    local.setValues(
                        floatArrayOf(
                            bounds.width(),
                            0f,
                            bounds.left,
                            0f,
                            bounds.height(),
                            bounds.top,
                            0f,
                            0f,
                            1f,
                        ),
                    )
                }
                val gradientTransform = attributes.optString("gradientTransform")
                if (gradientTransform.isNotEmpty()) local.preConcat(transform(gradientTransform))
                fun coord(name: String, default: String) =
                    coordinate(attributes.optString(name, default))
                shader =
                    if (value.getString("gradient") == "radialGradient") {
                        RadialGradient(
                            coord("cx", "50%"),
                            coord("cy", "50%"),
                            coord("r", "50%"),
                            colors,
                            positions,
                            Shader.TileMode.CLAMP,
                        )
                    } else
                        LinearGradient(
                            coord("x1", "0"),
                            coord("y1", "0"),
                            coord("x2", "1"),
                            coord("y2", "0"),
                            colors,
                            positions,
                            Shader.TileMode.CLAMP,
                        )
                shader!!.setLocalMatrix(local)
                alpha = (value.float("alpha") * 255).toInt().coerceIn(0, 255)
            }
    }
}

internal typealias ProgramTilePicture = ProgramVectorPicture
