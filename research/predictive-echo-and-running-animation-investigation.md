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

## 4. Proposed Fixes

### 4.1 Predictive Echo Immediate Frame Publishing
When `value["type"] == "input"`:
1. If `self.predictor.has_pending()`:
   - Compute `display(current_screen)` immediately from the latest `self.screen` or base frame.
   - Publish the speculative frame to Android immediately (`self.publish_frame()`), without waiting for `replica.terminal().render_ready()`.
2. Upon server frame arrival, `observe(...)` will cleanly confirm or drop the speculative cells and restore authoritative server state.

### 4.2 Running Animation Re-evaluation
1. Provide a subtle, low-frequency pulse for inactive working tabs rather than completely freezing them to `settledMarquee` with phase 0.
2. Optimize `program_task` RPC batching or multiplexing to prevent high-latency timeouts.
