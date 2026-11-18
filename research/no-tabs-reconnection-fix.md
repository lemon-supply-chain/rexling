# Research: Fixing Endless Reconnection When No Tabs Exist

## Overview
When a Rex session contains no open tabs/panes (e.g. upon connecting to an empty session, or after all tabs/blocks in a session have exited or been closed), the engine previously entered an infinite reconnection loop, generating continuous connection and disconnection cycles on the Rex server.

## Root Cause Analysis

### 1. Spurious `program_task` WebSocket Cycles on Empty Window Lists
In [engine.rs](file:///Users/xiaobo/dev/app/rexling/rexling-core/jni/src/engine.rs#L572-L582), the engine periodically (every 4 seconds) refreshes foreground program and process metadata for active tabs by creating a background `program_task`:
```rust
if self.phase == "connected" && self.program_task.is_none() && !self.selected_session.is_empty()
    && self.program_refresh <= tokio::time::Instant::now()
{
    let ids: Vec<String> = self.windows.as_array().into_iter().flatten().flat_map(|w| ...).collect();
    self.program_task = Some(program_task(self.endpoint.clone().unwrap(), self.selected_session.clone(), ids));
    self.program_refresh = tokio::time::Instant::now() + Duration::from_secs(4);
}
```
`program_task` establishes an independent auxiliary `Control` WebSocket connection to Rex (`Control::connect`), attaches to the session, queries process information for each ID in `ids`, and terminates.

When a session has **no tabs** (`self.windows` is empty and `ids.is_empty()`):
- `program_task` still unconditionally dialed the Rex server over WebSocket.
- It called `client.set_label` (`rex-android-programs`) and `session.attach`.
- Because `ids` was empty, it immediately concluded and dropped the connection.
- 4 seconds later, `program_refresh` expired, spawning another task that dialed and disconnected again.
- To server-side monitoring, this presented as constant, endless reconnection.

### 2. Stream Close Code 4000 Erroneously Escalated to Full Transport Recovery
Under the Rex protocol specification (documented in [PROTOCOL.md](file:///Users/xiaobo/dev/app/rexling/rexling-core/probe/PROTOCOL.md#L294) and `server-wire-guide-r8.md`):
- `4000 block closed`: The terminal block has closed. Do not reconnect.
- `1000 stream ended`: Normal data stream termination.

When the last terminal process in a session terminated (e.g., via `exit` or shell termination), Rex closed the data WebSocket with code 4000. In [engine.rs](file:///Users/xiaobo/dev/app/rexling/rexling-core/jni/src/engine.rs#L485-L505), `d.read()` returned `Err(e)`. The event loop previously treated all data read errors identically:
```rust
Err(e) => self.recover(e)
```
Calling `recover(e)` dropped the healthy primary `Control` connection, marked `self.phase = "reconnecting"`, and engaged exponential backoff retry. After reconnecting, the session had 0 tabs, which then triggered the periodic `program_task` cycle from Root Cause #1.

### 3. Deleted Session Attach Failures Escalated to Connection Recovery
If a previously selected session was destroyed on the server during reconnect, `connected()` called `self.control.attach(&session).await?`. The resulting `session_not_found` RPC error failed `connected()`, causing `recover()` to schedule another connection attempt that would immediately fail again on the same stale session ID.

## Implementation & Fixes

1. **Guard `program_task` on Non-Empty Block IDs**:
   In [engine.rs](file:///Users/xiaobo/dev/app/rexling/rexling-core/jni/src/engine.rs#L572-L582), check `if !ids.is_empty()` before creating `program_task`. If there are no tabs to poll, skip the background connection entirely. When a tab is subsequently opened, `set_view` detects the changed window IDs and resets `program_refresh = Instant::now()`, immediately fetching program metadata.

2. **Differentiate Normal Terminal Closure from Transport Failure**:
   In [engine.rs](file:///Users/xiaobo/dev/app/rexling/rexling-core/jni/src/engine.rs#L494-L510), inspect data stream errors with `is_data_stream_closed(&e)`:
   ```rust
   fn is_data_stream_closed(error: &anyhow::Error) -> bool {
       let msg = error.to_string();
       msg.contains("4000") || msg.contains("block closed") || msg.contains("code: 1000")
   }
   ```
   If a stream close code 4000 or 1000 is received, clear `self.data`, `self.selected_block`, and `self.replica`, set `self.notice = "That terminal has closed"`, and refresh the view without dropping the control connection or setting `phase = "reconnecting"`.

3. **Handle `missing_terminal` during Session Attach**:
   In [engine.rs](file:///Users/xiaobo/dev/app/rexling/rexling-core/jni/src/engine.rs#L685-L700), gracefully catch `missing_terminal` errors on `session.attach` and clear `selected_session` and `windows`, preventing infinite reconnect attempts against deleted sessions.

4. **Automated Regression Test Suite**:
   Restored and added tests in [engine.rs](file:///Users/xiaobo/dev/app/rexling/rexling-core/jni/src/engine.rs#L3430-L3495):
   - `block_closed_error_detection_matches_rex_wire_protocol`: Validates close codes 4000 and 1000 detection.
   - `an_actor_without_a_data_stream_sleeps_between_maintenance_turns`: Verifies an idle actor without a data stream or tabs stays parked and does not spin.
   - `a_reconnecting_actor_waits_for_its_backoff_without_spinning`: Verifies backoff sleep behavior.

## Verification
- `cargo test --manifest-path rexling-core/jni/Cargo.toml`: 89 passed, 15 ignored (0 failures).
- `cargo test --manifest-path rexling-core/Cargo.toml`: 46 passed, 3 ignored (0 failures).
- `cd rexling-android && ./gradlew test`: All 27 tasks passed (`BUILD SUCCESSFUL`).
