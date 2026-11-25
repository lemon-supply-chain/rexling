package dev.lemon.rex

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

@Composable
internal fun ControlShortcutsSheet(
    items: List<ControlShortcut>,
    onSave: (String) -> String?,
    onClose: () -> Unit,
    hazeState: HazeState? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var draftText by rememberSaveable { mutableStateOf(ControlShortcutConfig.editorText(items)) }
    val draft = ControlShortcutConfig.restore(draftText)
    val latest by rememberUpdatedState(draft)
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(value: List<ControlShortcut>) {
        draftText = ControlShortcutConfig.editorText(value)
        error = null
    }
    val list = rememberLazyListState()
    val drag = remember { TabDrag() }
    val index = editing
    if (index != null) {
        key(index) {
            ControlShortcutRecorder(
                draft.getOrNull(index),
                onCancel = { editing = null },
                onDone = { item ->
                    val next =
                        draft.toMutableList().apply {
                            if (index in indices) set(index, item) else add(item)
                        }
                    val valid = runCatching {
                        ControlShortcutConfig.parse(ControlShortcutConfig.editorText(next))
                    }
                    if (valid.isSuccess) {
                        update(valid.getOrThrow())
                        editing = null
                        null
                    } else valid.exceptionOrNull()?.message
                },
                hazeState = hazeState,
            )
        }
        return
    }
    val slop = LocalViewConfiguration.current.touchSlop
    val display =
        if (drag.id != null)
            drag.order.mapNotNull { label -> draft.firstOrNull { it.label == label } }
        else draft
    LaunchedEffect(drag.id, drag.pointer) {
        if (drag.id != null) {
            while (true) {
                val start = list.layoutInfo.viewportStartOffset
                val end = list.layoutInfo.viewportEndOffset
                val amount =
                    when {
                        drag.pointer < start + 40 -> -12f
                        drag.pointer > end - 40 -> 12f
                        else -> 0f
                    }
                if (amount == 0f || list.scrollBy(amount) == 0f) break
                drag.cross(amount, list)
                delay(16)
            }
        }
    }
    SettingsDrawer(
        stringResource(R.string.ctrl_shortcuts_title),
        stringResource(R.string.close_ctrl_shortcuts),
        onClose,
        hazeState,
        fixed = true,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 600.dp)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ctrl_shortcuts_drag_hint),
                    Modifier.weight(1f),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { editing = draft.size }) {
                    ChromeSymbol(
                        ChromeIcon.ADD,
                        Modifier.size(16.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add_shortcut), style = SettingsType.detail)
                }
            }
            LazyColumn(
                state = list,
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 100.dp, max = 420.dp)
                        .semantics {
                            contentDescription = context.getString(R.string.a11y_ctrl_shortcut_list)
                        }
                        .pointerInput(Unit) {
                            detectTabDrag(
                                start = { drag.start(latest.map { it.label }, list, it.y) },
                                move = { drag.move(it.y, slop, list) },
                                end = {
                                    if (drag.changed())
                                        update(
                                            drag.order.mapNotNull { label ->
                                                latest.firstOrNull { it.label == label }
                                            },
                                        )
                                    drag.cancel()
                                },
                                cancel = drag::cancel,
                            )
                        },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(display, key = { it.label }) { item ->
                    val at = draft.indexOf(item)
                    val editDesc = stringResource(R.string.a11y_edit_ctrl_shortcut, item.label)
                    val removeDesc = stringResource(R.string.a11y_remove_ctrl_shortcut, item.label)
                    val moveUpLabel = stringResource(R.string.move_up)
                    val moveDownLabel = stringResource(R.string.move_down)
                    Row(
                        Modifier.fillMaxWidth()
                            .graphicsLayer { translationY = drag.translation(item.label, list) }
                            .zIndex(if (drag.id == item.label) 1f else 0f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                            ) // alpha-ok item surface
                            .clickable(role = Role.Button) { editing = at }
                            .semantics {
                                contentDescription = editDesc
                                customActions = buildList {
                                    if (at > 0)
                                        add(
                                            CustomAccessibilityAction(moveUpLabel) {
                                                update(
                                                    draft.toMutableList().apply {
                                                        add(at - 1, removeAt(at))
                                                    },
                                                )
                                                true
                                            },
                                        )
                                    if (at < draft.lastIndex)
                                        add(
                                            CustomAccessibilityAction(moveDownLabel) {
                                                update(
                                                    draft.toMutableList().apply {
                                                        add(at + 1, removeAt(at))
                                                    },
                                                )
                                                true
                                            },
                                        )
                                }
                            }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                item.label,
                                style = SettingsType.row,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ControlKeyChips(ControlKeyRecorder.decode(item.keys))
                        }
                        ChromeButton(ChromeIcon.CLOSE, removeDesc, enabled = draft.size > 1) {
                            update(draft - item)
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
                TextButton(onClick = { update(ControlShortcutConfig.defaults) }) {
                    Text(stringResource(R.string.reset_defaults), style = SettingsType.detail)
                }
                Button(
                    onClick = {
                        error = onSave(draftText)
                        if (error == null) onClose()
                    },
                ) {
                    Text(stringResource(R.string.save), style = SettingsType.row)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlKeyChips(keys: List<RecordedControlKey>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEachIndexed { index, key ->
            if (index > 0)
                Text(
                    "→",
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Text(
                key.chip(index),
                Modifier.clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f))
                    .padding(horizontal = 6.dp, vertical = 2.dp), // alpha-ok decorative chip tint
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlShortcutRecorder(
    item: ControlShortcut?,
    onCancel: () -> Unit,
    onDone: (ControlShortcut) -> String?,
    hazeState: HazeState?,
) {
    var name by rememberSaveable {
        mutableStateOf(
            item?.label?.takeIf { it != ControlShortcutConfig.label(item.keys) }.orEmpty(),
        )
    }
    var sequence by rememberSaveable { mutableStateOf(item?.keys.orEmpty()) }
    val keys = ControlKeyRecorder.decode(sequence)
    var nextControl by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun record(token: String, ctrl: Boolean = nextControl, shift: Boolean = false) {
        val next = ControlKeyRecorder.decode(sequence) + RecordedControlKey(token, ctrl, shift)
        val encoded = ControlKeyRecorder.encode(next)
        runCatching { ControlShortcutConfig.decode(encoded) }
            .onSuccess {
                sequence = encoded
                nextControl = false
                error = null
            }
            .onFailure { error = it.message }
    }
    SettingsDrawer(
        if (item == null) stringResource(R.string.add_ctrl_shortcut)
        else stringResource(R.string.edit_ctrl_shortcut),
        stringResource(R.string.cancel_ctrl_recording),
        {
            keyboard?.hide()
            onCancel()
        },
        hazeState,
        fixed = true,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.ctrl_recording_hint),
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (keys.isEmpty())
                Text(
                    stringResource(R.string.no_keys_recorded),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ControlKeyChips(keys)
            OutlinedTextField(
                "",
                onValueChange = { text ->
                    text.forEach { record(ControlKeyRecorder.token(it), shift = it.isUpperCase()) }
                },
                modifier =
                    Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != KeyEvent.ACTION_DOWN) false
                        else {
                            val token =
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_SPACE -> "Space"
                                    KeyEvent.KEYCODE_TAB -> "Tab"
                                    KeyEvent.KEYCODE_ESCAPE -> "Esc"
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
                                    KeyEvent.KEYCODE_DEL -> "Backspace"
                                    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
                                    KeyEvent.KEYCODE_DPAD_UP -> "Up"
                                    KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
                                    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
                                    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
                                    KeyEvent.KEYCODE_MOVE_END -> "End"
                                    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
                                    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
                                    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                                        "F${native.keyCode-KeyEvent.KEYCODE_F1+1}"
                                    else ->
                                        native
                                            .getUnicodeChar(
                                                native.metaState and KeyEvent.META_CTRL_MASK.inv(),
                                            )
                                            .takeIf { it > 0 }
                                            ?.toChar()
                                            ?.let(ControlKeyRecorder::token)
                                }
                            if (token == null) false
                            else {
                                record(
                                    token,
                                    native.isCtrlPressed || nextControl,
                                    native.isShiftPressed,
                                )
                                true
                            }
                        }
                    },
                label = { Text(stringResource(R.string.press_keys)) },
                placeholder = { Text(stringResource(R.string.press_keys_placeholder)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { record("Enter") }),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(
                        "Space",
                        "Tab",
                        "Esc",
                        "Enter",
                        "Up",
                        "Down",
                        "Left",
                        "Right",
                        "Backspace",
                        "Delete",
                    )
                    .forEach { token ->
                        OutlinedButton(
                            onClick = { record(token) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(token, style = SettingsType.detail)
                        }
                    }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    nextControl,
                    { nextControl = !nextControl },
                    label = {
                        Text(
                            stringResource(R.string.ctrl_for_next_key),
                            style = SettingsType.detail,
                        )
                    },
                )
                TextButton(
                    onClick = {
                        sequence = ControlKeyRecorder.encode(keys.dropLast(1))
                        error = null
                    },
                    enabled = keys.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.undo), style = SettingsType.detail)
                }
                TextButton(
                    onClick = {
                        sequence = ""
                        error = null
                    },
                    enabled = keys.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.clear), style = SettingsType.detail)
                }
            }
            OutlinedTextField(
                name,
                {
                    name = it
                    error = null
                },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.name_optional)) },
                singleLine = true,
            )
            error?.let {
                Text(it, style = SettingsType.detail, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        keyboard?.hide()
                        onCancel()
                    },
                ) {
                    Text(stringResource(R.string.cancel), style = SettingsType.row)
                }
                Button(
                    onClick = {
                        runCatching { ControlKeyRecorder.item(name, keys) }
                            .onSuccess { entry ->
                                error = onDone(entry)
                                if (error == null) keyboard?.hide()
                            }
                            .onFailure { error = it.message }
                    },
                    enabled = keys.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.done), style = SettingsType.row)
                }
            }
        }
    }
}
