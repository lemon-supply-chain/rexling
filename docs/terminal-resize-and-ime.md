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

### 3.7 Program-Driven IME Viewport Policy

The keyboard changes which part of the terminal is usable, but not every program wants the same
reaction. Inline prompts should stay visible without reflowing the PTY, while full-screen TUIs
have to receive the new grid size and repaint themselves. `TerminalImeAnchor.kt` resolves that
policy per program from a data table.

`ImeAnchor.CURSOR` and `ImeAnchor.CONTENT` keep the PTY's full-height grid and position the
viewport with `TerminalKeyboardFocus` (`TerminalView.keyboardFocus`) and `terminalImePan`:

- `ImeAnchor.CURSOR`: only the reported hardware cursor moves the viewport (shells, editors,
  monitors).
- `ImeAnchor.CONTENT`: the reported cursor still wins; without one the last painted row anchors
  the viewport, so a hidden caret reveals its own editor above the keyboard. Registered programs
  are `pi` and `cursor-agent`; neither reports a hardware cursor nor emits OSC 133 semantic
  prompt metadata, so the semantic-zone fallbacks cannot help.

`ImeAnchor.RESIZE` is the opposite trade: the view shrinks to the visible area and
`TerminalView.onSizeChanged` reports the smaller grid. The existing resize debounce and
IME-animation flush then send SIGWINCH, so the program redraws into the space above the keyboard.
`yazi` is registered here because it is a full-screen file manager rather than an inline agent
with a fixed composer; its own layout must reflow instead of having the whole grid pushed up and
clipped at the top.

- `ImeAnchorRule` matches executable base names and install-path fragments against
  `ProgramIdentity.matchNames`. Adding a program is one entry in `TerminalImeAnchors.rules`;
  unknown or pending programs keep `CURSOR`, which never moves a viewport on its own.
- Scrollback (`scroll.below > 0`) and the last-painted-row cap apply only to panning policies.
  `RESIZE` programs always reflow; they never call `terminalImePan`.

### 3.8 Predictive Local Echo & Cursor Coordinates

To minimize typing latency over mobile high-latency connections, Rexling implements Mosh-style local echo (`prediction.rs`):

- **Cursor Tracking**: Ghostty reports both drawn (`cursor`) and known viewport positions (`cursor_at`). Programs that hide the hardware cursor (`pi`, `cursor-agent`) park their caret at their input prompt; tracking `cursor_at` enables local echo predictions even when the drawn cursor is hidden.
- **Engine Coordination**: Typed characters are drawn optimistically and confirmed or dropped upon server frames.
- **Defensive Server Lifecycle**: Predictive echo commands (`command("prediction", ...)`) are guarded so that unconfigured or empty server profiles (`endpoint.isBlank()`) do not send premature RPCs to the engine, and error banners are suppressed on empty workspaces where no active servers exist.

### 3.9 Edge Padding Strategy Pattern (Non-Editor vs Editor)

Terminal padding (`insetTop`, `insetBottom`, `insetHorizontal`) provides comfortable margins and accommodates floating carousel pager dots. However, full-screen editors and segmented status-bar TUIs have opposing requirements:
- **`TerminalEdgePolicy.NON_EDITOR` (Default)**: Strictly constrains drawing within the content box `clipRect(0f, insetTop, width, height - insetBottom)`. Tools with multi-colored status badges (e.g. `yazi`, `tmux`) never leak color stripes or icons into bottom insets or pager dots.
- **`TerminalEdgePolicy.EDITOR`**: Enabled for full-screen editors (`vim`, `neovim`, `helix`, `nano`, `micro`, `emacs`). When `extendPadding` is true, the top row background seamlessly extends upward to `0f` and the last row background extends downward to `height`, eliminating letterbox blank borders while keeping text safely within the content padding.

`TerminalEdgePolicies.policy(identity)` resolves the policy from `ProgramIdentity`, strictly defaulting to `NON_EDITOR` for shells, CLI utilities, and unclassified programs.

### 3.10 Keyboard Suggestions and Auto-Correction Policy

Soft keyboards (like Gboard) naturally attempt dictionary lookups, predictive word suggestions, and auto-corrections when typing in text fields. While helpful in messaging apps, auto-correcting CLI commands (`git`, `grep`, `kubectl`, option flags, Unix paths) leads to unwanted replacements and corrupted terminal input.

- **Configurable Preference**: A "Keyboard suggestions" (`keyboard_suggestions`) toggle in Preferences (Keyboard section) controls this behavior. It defaults to `false` (disabled) so terminal commands are passed verbatim without dictionary intervention.
- **IME Connection Flags**:
  - When suggestions are **disabled** (`keyboardSuggestions = false`): Modern IMEs (such as Gboard and Samsung Keyboard) ignore `TYPE_TEXT_FLAG_NO_SUGGESTIONS` on standard `TYPE_CLASS_TEXT` fields. `TerminalView.onCreateInputConnection` configures `InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS` alongside `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`. This forces the keyboard to treat the stream as raw literal characters without predictive spell-checking or dictionary replacement, while omitting `setInitialSurroundingText` and clearing context in `commitPending()`.
  - When suggestions are **enabled** (`keyboardSuggestions = true`): Non-Neovim programs request `InputType.TYPE_TEXT_FLAG_AUTO_CORRECT` with `TYPE_TEXT_FLAG_MULTI_LINE` and retain surrounding text for multi-word completions. Neovim always retains `TYPE_TEXT_FLAG_NO_SUGGESTIONS` regardless of preference to prevent command-mode key sequence corruption.
- **Dynamic Reconfiguration**: Toggling the preference marks active editors and invokes `InputMethodManager.restartInput()` immediately or upon regaining window focus (`onWindowFocusChanged`), cleanly updating the soft keyboard's state without requiring manual keyboard dismissals.

---

## 4. Verification & Testing

- **Rust engine unit tests**:
  - `a_resize_holds_frames_until_the_repaint_burst_goes_quiet` (verifies settling debounce logic).
  - `resize_keeps_an_at_bottom_viewport_pinned_to_new_output`.
  - `resize_keeps_a_scrolled_viewport_on_its_row`.
  - `prediction::tests` (verifies predictive echo epoch confirmation, hidden-cursor tracking, backspace and overflow handling).
  - Run via: `cd rexling-core && cargo test`
- **Android unit tests**:
  - `TerminalRowWindowTest`: Verifies top/bottom clipping, origin offsets, continuity across height transitions, and touch coordinate inversion.
  - `TerminalImePanTest` / `TerminalImeAnchorTest`: Verifies that editor cursor moves do not recenter a settled viewport, that hidden-caret programs (pi, cursor-agent) anchor on the last painted row, that `yazi` selects the resize policy, and that every other program keeps the cursor anchor.
  - `TerminalEdgePolicyTest`: Verifies that `null`, shells, tools (`yazi`, `btop`, `git`), and unknown programs strictly default to `NON_EDITOR`, while `vim`, `neovim`, and modern CLI editors resolve to `EDITOR`.
  - Run via: `cd rexling-android && ./gradlew testDebugUnitTest`
