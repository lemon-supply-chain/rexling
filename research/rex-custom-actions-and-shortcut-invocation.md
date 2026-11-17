# Rex Custom Actions & Shortcut Invocation

## 1. Overview

Rex allows configuring custom Lua actions in `init.lua` (such as `dev_layout`, `git_diff`, etc.) that execute on the server under the `client.action` RPC. In the Rexling mobile client, users can trigger these actions directly via shortcuts (or wheel menu items) rather than sending raw keystrokes into the terminal PTY.

This document details:
1. The root causes behind `dev_layout` previously failing to take effect.
2. The argument grammar supporting `[rex do] <name> [key=value ...]`.
3. The end-to-end execution flow across Kotlin, Rust JNI Engine, and the Rex server.

---

## 2. Root Causes of `dev_layout` Not Taking Effect

### 2.1 Invalid Target Check in `RexConnection.kt`
In `RexConnection.kt`:
```kotlin
private fun runRexAction(action: String, current: AppState, target: String): Boolean {
    if(!visible || !foreground || !current.connected || current.creating || navigationRequest.pending || !current.controlled) return false
    if(target.isBlank() || target != keyTarget() || target != current.selectedBlock) return false
    ...
}
```
`target` passed to `shortcut()` is `keyTarget()`, formatted as `"epoch:serial:session:block"` (e.g. `0:1:session-1:block-1`). However, `current.selectedBlock` is simply `"block-1"`. As a result:
`target != current.selectedBlock` was always true!
`runRexAction` aborted immediately without sending the `action` RPC command.

**Fix**: Validate `target == keyTarget()`. The target block identity is already embedded and validated inside `keyTarget()`.

### 2.2 Wire Protocol Field Name
The Android connection command helper was previously passing `"text" to action` instead of `"action" to action`. The Rust engine JNI dispatcher expects `"action"` (with fallback to `"text"`).

### 2.3 Post-Execution View Refresh in the Engine
When custom actions like `dev_layout` execute, they create new windows or alter session pane layouts on the server:
```lua
rex.action.define("dev_layout", function(opts)
    local win = rex.session.new_window()
    ...
end)
```
In `rexling-core/jni/src/engine.rs`, the engine executed `self.control.call("client.action", args).await?`, but did not invoke `self.refresh_view().await?; self.publish();`. The mobile client remained on the old window state without fetching the newly created window until an unrelated state change occurred.

**Fix**: Trigger `self.refresh_view().await?; self.publish();` immediately after the action completes successfully.

---

## 3. Shortcut Syntax & Argument Parsing: `rex do name args`

### 3.1 Editor Notation (`:=`)
In shortcut profiles:
- `Name = keys`: Plain terminal input (e.g. `Save = :w<CR>`).
- `Name := action`: Custom Rex action (e.g. `Dev := dev_layout`).
- `!Name := action`: Custom Rex action with a confirmation prompt.

Both bare action names and `rex do` prefixes are supported interchangeably:
- `dev := dev_layout`
- `dev := rex do dev_layout`
- `dev := dev_layout ratio=0.6`
- `dev := rex do dev_layout ratio=0.6 branch=main`

### 3.2 Parsing Grammar in `rexling-core` (`action_call`)
The engine tokenizes the action string:
1. Strips any leading `rex do` (case-insensitive) if present as the first two tokens.
2. Extracts the first token as the action `name`.
3. Rejects empty action names (returning a user-visible error).
4. Parses any subsequent tokens formatted as `key=value`:
   - Integers (`i64`) and floats (`f64`) are parsed as numbers.
   - `true` and `false` are parsed as booleans.
   - `null` is parsed as JSON null.
   - JSON arrays/objects (starting with `{` or `[`) are parsed into structured JSON values.
   - Plain strings are preserved as string values.
5. Emits the wire payload:
   ```json
   {
     "action": "<name>",
     "session_id": "<session>",
     "block_id": "<block>",
     "origin": "palette",
     "args": {
       "key": value
     }
   }
   ```
   *Note: If no arguments are supplied, `"args"` is completely omitted, satisfying Rex block actions that fail with `bad_request` if an empty object `{}` is supplied.*

---

## 4. Test Coverage

- **Android Unit Tests**:
  - `RexActionsTest.kt`: Tests `:=` parsing, preservation of literal arguments without local validation, editor round-trip, JSON storage encoding, and prevention of terminal typing leaks.
- **Rust Engine Tests**:
  - `engine.rs` (`a_shortcut_runs_a_named_action_from_the_palette`):
    - `dev_layout` -> zero args
    - `rex do dev_layout ratio=0.6` -> `args: {"ratio": 0.6}`
    - `dev_layout ratio=0.6 branch=main` -> `args: {"ratio": 0.6, "branch": "main"}`
    - Rejects invalid empty action strings (`""`, `"rex do"`, `"rex do   "`).
