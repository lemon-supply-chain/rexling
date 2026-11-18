# Agent Execution Tracking in Rex

## Overview

Rex and its client Rexling track what runs inside each terminal pane through a two-layer architecture:
1. **Process Layer (Identity Detection)**: Identifying the foreground process and running executable via operating system PTY process-group tracking and the `process_changed` RPC event.
2. **Status Layer (Execution & Lifecycle State)**: Tracking whether an agent is actively working, blocked waiting for user interaction/auth, idle, or completed via the **OSC 7501** control sequence protocol.

---

## 1. Process Layer: Foreground Identity

Rex server inspects the foreground process group on each terminal's PTY. When the foreground process changes or enters a new binary/script, Rex broadcasts a `process_changed` event over its control protocol.

### Reported Process Metadata
```json
{
  "foreground": {
    "pid": 12345,
    "name": "node",
    "path": "/usr/local/bin/node",
    "invoked_path": "/usr/local/bin/node",
    "argv0": "node",
    "script": "/Users/xiaobo/.gemini/antigravity/bin/agy",
    "cwd": "/Users/xiaobo/project",
    "user": "xiaobo"
  },
  "ancestors": [...]
}
```

### Identification in Rexling
In `rexling-core/jni/src/engine.rs`:
- `program_candidates` extracts names from `[script, argv0, invoked_path, path, name]`.

In `rexling-android`:
- **`agy` (Antigravity CLI)**:
  `ProgramIdentity.kt` explicitly checks for Antigravity:
  ```kotlin
  name in listOf("antigravity", "agy", "antigravity-cli") ||
  name.startsWith("antigravity-") ||
  path.contains("/antigravity") ||
  path.contains("/.gemini/antigravity") -> ProgramKind.ANTIGRAVITY
  ```
  Resolves to `ProgramKind.ANTIGRAVITY`, which binds to the dedicated purple gradient card tile and glyph.
- **`pi` (pi-coding-agent)**:
  - Registered in `ProgramVocabulary.kt` (`executables` set contains `"pi"`).
  - Matches the `pi.svg` artwork from the Superlogical catalog.
  - In `rexling-core/jni/src/prediction.rs`:
    ```rust
    if name == "pi" || path.contains("/pi-coding-agent/") {
        return Program::Pi;
    }
    ```
    Recognized specially to tune local predictive echo for Pi's TUI input handling (handling hidden cursor and Enter submissions).

---

## 2. Status Layer: Agent Execution State (OSC 7501)

Rex defines the `com.superlogical.terminal.program_status` API and `program_status_changed` event. Programs in the terminal report dynamic lifecycle status using **OSC 7501** escape sequences:

```sh
printf '\033]7501;state=working:app=agy:title=<base64_title>\007'
```

### Supported States
| State | Meaning | Rex / Rexling Reaction |
|---|---|---|
| `working` | Agent is reasoning, streaming tokens, or executing tools | Added to `workingBlocks`; tab & pane chrome show animated traveling highlight/breathing glow (`WorkingIndicator.kt`) |
| `blocked` | Agent requires human input, auth, or permission | Marked as "Needs you"; dispatches push notifications via `AttentionAlerts.kt` when backgrounded |
| `done` | Task completed | Displays completion badge; clears working highlight |
| `error` | Failure encountered | Retained in activity ledger as an error event |
| `idle` | Waiting at prompt | Clears working indicator |

### Native Working & Progress Detection
In `rexling-core/jni/src/engine.rs`:
```rust
fn program_working(process: &Value, status: &Value) -> bool {
    let foreground = &process["foreground"];
    foreground["pid"].is_number()
        && foreground["name"].is_string()
        && status["records"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|record| {
                record["owner"]["pid"] == foreground["pid"]
                    && record["owner"]["name"] == foreground["name"]
                    && record["state"] == "working"
            })
}

fn program_progress(process: &Value, status: &Value) -> Option<u64> {
    let foreground = &process["foreground"];
    if !foreground["pid"].is_number() || !foreground["name"].is_string() {
        return None;
    }
    status["records"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|record| {
            record["owner"]["pid"] == foreground["pid"]
                && record["owner"]["name"] == foreground["name"]
                && record["state"] == "working"
        })
        .find_map(|record| record["progress"].as_u64().filter(|p| *p <= 100))
}
```

