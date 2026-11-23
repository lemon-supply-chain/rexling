package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SwitcherSearchField(
    query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        query, onQuery,
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant.copy(alpha = .45f)).padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = SettingsType.row.copy(color = colors.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChromeSymbol(ChromeIcon.SEARCH, Modifier.size(17.dp), colors.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, style = SettingsType.row, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
            }
        },
    )
}

@Composable internal fun AllPanesPage(
    sources: List<PaneCatalogSource>,
    currentServer: String,
    currentSession: String,
    currentBlock: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (PaneCatalogTarget) -> Boolean,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var missedPane by remember { mutableStateOf(false) }
    val loading = sources.any { it.loading }
    val incomplete = sources.any { it.incomplete }
    val results = remember(sources, search) {
        sources.mapNotNull { source ->
            val matching = source.panes.filter { PaneCatalog.matches(it, search.trim()) }
            if (matching.isEmpty()) null else source.server to matching.groupBy { it.session }
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeButton(ChromeIcon.LEFT, stringResource(R.string.a11y_back_to_sessions), onClick = onBack)
            SwitcherSearchField(search, { search = it }, stringResource(R.string.find_a_pane), Modifier.weight(1f))
            ChromeButton(ChromeIcon.REFRESH, stringResource(R.string.refresh_panes), enabled = !loading && sources.isNotEmpty()) {
                missedPane = false
                onRefresh()
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp))
        if (incomplete || missedPane) Text(
            if (missedPane) stringResource(R.string.pane_unavailable_refresh)
            else stringResource(R.string.pane_details_unavailable_refresh),
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = SettingsType.detail, color = colors.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (results.isEmpty() && !loading) item {
                Text(
                    if (sources.isEmpty()) stringResource(R.string.no_servers_connected)
                    else if (search.isBlank()) stringResource(R.string.no_panes_available)
                    else stringResource(R.string.no_matching_panes),
                    Modifier.padding(vertical = 16.dp), style = SettingsType.row, color = colors.onSurfaceVariant,
                )
            }
            for ((server, sessions) in results) {
                item(key = "pane-server:${server.id}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ServerGlyph(server.icon, Modifier.size(16.dp))
                        Text(server.label, style = SettingsType.section, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                for ((session, panes) in sessions) {
                    item(key = "pane-session:${sessionKey(server.id, session.id)}") {
                        Text(session.label, Modifier.padding(start = 4.dp, top = 4.dp), style = SettingsType.detail,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    items(panes, key = { "pane:${it.key}" }) { target ->
                        val current = server.id == currentServer && session.id == currentSession && target.pane.block == currentBlock
                        PaneCatalogRow(target, current) { missedPane = !onOpen(target) }
                    }
                }
            }
        }
    }
}

@Composable private fun PaneCatalogRow(target: PaneCatalogTarget, current: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (current) colors.primaryContainer else colors.surfaceVariant.copy(alpha = .45f))
            .clickable(role = Role.Button, onClick = onOpen).heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        PaneProgramTile(target.pane.program, Modifier.size(24.dp, 19.dp), large = false, rotation = 0f)
        Column(Modifier.weight(1f)) {
            Text(target.title, style = SettingsType.row, color = if (current) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (target.pane.directory.isNotBlank()) Text(
                target.pane.directory, style = SettingsType.detail, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        target.activity?.let { status ->
            Text(status.label, style = SettingsType.detail, color = colors.onSurfaceVariant)
        }
        if (current) ChromeSymbol(ChromeIcon.CHECK, Modifier.size(16.dp), colors.onPrimaryContainer)
    }
}
