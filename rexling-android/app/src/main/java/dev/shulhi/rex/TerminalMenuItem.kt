package dev.shulhi.rex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun TerminalMenuItem(label: String, icon: ChromeIcon, onClick: () -> Unit) {
    TerminalMenuItem(label, icon, true, false, onClick)
}

@Composable
internal fun TerminalMenuItem(label: String, icon: ChromeIcon, enabled: Boolean, onClick: () -> Unit) {
    TerminalMenuItem(label, icon, enabled, false, onClick)
}

/** Keep each decorative glyph and label together with a compact, consistent gap. */
@Composable
internal fun TerminalMenuItem(
    label: String,
    icon: ChromeIcon,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val iconColor = if (!enabled) scheme.onSurface.copy(alpha = .38f) else if (destructive) scheme.error else scheme.onSurfaceVariant
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromeSymbol(icon, color = iconColor)
                Text(label)
            }
        },
        enabled = enabled,
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = if (destructive) scheme.error else scheme.onSurface)
    )
}
