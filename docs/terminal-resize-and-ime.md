# Terminal Resize, Viewport Windowing, and IME Transitions

This document details the architecture and mechanisms implemented to deliver smooth, flicker-free viewport transitions, PTY resize handling, and ergonomic keyboard interactions during soft-keyboard (IME) animations on Android.

---

## 1. Background & Problem Statement

Mobile terminal emulators face unique challenges when interacting with on-screen keyboards (IMEs):

1. **Per-frame Height Changes**: During an IME opening or closing transition, the Android window insets update continuously (60–120 Hz over 200–300 ms).
2. **PTY Resize Flashes (SIGWINCH repaints)**: Terminal programs (shells, Neovim, tmux, top) respond to window size changes by repainting the entire screen. Emitting intermediate resizes during an animation triggers multiple full-screen repaints, causing noticeable screen tearing and flashing.
3. **Cursor and Prompt Occlusion**: If a viewport is anchored strictly at the top inset while the view height shrinks, the active command prompt and cursor are pushed below the visible bounds until the animation finishes.
4. **Redraw Cost**: Re-recording terminal row display lists (`RenderNode` / `Picture`) whenever view height changes causes expensive full-screen re-recordings every animation frame.
5. **Input Ordering Race**: If a user types immediately as the keyboard opens, key input packets must not arrive at the PTY before the adjusted terminal grid dimensions.

---

## 2. System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Android System (WindowInsetsAnimationCompat)                │
└──────────────┬───────────────────────────────┬──────────────┘
               │ onStart / onEnd               │ derivedStateOf
               ▼                               ▼
