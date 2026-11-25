package dev.lemon.rex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

internal data class TerminalDrawerAction(
    val label: String,
    val icon: ChromeIcon,
    val enabled: Boolean = true,
    val opensDrawer: Boolean = false,
    val run: () -> Unit,
)

@Composable
internal fun TerminalActionsDrawer(
    title: String,
    subtitle: String,
    status: String,
    dimensions: Pair<Int, Int>?,
    controlled: Boolean,
    ownership: (() -> Unit)?,
    quickActions: List<TerminalDrawerAction>,
    actions: List<TerminalDrawerAction>,
    onPreferences: () -> Unit,
    onClosePane: (() -> Unit)?,
    onDismiss: () -> Unit,
    hazeState: HazeState? = null,
) {
    fun dispatch(action: () -> Unit) {
        onDismiss()
        action()
    }
    SettingsDrawer(
        title,
        stringResource(R.string.close_terminal_menu),
        onDismiss,
        hazeState,
        subtitle = subtitle.ifBlank { null },
        headerActions = {
            ChromeButton(ChromeIcon.SETTINGS, stringResource(R.string.preferences)) {
                dispatch(onPreferences)
            }
        },
    ) {
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier.weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        status,
                        Modifier.weight(1f, fill = false).alignByBaseline(),
                        style = SettingsType.row,
                        color = if (controlled) colors.primary else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dimensions?.let { (columns, rows) ->
                        Text(
                            "$columns × $rows",
                            Modifier.alignByBaseline(),
                            style = SettingsType.detail,
                            fontFamily = MonoUi,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (ownership != null) {
                    TextButton(onClick = { dispatch(ownership) }) {
                        Text(
                            if (controlled) stringResource(R.string.release_control)
                            else stringResource(R.string.take_control),
                            style = SettingsType.row,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (action in quickActions) {
                    SettingsCard(Modifier.weight(1f).fillMaxHeight()) {
                        val tint =
                            if (action.enabled) colors.onSurface
                            else colors.onSurface.copy(alpha = .38f)
                        Column(
                            Modifier.fillMaxSize()
                                .clickable(enabled = action.enabled, role = Role.Button) {
                                    dispatch(action.run)
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        ) {
                            ChromeSymbol(action.icon, Modifier.size(20.dp), tint)
                            Text(
                                action.label,
                                style = SettingsType.detail,
                                color = tint,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            SettingsCard {
                Column(Modifier.fillMaxWidth()) {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) SettingsDivider()
                        TerminalDrawerRow(action) { dispatch(action.run) }
                    }
                }
            }
            if (onClosePane != null) {
                SettingsCard {
                    TerminalDrawerRow(
                        TerminalDrawerAction(
                            stringResource(R.string.close_pane),
                            ChromeIcon.CLOSE,
                            run = onClosePane,
                        ),
                        destructive = true,
                    ) {
                        dispatch(onClosePane)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalDrawerRow(
    action: TerminalDrawerAction,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tint =
        when {
            !action.enabled -> colors.onSurface.copy(alpha = .38f)
            destructive -> colors.error
            else -> colors.onSurface
        }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChromeSymbol(
            action.icon,
            Modifier.size(20.dp),
            if (destructive || !action.enabled) tint else colors.onSurfaceVariant,
        )
        Text(action.label, Modifier.weight(1f), style = SettingsType.row, color = tint)
        if (action.opensDrawer)
            ChromeSymbol(
                ChromeIcon.CHEVRON,
                Modifier.size(16.dp).rotate(-90f),
                colors.onSurfaceVariant,
            )
    }
}
