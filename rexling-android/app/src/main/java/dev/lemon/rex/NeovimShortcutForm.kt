package dev.lemon.rex

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NeovimShortcutChips(keys: String) {
    val tokens = if (keys.startsWith(':')) listOf(keys) else NeovimKeyRecorder.tokens(keys)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tokens.take(12).forEach { token ->
            Text(
                NeovimKeyRecorder.label(token),
                Modifier.clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.primary,
            )
        } // alpha-ok decorative chip tint
        if (tokens.size > 12)
            Text(
                "+${tokens.size-12}",
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NeovimShortcutForm(
    item: NeovimShortcut?,
    defaultGroup: String,
    groups: List<String>,
    onCancel: () -> Unit,
    onDone: (NeovimShortcut) -> String?,
    hazeState: HazeState?,
) {
    var commandMode by rememberSaveable { mutableStateOf(item?.keys?.startsWith(':') ?: true) }
    var command by rememberSaveable {
        mutableStateOf(item?.keys?.takeIf { it.startsWith(':') }?.drop(1).orEmpty())
    }
    var sequence by rememberSaveable {
        mutableStateOf(item?.keys?.takeIf { !it.startsWith(':') }.orEmpty())
    }
    var name by rememberSaveable {
        mutableStateOf(item?.label?.takeIf { it != item.keys }.orEmpty())
    }
    var group by rememberSaveable { mutableStateOf(item?.group ?: defaultGroup) }
    var groupMenu by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun record(token: String, control: Boolean = ctrl, meta: Boolean = alt) {
        val value =
            when {
                (control || meta) && token.startsWith('<') ->
                    "<${if(control) "C" else "A"}-${token.drop(1).dropLast(1)}>"
                control || meta -> "<${if(control) "C" else "A"}-$token>"
                else -> token
            }
        runCatching { NeovimShortcuts.decodeKeys(sequence + value) }
            .onSuccess {
                sequence += value
                ctrl = false
                alt = false
                error = null
            }
            .onFailure { error = it.message }
    }
    fun finish() {
        val keys = if (commandMode) ":" + command.trim().removePrefix(":") else sequence
        if (commandMode && keys == ":") {
            error = "Enter a command."
            return
        }
        val entry = NeovimShortcut(name.trim().ifBlank { keys }, keys, group)
        runCatching {
            NeovimShortcuts.parse(NeovimShortcuts.editorText(listOf(entry))).single().also { parsed
                ->
                require(parsed == entry) {
                    "Give this shortcut a name without ' = ' to preserve its keys."
                }
            }
        }
            .onSuccess {
                error = onDone(it)
                if (error == null) keyboard?.hide()
            }
            .onFailure { error = it.message }
    }
    SettingsDrawer(
        if (item == null) stringResource(R.string.add_shortcut) else stringResource(R.string.edit),
        stringResource(R.string.cancel),
        {
            keyboard?.hide()
            onCancel()
        },
        hazeState,
        swipeToDismiss = false,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    commandMode,
                    {
                        commandMode = true
                        error = null
                    },
                    label = { Text(stringResource(R.string.command), style = SettingsType.row) },
                )
                FilterChip(
                    !commandMode,
                    {
                        commandMode = false
                        error = null
                    },
                    label = {
                        Text(stringResource(R.string.key_sequence), style = SettingsType.row)
                    },
                )
            }
            if (commandMode) {
                OutlinedTextField(
                    command,
                    {
                        command = it
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.neovim_command)) },
                    prefix = { Text(":") },
                    placeholder = { Text("w") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                Text(
                    stringResource(R.string.command_mode_hint),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.key_sequence_hint),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sequence.isBlank())
                    Text(
                        stringResource(R.string.no_keys_recorded),
                        style = SettingsType.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else NeovimShortcutChips(sequence)
                OutlinedTextField(
                    "",
                    { text -> text.forEach { record(NeovimKeyRecorder.char(it)) } },
                    Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        if (key.action != KeyEvent.ACTION_DOWN) false
                        else {
                            val token =
                                when (key.keyCode) {
                                    KeyEvent.KEYCODE_SPACE -> "<Space>"
                                    KeyEvent.KEYCODE_TAB ->
                                        if (key.isShiftPressed) "<S-Tab>" else "<Tab>"
                                    KeyEvent.KEYCODE_ESCAPE -> "<Esc>"
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> "<CR>"
                                    KeyEvent.KEYCODE_DEL -> "<BS>"
                                    KeyEvent.KEYCODE_FORWARD_DEL -> "<Del>"
                                    KeyEvent.KEYCODE_DPAD_UP -> "<Up>"
                                    KeyEvent.KEYCODE_DPAD_DOWN -> "<Down>"
                                    KeyEvent.KEYCODE_DPAD_LEFT -> "<Left>"
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> "<Right>"
                                    KeyEvent.KEYCODE_MOVE_HOME -> "<Home>"
                                    KeyEvent.KEYCODE_MOVE_END -> "<End>"
                                    KeyEvent.KEYCODE_PAGE_UP -> "<PageUp>"
                                    KeyEvent.KEYCODE_PAGE_DOWN -> "<PageDown>"
                                    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                                        "<F${key.keyCode-KeyEvent.KEYCODE_F1+1}>"
                                    else ->
                                        key.getUnicodeChar(
                                                key.metaState and KeyEvent.META_CTRL_MASK.inv(),
                                            )
                                            .takeIf { it > 0 }
                                            ?.toChar()
                                            ?.let(NeovimKeyRecorder::char)
                                }
                            if (token == null) false
                            else {
                                record(token, key.isCtrlPressed || ctrl, key.isAltPressed || alt)
                                true
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.press_keys)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { record("<CR>") }),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf(
                            "Space" to "<Space>",
                            "Tab" to "<Tab>",
                            "Esc" to "<Esc>",
                            "Enter" to "<CR>",
                            "↑" to "<Up>",
                            "↓" to "<Down>",
                            "←" to "<Left>",
                            "→" to "<Right>",
                            "Backspace" to "<BS>",
                        )
                        .forEach { (label, token) ->
                            OutlinedButton(
                                onClick = { record(token) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(label, style = SettingsType.detail)
                            }
                        }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        ctrl,
                        {
                            ctrl = !ctrl
                            if (ctrl) alt = false
                        },
                        label = {
                            Text(stringResource(R.string.ctrl_next), style = SettingsType.detail)
                        },
                    )
                    FilterChip(
                        alt,
                        {
                            alt = !alt
                            if (alt) ctrl = false
                        },
                        label = {
                            Text(stringResource(R.string.alt_next), style = SettingsType.detail)
                        },
                    )
                    TextButton(
                        onClick = {
                            sequence =
                                NeovimKeyRecorder.tokens(sequence).dropLast(1).joinToString("")
                            error = null
                        },
                        enabled = sequence.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.undo), style = SettingsType.detail)
                    }
                    TextButton(
                        onClick = {
                            sequence = ""
                            error = null
                        },
                        enabled = sequence.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.clear), style = SettingsType.detail)
                    }
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
            val ungroupedText = stringResource(R.string.ungrouped)
            Box {
                TextButton(onClick = { groupMenu = true }) {
                    Text(
                        stringResource(R.string.group_format, group.ifBlank { ungroupedText }),
                        style = SettingsType.row,
                    )
                    Spacer(Modifier.width(6.dp))
                    ChromeSymbol(
                        ChromeIcon.CHEVRON,
                        Modifier.size(16.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(groupMenu, { groupMenu = false }) {
                    (listOf("") + groups).forEach { choice ->
                        DropdownMenuItem(
                            text = {
                                Text(choice.ifBlank { ungroupedText }, style = SettingsType.row)
                            },
                            onClick = {
                                group = choice
                                groupMenu = false
                            },
                        )
                    }
                }
            }
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
                Button(onClick = ::finish) {
                    Text(stringResource(R.string.done), style = SettingsType.row)
                }
            }
        }
    }
}