### Dynamic Countdown & Progress Pie Icon
When a foreground command reports `progress` (e.g., `timer` countdown or build tasks), Rexling bridges this progress into `terminalProgress`:
- In `rexling-core`: Foreground OSC 7501 `progress` merges into `terminalProgress` exposed to the frontend whenever no conflicting OSC 9;4 sequence overrides it.
- In `rexling-android`: For programs without static catalog icon artwork in `program_tiles.json` (such as `timer`), `PaneProgramTile` in `ProgramStack.kt` automatically renders a dynamic **Countdown / Progress Pie Tile** (`CountdownIcon`). The dial visually sweeps from 12 o'clock clockwise based on current percentage, eliminating the need to create static tile/glyph artwork mappings for countdown/timer CLI utilities.

## 3. Shell Script Wrappers, Subshells & Compound Commands (`mole`, `mo`)

### Why Terminal Title Parsing Fails
Earlier experiments attempted to extract executable candidates from `terminal.title` or track title ownership across process ancestor hierarchies. However, this approach inherently breaks in real-world shell usage:
1. **Compound & Chained Commands (`&&`, `;`, `|`)**:
   - Shell hooks (such as `zsh` Oh My Zsh `termsupport`) trigger `preexec` once when the command line is submitted.
   - For chained commands (e.g. `mo update && brew upgrade`), the terminal title is set once to `mo update` (or `update`). When `mo` completes and `brew upgrade` runs, the terminal title remains stuck on the first command, causing Rexling to display the wrong icon across subsequent commands.
2. **Subshell & Child Command Pollution**:
   - If an ancestor's title overrides child processes, any subshell or auxiliary command (`sh`, `perl`, `sudo`, `curl`) spawned by a script is masked, making it impossible to observe real foreground transitions.

### Robust Architecture: Scheme A + Scheme C

#### Scheme A: Strict Process-Driven Engine Identity
In `rexling-core/jni/src/engine.rs`:
- Reverted all title-guessing heuristics in `program_candidates`.
- Program identity is strictly derived from:
  1. Active OS foreground process metadata: `[script, argv0, invoked_path, path, name]`.
  2. OSC 7501 status records (`status.records`) where `record.owner.pid == foreground.pid` and `record.owner.name == foreground.name`.
- Eliminates ghost titles, avoids command shadowing, and maintains 100% deterministic process isolation.

#### Scheme C: OSC 7501 Protocol Integration
For shell scripts or custom CLI tools where the OS only sees an interpreter binary (e.g. `bash` or `sh`), the program explicitly signals its identity and lifecycle via OSC 7501.

Because Rex binds OSC 7501 records directly to the process's PID:
- When the script starts, it emits `state=working:app=mole`.
- When the script finishes, the process terminates.
- In compound commands (`mo && next_cmd`), the second command runs under a different PID, so the old OSC 7501 record is immediately ignored by `program_candidates` and `program_working`, seamlessly displaying the true foreground program.

Example Shell Wrapper for `~/.zshrc`:
```zsh
mo() {
    printf '\033]7501;state=working:app=mole\007'
    command mo "$@"
    local rc=$?
    printf '\033]7501;state=idle\007'
    return $rc
}
alias mole=mo
```

---

## 4. Applicability to `agy` and `pi`

1. **Running Status (Is the process alive?)**:
   - Fully supported out-of-the-box for both `agy` and `pi`.
   - Rex and Rexling accurately distinguish when `agy` or `pi` is running in the foreground of a pane, showing their dedicated icons and titles.

2. **Active Work State (Is the agent thinking vs. waiting for user input?)**:
   - Currently, agents like Codex and Claude Code emit or are parsed for OSC 7501 / task-title signals.
   - For `agy` and `pi`:
     - If they do **not** emit OSC 7501 escape sequences, Rex only observes that their process is active in the foreground; it cannot infer purely from terminal bytes whether the LLM is generating tokens or idling at an input prompt.
     - **Enabling Full Live Tracking**: If `agy` or `pi` (via CLI hooks or wrapper scripts) emits OSC 7501 sequences:
       - On prompt start: `\033]7501;state=idle:app=agy\007`
       - On task execution: `\033]7501;state=working:app=agy:title=<base64>\007`
       - On asking questions / permission: `\033]7501;state=blocked:kind=question:app=agy\007`
       Rex and Rexling will automatically display real-time breathing highlights and send background attention alerts.
