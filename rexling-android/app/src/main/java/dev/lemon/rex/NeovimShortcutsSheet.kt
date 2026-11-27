package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

private data class NeovimEditorRow(
    val id: String,
    val node: NeovimEditNode,
    val child: Int? = null,
) {
    val shortcut
        get() = node.shortcuts.getOrNull(child ?: 0)

    val group
        get() = child == null && node.group != null
}

@Composable
internal fun NeovimShortcutsSheet(
    items: List<NeovimShortcut>,
    onSave: (String) -> String?,
    onClose: () -> Unit,
    enabled: Boolean = true,
    onEnabled: (Boolean) -> Unit = {},
    layout: NeovimShortcutLayout = NeovimShortcutLayout.GRID,
    onLayout: (NeovimShortcutLayout) -> Unit = {},
    effect: NeovimShortcutEffect = NeovimShortcutEffect.FAN_OUT,
    onEffect: (NeovimShortcutEffect) -> Unit = {},
    hapticsEnabled: Boolean = true,
    onHaptics: (Boolean) -> Unit = {},
    hazeState: HazeState? = null,
) {
    var document by rememberSaveable {
        mutableStateOf(NeovimShortcutEditor.encode(NeovimShortcutEditor.fromItems(items)))
    }
    val nodes = NeovimShortcutEditor.decode(document)
    val latestNodes by rememberUpdatedState(nodes)
    var ungroupedExpanded by rememberSaveable { mutableStateOf(true) }
    var draggingGroups by remember { mutableStateOf(false) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceChild by rememberSaveable { mutableStateOf<Int?>(null) }
    var options by rememberSaveable { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val drag = remember { TabDrag() }
    LaunchedEffect(groupId) {
        list.scrollToItem(0)
        drag.cancel()
    }
    val current = nodes.firstOrNull { it.id == groupId }
    fun update(next: List<NeovimEditNode>) {
        document = NeovimShortcutEditor.encode(next)
        error = null
    }
    if (editing) {
        val original =
            nodes
                .firstOrNull { it.id == sourceId }
                ?.let { node ->
                    node.shortcuts.getOrNull(sourceChild ?: 0)?.copy(group = node.group.orEmpty())
                }
        key(sourceId, sourceChild) {
            NeovimShortcutForm(
                original,
                current?.group.orEmpty(),
                nodes.mapNotNull { it.group },
                onCancel = { editing = false },
                onDone = { item ->
                    runCatching { NeovimShortcutEditor.upsert(nodes, sourceId, sourceChild, item) }
                        .fold(
                            onSuccess = {
                                update(it)
                                editing = false
                                null
                            },
                            onFailure = { it.message },
                        )
                },
                hazeState = hazeState,
            )
        }
        return
    }
    if (options) {
        SettingsDrawer(
            stringResource(R.string.shortcut_menu),
            stringResource(R.string.a11y_close_neovim_menu),
            { options = false },
            hazeState,
            onBack = { options = false },
            fixed = true,
            backDescription = stringResource(R.string.back_to_neovim_shortcuts),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                NeovimMenuOptions(layout, onLayout, effect, onEffect, hapticsEnabled, onHaptics)
            }
        }
        return
    }
    val rootRows = nodes.map { NeovimEditorRow(it.id, it) }
    val rows =
        if (current != null)
            current.shortcuts.mapIndexed { index, item ->
                NeovimEditorRow("child:${item.label}", current, index)
            }
        else
            (if (ungroupedExpanded) rootRows.filterNot { it.group } else emptyList()) +
                rootRows.filter { it.group }
    val latestRows by rememberUpdatedState(rows)
    val slop = LocalViewConfiguration.current.touchSlop
    val reordered =
        if (drag.id != null)
            drag.order.mapNotNull { id -> rows.firstOrNull { it.id == id } }.iterator()
        else null
    val display =
        if (reordered != null)
            rows.map { if (current != null || it.group == draggingGroups) reordered.next() else it }
        else rows
    LaunchedEffect(drag.id, drag.pointer) {
        if (drag.id != null)
            while (true) {
                val amount =
                    when {
                        drag.pointer < list.layoutInfo.viewportStartOffset + 40 -> -12f
                        drag.pointer > list.layoutInfo.viewportEndOffset - 40 -> 12f
                        else -> 0f
                    }
                if (amount == 0f || list.scrollBy(amount) == 0f) break
                drag.cross(amount, list)
                delay(16)
            }
    }
    SettingsDrawer(
        current?.group ?: stringResource(R.string.neovim_shortcuts),
        stringResource(R.string.close_shortcuts),
        onClose,
        hazeState,
        onBack = if (current != null) ({ groupId = null }) else null,
        fixed = true,
        backDescription = stringResource(R.string.back_to_neovim_shortcuts),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (current == null) {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(enabled, role = Role.Switch, onValueChange = onEnabled),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.enable_neovim_shortcuts),
                        Modifier.weight(1f),
                        style = SettingsType.row,
                    )
                    Switch(enabled, onCheckedChange = null)
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))
                        .clickable { options = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { // alpha-ok item surface
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.menu_appearance), style = SettingsType.row)
                        Text(
                            if (layout == NeovimShortcutLayout.CIRCULAR)
                                "${stringResource(R.string.layout_circular)} · ${effect.label}"
                            else stringResource(R.string.layout_grid),
                            style = SettingsType.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ChromeSymbol(
                        ChromeIcon.RIGHT,
                        Modifier.size(16.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.hold_and_drag_section),
                    Modifier.weight(1f),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current == null)
                    TextButton(
                        onClick = {
                            renameId = null
                            groupDialog = true
                        },
                    ) {
                        Text(stringResource(R.string.add_group), style = SettingsType.detail)
                    }
                TextButton(
                    onClick = {
                        sourceId = null
                        sourceChild = null
                        editing = true
                    },
                ) {
                    ChromeSymbol(
                        ChromeIcon.ADD,
                        Modifier.size(15.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add_shortcut), style = SettingsType.detail)
                }
            }
            val shortcutListDescription = stringResource(R.string.a11y_neovim_shortcut_list)
            LazyColumn(
                state = list,
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 120.dp, max = 380.dp)
                        .semantics { contentDescription = shortcutListDescription }
                        .pointerInput(groupId) {
                            detectTabDrag(
                                start = { position ->
                                    val touched =
                                        list.layoutInfo.visibleItemsInfo.firstOrNull {
                                            position.y >= it.offset &&
                                                position.y < it.offset + it.size
                                        }
                                    val row = latestRows.firstOrNull { it.id == touched?.key }
                                    if (row == null) false
                                    else {
                                        draggingGroups = row.group
                                        drag.start(
                                            latestRows
                                                .filter {
                                                    groupId != null || it.group == draggingGroups
                                                }
                                                .map { it.id },
                                            list,
                                            position.y,
                                        )
                                    }
                                },
                                move = { drag.move(it.y, slop, list) },
                                end = {
                                    if (drag.changed()) {
                                        val ordered =
                                            drag.order.mapNotNull { id ->
                                                latestRows.firstOrNull { it.id == id }
                                            }
                                        if (groupId == null)
                                            update(
                                                NeovimShortcutEditor.reorderSection(
                                                    latestNodes,
                                                    draggingGroups,
                                                    ordered.map { it.node.id },
                                                ),
                                            )
                                        else
                                            update(
                                                latestNodes.map { node ->
                                                    if (node.id == groupId)
                                                        node.copy(
                                                            shortcuts =
                                                                ordered.mapNotNull { it.shortcut },
                                                        )
                                                    else node
                                                },
                                            )
                                    }
                                    drag.cancel()
                                },
                                cancel = drag::cancel,
                            )
                        },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (current == null)
                    item(key = "ungrouped-heading") {
                        val ungroupedDescription = stringResource(R.string.a11y_ungrouped_shortcuts)
                        val expandedDescription = stringResource(R.string.a11y_state_expanded)
                        val collapsedDescription = stringResource(R.string.a11y_state_collapsed)
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) {
                                    ungroupedExpanded = !ungroupedExpanded
                                }
                                .semantics {
                                    contentDescription = ungroupedDescription
                                    stateDescription =
                                        if (ungroupedExpanded) expandedDescription
                                        else collapsedDescription
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    R.string.ungrouped_count,
                                    nodes.count { it.group == null },
                                ),
                                Modifier.weight(1f),
                                style = SettingsType.section,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ChromeSymbol(
                                ChromeIcon.CHEVRON,
                                Modifier.size(16.dp).graphicsLayer {
                                    rotationZ = if (ungroupedExpanded) 180f else 0f
                                },
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (ungroupedExpanded && nodes.none { it.group == null })
                            Text(
                                stringResource(R.string.shortcuts_appear_directly),
                                Modifier.padding(start = 4.dp, bottom = 8.dp),
                                style = SettingsType.detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                if (rows.isEmpty() && current != null)
                    item {
                        Text(
                            stringResource(R.string.add_shortcut_to_group),
                            Modifier.padding(12.dp),
                            style = SettingsType.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                display.forEach { row ->
                    if (
                        current == null &&
                            row.group &&
                            row.id == display.firstOrNull { it.group }?.id
                    )
                        item(key = "groups-heading") {
                            Text(
                                stringResource(R.string.groups),
                                Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                                style = SettingsType.section,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    item(key = row.id) {
                        val title =
                            if (row.group) row.node.group.orEmpty() else row.shortcut!!.label
                        val rowDescription =
                            if (row.group) stringResource(R.string.a11y_open_neovim_group, title)
                            else stringResource(R.string.a11y_edit_neovim_shortcut, title)
                        Row(
                            Modifier.fillMaxWidth()
                                .graphicsLayer { translationY = drag.translation(row.id, list) }
                                .zIndex(if (drag.id == row.id) 1f else 0f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (row.group)
                                        MaterialTheme.colorScheme.primaryContainer.copy(
                                            alpha = .45f,
                                        )
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                                ) // alpha-ok item surface
                                .clickable {
                                    if (row.group) groupId = row.node.id
                                    else {
                                        sourceId = row.node.id
                                        sourceChild = row.child
                                        editing = true
                                    }
                                }
                                .semantics { contentDescription = rowDescription }
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    title,
                                    style = SettingsType.row,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.group)
                                    Text(
                                        pluralStringResource(
                                            R.plurals.shortcut_count,
                                            row.node.shortcuts.size,
                                            row.node.shortcuts.size,
                                        ),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                else NeovimShortcutChips(row.shortcut!!.keys)
                            }
                            if (row.group)
                                ChromeButton(
                                    ChromeIcon.EDIT,
                                    stringResource(R.string.a11y_rename_neovim_group, title),
                                ) {
                                    renameId = row.node.id
                                    groupDialog = true
                                }
                            ChromeButton(
                                ChromeIcon.CLOSE,
                                if (row.group)
                                    stringResource(R.string.a11y_ungroup_neovim_group, title)
                                else stringResource(R.string.a11y_remove_neovim_shortcut, title),
                            ) {
                                runCatching {
                                        if (row.group)
                                            NeovimShortcutEditor.ungroup(nodes, row.node.id)
                                        else if (row.child == null)
                                            nodes.filterNot { it.id == row.node.id }
                                        else
                                            nodes.map { node ->
                                                if (node.id == row.node.id)
                                                    node.copy(
                                                        shortcuts =
                                                            node.shortcuts.filterIndexed { index, _
                                                                ->
                                                                index != row.child
                                                            },
                                                    )
                                                else node
                                            }
                                    }
                                    .onSuccess(::update)
                                    .onFailure { error = it.message }
                            }
                        }
                    }
                }
            }
            error?.let {
                Text(it, style = SettingsType.detail, color = MaterialTheme.colorScheme.error)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        update(NeovimShortcutEditor.fromItems(NeovimShortcuts.defaults))
                        groupId = null
                    },
                ) {
                    Text(stringResource(R.string.reset_defaults), style = SettingsType.detail)
                }
                Button(
                    onClick = {
                        runCatching {
                                NeovimShortcutEditor.validate(nodes, populated = true)
                                onSave(
                                    NeovimShortcuts.editorText(NeovimShortcutEditor.flatten(nodes)),
                                )
                            }
                            .fold(
                                onSuccess = {
                                    error = it
                                    if (it == null) onClose()
                                },
                                onFailure = { error = it.message },
                            )
                    },
                ) {
                    Text(stringResource(R.string.save), style = SettingsType.row)
                }
            }
        }
    }
    if (groupDialog) {
        val renamed = nodes.firstOrNull { it.id == renameId }
        NeovimGroupDialog(renamed?.group.orEmpty(), onClose = { groupDialog = false }) { name ->
            runCatching {
                val value = NeovimShortcutEditor.groupName(name)
                val next =
                    if (renamed == null)
                        nodes + NeovimEditNode(group = value, shortcuts = emptyList())
                    else nodes.map { if (it.id == renamed.id) it.copy(group = value) else it }
                update(NeovimShortcutEditor.validate(next))
                groupDialog = false
            }
                .exceptionOrNull()
                ?.message
        }
    }
}

@Composable
private fun NeovimGroupDialog(initial: String, onClose: () -> Unit, onSave: (String) -> String?) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (initial.isEmpty()) stringResource(R.string.add_group)
                else stringResource(R.string.rename_group),
                style = SettingsType.row,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    {
                        name = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                )
                error?.let {
                    Text(it, style = SettingsType.detail, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(name) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
