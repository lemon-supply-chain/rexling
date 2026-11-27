package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionRow(
    target: SessionTarget,
    active: Boolean,
    last: Boolean,
    closing: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit = {},
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
) {
    val actions =
        listOf(
            SwitcherRowAction(
                stringResource(R.string.rename_session),
                ChromeIcon.EDIT,
                run = onRename,
            ),
            SwitcherRowAction(
                stringResource(R.string.close_session),
                ChromeIcon.CLOSE,
                destructive = true,
                run = onClose,
            ),
        )
    SwitcherSwipeRow(revealed, onReveal, actions, enabled = !closing) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (active) .8f else .35f,
                    ),
                )
                .clickable(enabled = !closing, onClick = onSelect)
                .semantics {
                    if (!closing)
                        customActions = actions.map { action ->
                            CustomAccessibilityAction(action.label) {
                                onReveal(false)
                                action.run()
                                true
                            }
                        }
                }
                .heightIn(min = 56.dp)
                .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(6.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(4.dp),
                    ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    target.session.label,
                    style = SettingsType.paneTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (target.detail?.let {
                        stringResource(
                            R.string.session_details_format,
                            it.attachment,
                            it.tabs,
                            it.panes,
                        )
                    } ?: stringResource(R.string.details_not_loaded)),
                    style = SettingsType.paneDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                closing -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                active ->
                    Text(
                        stringResource(R.string.session_current),
                        color = MaterialTheme.colorScheme.primary,
                        style = SettingsType.paneDetail,
                        fontWeight = FontWeight.SemiBold,
                    )
                last ->
                    Text(
                        stringResource(R.string.session_previous),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = SettingsType.paneDetail,
                    )
            }
        }
    }
}
