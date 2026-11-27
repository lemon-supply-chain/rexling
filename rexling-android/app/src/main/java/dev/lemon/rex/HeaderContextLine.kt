package dev.lemon.rex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Give the session label priority over optional path text at every window width. */
@Composable
internal fun HeaderContextLine(
    label: String,
    directory: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleStyle: TextStyle = style,
) {
    if (title == null && directory.isBlank()) {
        Text(label, modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    val contextStyle = base.merge(style)
    val headingStyle = base.merge(titleStyle)
    val gap = 5.dp
    BoxWithConstraints(modifier) {
        fun width(text: String, textStyle: TextStyle) =
            textMeasurer.measure(text, textStyle, maxLines = 1, softWrap = false).size.width
        val available = constraints.maxWidth
        val gapPx = with(density) { gap.roundToPx() }
        val labelWidth = width(label, contextStyle)
        val titleLimit =
            if (label.isBlank()) available
            else
                (available - minOf(labelWidth, (available * .42f).toInt()) - gapPx).coerceAtLeast(0)
        val headingWidth = title?.let { minOf(width(it, headingStyle), titleLimit) } ?: 0
        val gapCount = (if (title != null) 1 else 0) + (if (label.isNotBlank()) 1 else 0)
        val pathWidth = available - headingWidth - labelWidth - gapCount * gapPx
        val path =
            if (directory.isBlank() || pathWidth <= 0) ""
            else if (width(directory, contextStyle) <= pathWidth) directory
            else {
                val trimmed = directory.trimEnd('/', '\\')
                val separator = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
                val tail = if (separator >= 0) "…${trimmed.substring(separator)}" else ""
                tail.takeIf { it.isNotBlank() && width(it, contextStyle) <= pathWidth }.orEmpty()
            }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null)
                Text(
                    title,
                    Modifier.widthIn(max = with(density) { titleLimit.toDp() }).alignByBaseline(),
                    style = headingStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            if (label.isNotBlank())
                Text(
                    label,
                    Modifier.weight(1f, fill = false).alignByBaseline(),
                    style = contextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            if (path.isNotBlank())
                Text(
                    path,
                    Modifier.alignByBaseline(),
                    // alpha-ok secondary header path text
                    style = contextStyle.copy(color = contextStyle.color.copy(alpha = .78f)),
                    maxLines = 1,
                )
        }
    }
}
