# Investigation: Predictive Local Echo Pipeline & Running Animation Idle Stagnation

## 1. Issue Overview

Two symptoms were reported during high-latency remote sessions:
1. **Running Animation Idle Stagnation**: The "running" / working animation (rainbow marquee / traveling highlight bar) does not animate / flash unless explicitly triggered.
2. **Predictive Local Echo Ineffective**: On high-latency (DERP-relayed) connections, typing feels unassisted; local predictive echo does not appear on screen ahead of server echo.

---

## 2. Root Cause Analysis: Predictive Local Echo Failure

### 2.1 The Speculative Render Gap in `engine.rs`

In Rexling, Mosh-style predictive local echo was introduced in `3ba9116` (`prediction.rs`), maintaining speculative cell overlays in `self.predictor`.

However, the event loop and render pipeline in `rexling-core/jni/src/engine.rs` contains a fundamental architectural disconnect:

1. **Typing does not dirty the frame**:
   In `engine.rs` (`execute_command`):
   ```rust
   if value["type"] == "input" {
       let program = crate::prediction::program(&self.programs[target.block.as_str()]);
       self.predictor.input(&prediction_key(&target), text, program, std::time::Instant::now());
   } else if !click {
       self.predictor.reset();
   }
   let scrolled_back = self.screen.as_ref().is_some_and(|f| f.scroll_offset + f.scroll_visible < f.scroll_total);
   if !click && let Some(replica) = self.replica.as_mut().filter(|r| r.ready()) {
       replica.terminal()?.scroll_to_bottom();
       if scrolled_back {
           self.dirty = true;
       }
   }
   ```
   During normal typing, `self.dirty` is **never set to `true`** unless the user is scrolled back into history.

2. **Frame publishing is gated on server-driven Ghostty damage**:
   In `engine.rs` main render loop:
   ```rust
   if self.visible && (self.dirty || animation_at.is_some_and(|at| at <= now)) {
       self.dirty = false;
       if let Some(replica) = self.replica.as_mut().filter(|r| r.ready()) {
           match replica.terminal().and_then(|t| if t.render_ready()? { t.frame().map(Some) } else { Ok(None) }) {
               Ok(Some(frame)) => {
                   let frame = Arc::new(frame);
                   let target = self.input_target();
                   self.predictor.retarget(&prediction_key(&target));
                   self.predictor.observe(frame.clone(), std::time::Instant::now());
                   self.screen = Some(self.predictor.display(frame));
                   self.publish_frame();
               }
               Ok(None) => { self.dirty = true; }
               Err(e) => { ... }
           }
       }
   }
   ```
   - `t.render_ready()?` queries Ghostty's dirty/damage bit.
   - When a key is typed on the client, Ghostty has received zero terminal bytes from the PTY. Thus `t.render_ready()?` returns `false` (`Ok(None)`).
   - `self.publish_frame()` is **only** invoked after network packets arrive from the remote server and feed into Ghostty.
   - By the time remote packets arrive, the server frame **already contains the real echoed characters**.
   - `self.predictor.observe(...)` compares the server frame with pending predictions, flags them as confirmed, and drops them.
   - **Conclusion**: The speculative overlay produced by `self.predictor.display(...)` is never painted to the Android UI during the latency interval between keypress and network arrival.

---

## 3. Root Cause Analysis: Running Animation Idle Stagnation

### 3.1 Active-Only Animation Gating

In optimizations `f147276` and `5c71b34`:
1. In `TerminalChrome.kt`:
   ```kotlin
   marquee = if (active || marquee == null) marquee else settledMarquee
   ```
   Non-active (background) tabs are given `settledMarquee = remember { RainbowMarquee(mutableStateOf(0f), mutableStateOf(0f)) }`. Their marquee phase is locked to `0f`, eliminating all rotation and breathing light.
2. In `TerminalChrome.kt`:
   ```kotlin
   if (active) {
       drawWorkingBar(..., phase = if (busy && animateWorking) workPhase.value else null)
   }
   ```
   The working progress bar is drawn **strictly on the active tab**. Inactive background tabs do not render the bar at all.

### 3.2 Detection Lag & High-Latency Polling

1. **OSC 7501 Process Binding**:
   A block is only marked `working` if `foreground["pid"]` matches a record in `status["records"]` with `state == "working"`. Standard shell commands (e.g. `sleep`, `make`, `git clone`) do not produce OSC 7501 working records, so `busy` remains `false`.
2. **4-Second Polling Loop (`program_task`)**:
   `engine.rs` queries process and status metadata every 4 seconds via separate RPC calls over SSH/Tailscale. On high-latency relay paths (200-500ms RTT), sequential RPCs can take 2-4 seconds or hit the 8-second timeout, delaying or dropping the working state flag until local user activity triggers a view refresh.

---

## 4. Implementation & Fix Verification

### 4.1 Predictive Echo Immediate Speculative Frame Publishing (`engine.rs` & `prediction.rs`)
1. **Immediate Speculative Publishing**:
   In `engine.rs` (`send_input`), when `value["type"] == "input"`:
   - Call `self.predictor.input(...)` to record predictions.
   - If `self.predictor.base()` exists, compute speculative frame `self.predictor.display(base.clone())`.
   - If the frame changed (`!self.screen.as_ref().is_some_and(|s| Arc::ptr_eq(s, &frame))`), immediately update `self.screen` and invoke `self.publish_frame()`.
   - Result: Keypress characters render in 0ms locally, ahead of server round trip.
2. **Visual Underline Integration**:
   - In `prediction.rs` (`display`), when `self.flagging` is true (RTT > 80ms), flagged character predictions receive standard underline bit `1 << 6` alongside `PREDICTED`, integrating directly into Android's native `TerminalView` draw routines.
   - When server echo confirms the characters, authoritative server frame attributes automatically replace the underline.
3. **Reset and Expiration Handling**:
   - In `engine.rs` `expire` and `set_mode(Mode::Off)`: when predictions expire or prediction is turned off, `self.screen` reverts immediately to `base` and publishes, wiping out stale predictions without waiting for remote socket traffic.
4. **Unit Test Verification**:
   - Added end-to-end unit test `predictive_echo_publishes_speculative_frame_immediately_on_input` in `engine.rs`, asserting immediate speculative frame dispatch on keypress.
   - All 108 tests in `rexling-core/jni` passed.

---

## 5. UI Tab Indicator & Running Animation Verification

1. **Tab Indicator Fix**:
   - [TerminalChrome.kt](file:///Users/xiaobo/Dev/app/rexling/rexling-android/app/src/main/java/dev/lemon/rex/TerminalChrome.kt) and [TabRail.kt](file:///Users/xiaobo/Dev/app/rexling/rexling-android/app/src/main/java/dev/lemon/rex/TabRail.kt) draw the bottom 2dp bar strictly for `active` tabs (`if (active)`). Unselected tabs never draw a bottom bar.
2. **Background Running Animation**:
   - Inactive busy tabs animate their icon's rainbow marquee ring via `ProgramStack.kt`, without rendering any bottom bar.
3. **Compilation**:
   - JNI libraries built via `build_native.py` for `aarch64-linux-android`.
   - Release APK compiled at `rexling-android/app/build/outputs/apk/release/app-release.apk` (27.9 MB).
