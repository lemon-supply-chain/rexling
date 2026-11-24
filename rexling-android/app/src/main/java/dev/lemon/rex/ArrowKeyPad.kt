package dev.lemon.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Localized name of an arrow key, for the "%1$s arrow" descriptions. */
internal fun arrowDirectionRes(key: String): Int? =
    when (key) {
        "left" -> R.string.direction_left
        "right" -> R.string.direction_right
        "up" -> R.string.direction_up
        "down" -> R.string.direction_down
        else -> null
    }

/** Discrete tap/hold arrows, restored as the joystick's double-tap alternative. */
@Composable
internal fun ArrowKeyPad(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onKey: (String) -> Unit,
    readable: Boolean = enabled,
    inputKey: String = "",
) {
    val keys = listOf(listOf("pageup", "up", "pagedown"), listOf("left", "down", "right"))
    val icons =
        mapOf(
            "left" to ChromeIcon.LEFT,
            "down" to ChromeIcon.DOWN,
            "up" to ChromeIcon.UP,
            "right" to ChromeIcon.RIGHT,
        )
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier.semantics {
            contentDescription = context.getString(R.string.a11y_arrow_directions)
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { key ->
                    val page = key == "pageup" || key == "pagedown"
                    Box(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .semantics {
                                contentDescription =
                                    when (key) {
                                        "pageup" -> context.getString(R.string.a11y_page_up)
                                        "pagedown" -> context.getString(R.string.a11y_page_down)
                                        else ->
                                            context.getString(
                                                R.string.a11y_arrow_format,
                                                context.getString(
                                                    arrowDirectionRes(key) ?: R.string.direction_up,
                                                ),
                                            )
                                    }
                            }
                            .repeatKey(
                                enabled,
                                inputKey,
                                repeatIntervalMillis = if (page) 160L else 65L,
                            ) {
                                onKey(key)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val color =
                            if (readable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        if (page)
                            Text(
                                stringResource(
                                    if (key == "pageup") R.string.key_page_up
                                    else R.string.key_page_down,
                                ),
                                fontFamily = MonoUi,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = color,
                            )
                        else ChromeSymbol(icons.getValue(key), Modifier.size(22.dp), color)
                    }
                }
            }
        }
    }
}