┌──────────────────────────────┐  ┌───────────────────────────┐
│ RexModel / RexConnection     │  │ Workspace (Jetpack Compose│
│ - PaneSize state machine     │  │ - avoids per-frame        │
│ - onImeClosing (eager)       │  │   recomposition           │
│ - flushPendingSize (settled) │  └───────────────────────────┘
│ - flushSize before input     │
└──────────────┬───────────────┘
               │ "resize" command
               ▼
┌─────────────────────────────────────────────────────────────┐
│ JNI Engine (rexling-core Actor)                             │
│ - RESIZE_SETTLE_CAP (130ms) & RESIZE_SETTLE_QUIET (30ms)    │
│ - Holds dirty frames until repaint burst finishes           │
└──────────────┬──────────────────────────────────────────────┘
               │ Updates & Frames
               ▼
┌─────────────────────────────────────────────────────────────┐
│ TerminalView & TerminalRowWindow                            │
│ - terminalRowWindow(): bottom-anchoring on height decrease  │
│ - Geometry cache key decoupled from view height             │
│ - onBottom auto-scroll on text input                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Core Mechanisms

### 3.1 Bottom-Anchored Viewport Windowing (`TerminalRowWindow`)

When the screen area shrinks faster than the PTY can resize, the grid row count exceeds the fitted visible rows.

- **Glued to Bottom**: When `rows > fittedRows`, the window is anchored to the bottom edge:
  $$\text{origin} = \text{insetTop} + \text{contentHeight} - \text{rows} \times \text{cellHeight}$$
  $$\text{firstRow} = \max(0, \text{rows} - \text{fittedRows})$$
  This ensures that the cursor row and prompt remain locked to the top edge of the virtual keyboard rather than being clipped off.
- **Anchored to Top**: When `rows <= fittedRows` (e.g. keyboard is collapsing before PTY restores full height), content anchors at `insetTop`. Blank space expands below the content instead of shifting text downwards and creating a blank void above it.
- **Accurate Inversion**: Coordinates for touch interactions, link clicking, and text selection invert properly via `TerminalRowWindow.row(y, cellHeight)`.

### 3.2 Display List Cache Independence from View Height

In hardware-accelerated Android `View` rendering, `TerminalView` caches row display recordings (`TerminalRowRecording`).

Previously, the cache validation key included the view `height`. As a result, every frame of an IME animation cleared all recorded rows and re-recorded the full terminal.

The updated geometry key:
```kotlin
val geometry = "$width:${frame.columns}:$visibleColumns:$insetHorizontal:$insetTop:$insetBottom:$extendPadding:${canvas.isHardwareAccelerated}"
```
Excluding `height` allows row recordings to be preserved throughout IME height animations, drastically reducing CPU/GPU overhead during transitions.

### 3.3 PTY Resize Coalescing & IME Lifecycle Synchronization

Located in `RexConnection.kt` and `MainActivity.kt`:

1. **WindowInsetsAnimation Callback**:
   - `onStart`: When the IME starts closing, `model.onImeClosing()` eagerly restores the unconstrained full-height terminal grid ahead of time.
   - `onEnd`: Once the IME animation completes and layout settles, `model.onImeAnimationEnd()` flushes the settled grid immediately, bypassing any remaining debounce delay.
2. **Compose State Optimization**:
   - `keyboardVisible` uses `derivedStateOf { imeInsets.getBottom(imeDensity) > 0 }` to avoid recomposing the entire workspace during continuous animation frames.
3. **Input Priority Over Resize**:
   - When user input or paste commands are dispatched (`type == "input" || type == "paste"`), any pending deferred resize is immediately flushed ahead of the input payload. This prevents keystrokes from executing against stale terminal dimensions.
4. **Deduplication**:
   - Resizes are tracked via `PaneSize`. Repeated dimensions during animations or redraws do not restart the debounce timer.

### 3.4 Backend Resize Settling in Rust (`engine.rs`)

When programs receive `SIGWINCH`, they often send screen clearing and redraw sequences across multiple VT write chunks.

- `RESIZE_SETTLE_CAP` (130 ms): Maximum duration to hold dirty frame updates after a resize event.
- `RESIZE_SETTLE_QUIET` (30 ms): Time window of silence after the last data chunk before releasing the frame.
- If data chunks are still arriving within the cap, `settling()` returns `true`, holding off publishing intermediate dirty frames. This converts what would be a series of partial screen flashes into a single coherent frame redraw.

### 3.5 Ghostty Viewport Pinning (`ghostty.rs`)

Tests and verification ensure that:
- A viewport positioned at the bottom of the scrollback buffer stays pinned to the bottom following resize and SIGWINCH redraws (`resize_keeps_an_at_bottom_viewport_pinned_to_new_output`).
- Explicitly scrolled history viewports remain anchored to the chosen scrollback row rather than unexpectedly jumping to the bottom (`resize_keeps_a_scrolled_viewport_on_its_row`).

### 3.6 Typing Ergonomics, Selection, and Haptics

1. **Auto-Scroll to Bottom on Input**:
   - Any typing, composing, or key event automatically invokes `onBottom` if the user was scrolled up in history (`screen.scroll.below > 0`).
2. **Editor Session Lifecycle**:
   - `restartInput` is called only when focus or control state changes, preventing stale input connections from swallowing keys while avoiding redundant restarts.
3. **Android Magnifier Support**:
   - Integrates `android.widget.Magnifier` for precise cursor and text selection dragging on Android 9+ (API 28+).
4. **Haptic Feedback**:
   - Standardized `HapticFeedbackType.TextHandleMove` and `LongPress` across dock keys, repeat keys, joystick interactions, and shortcut selections.

---

## 4. Verification & Testing

- **Rust engine unit tests**:
  - `a_resize_holds_frames_until_the_repaint_burst_goes_quiet` (verifies settling debounce logic).
  - `resize_keeps_an_at_bottom_viewport_pinned_to_new_output`.
  - `resize_keeps_a_scrolled_viewport_on_its_row`.
  - Run via: `cd rexling-core && cargo test`
- **Android unit tests**:
  - `TerminalRowWindowTest`: Verifies top/bottom clipping, origin offsets, continuity across height transitions, and touch coordinate inversion.
  - Run via: `cd rexling-android && ./gradlew testDebugUnitTest`
