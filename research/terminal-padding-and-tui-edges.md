# Terminal Padding and Edge Background Extension in Rexling

## 1. Executive Summary

When running terminal applications in Rexling on Android, `TerminalView` applies padding (`insetHorizontal`, `insetTop`, `insetBottom`) to provide comfortable spacing and accommodate UI elements like the carousel pager dots (`PanePositionIndicator`).

A tension exists between two types of full-screen terminal applications:
1. **Full-screen Editors (Vim, Neovim, Helix, Nano)**: Users expect editor buffers to seamlessly fill the viewport without distracting letterboxing or contrasting top/bottom border gutters ("上下留白").
2. **Segmented Status Bar TUIs (Yazi, Tmux status bars)**: Bottom rows contain multi-colored badge spans (e.g., file size, git branch, cursor metrics). Crude vertical extension of the bottom row causes these badges to bleed down across the bottom padding and carousel pager dots as vertical colored stripes ("底部色块溢出").

This document analyzes the historical changes, the underlying rendering mechanics, and the trade-offs between available solutions.

---

## 2. Historical Context & Root Cause Analysis

### 2.1 Upstream's Initial Approach
Upstream attempted to emulate Ghostty's `window-padding-color = extend` for vertical padding by adjusting row origins and row heights in `TerminalView.kt`:
```kotlin
val origin = if(y == 0) 0f else insetTop + y * cellHeight
val top = if(y == 0) insetTop else 0f
val bottom = if(y == visibleRows - 1) height - origin else top + cellHeight
```
And in `drawRow.flush()`:
```kotlin
canvas.drawRect(
    if(start == 0) 0f else insetHorizontal + start * cellWidth, 0f,
    if(end >= columns) width.toFloat() else insetHorizontal + end * cellWidth, bottom,
    backgroundPaint
)
```
- **Top Row (`y == 0`)**: `origin = 0f`, `top = insetTop`, `bottom = insetTop + cellHeight`. The background was drawn from `0f` to `insetTop + cellHeight`, covering the top padding (`0f..insetTop`), while text started at `insetTop`.
- **Bottom Row (`y == visibleRows - 1`)**: `bottom = height - origin`. The background was drawn from `0f` to `height - origin`, stretching to the bottom edge of the view (`height`).

### 2.2 Why Upstream's Implementation Broke Yazi
1. **Vertical Color Bleed**: In Yazi, the bottom line is a status bar with segmented colors (e.g., cyan badges, yellow git indicators). Because `flush()` executed for *every* colored cell run in the bottom row with `bottom = height - origin`, each colored badge was stretched down into the bottom padding and over the carousel pager dots as vertical colored pillars.
2. **Truncation Row via `ceil`**: Upstream used `ceil(contentHeight / cellHeight)`, causing a fractional row to be drawn beyond the usable content height.
3. **No Resting Clip**: Upstream only clipped with `canvas.clipRect` when `slide != 0f` (during kinetic scroll fling), leaving resting TUI frames unclipped against `insetBottom`.

### 2.3 Lemon's Fix in Commit `694609f`
To fix Yazi's bottom overflow, commit `694609f` strictly isolated row rendering:
1. Replaced `ceil` with `floor(contentHeight / cellHeight)` to eliminate fractional off-grid rows.
2. Wrapped the entire row pass in `canvas.clipRect(0f, insetTop, width.toFloat(), height - insetBottom)`.
3. Set fixed row height bounds `0f..cellHeight` with origin `insetTop + y * cellHeight`.

### 2.4 Side Effect: Vim's Top and Bottom Blank Space ("上下留白")
With strict clipping to `insetTop..height - insetBottom`:
- **Top padding (`0f..insetTop`)**: Painted exclusively with `palette.background`.
- **Bottom padding & fractional gap (`insetTop + visibleRows * cellHeight..height`)**: Painted exclusively with `palette.background`.
- **Left and Right padding**: Handled by `extendPadding` in `flush()`, which extends cell backgrounds horizontally from `0f` to `width.toFloat()`.

Because Vim often uses a colorscheme different from `palette.background` (or even with the same background, introduces padding around the buffer), horizontal edges look seamless, while vertical edges display contrasting letterbox bars.

---

## 3. Comparison of Solution Approaches

| Approach | Mechanics | Pros | Cons |
| :--- | :--- | :--- | :--- |
| **A. Program-Specific Policy (`ProgramKind`)** | Recognize `VIM` / `NEOVIM` via `ProgramIdentity` (already present in Rexling). Allow vertical padding extension only for editors. | Zero risk to Yazi, Tmux, or shells. Vim gets a seamless mobile editor experience. Aligns with existing `ProgramKind.NEOVIM` architecture. | Requires maintaining a list of editor identifiers (e.g., vim, nvim, helix, nano). |
| **B. Uniform Background Detection** | In `TerminalView.kt`, inspect row background uniformity: row 0 extends upward if uniform; row `visibleRows - 1` extends downward only if it has a single uniform background (Vim's command line is uniform; Yazi's badge bar is multi-colored). | Automatic across any editor without hardcoding names. Distinguishes uniform command lines from segmented status bars dynamically. | Slightly more complex drawing logic in `TerminalView`. Must still account for carousel pager dots when `blocks.size > 1`. |
| **C. Top Extend + Smart Bottom** | Always allow row 0 to extend upward into `insetTop` (top padding never has chrome/dots and is always safe). For bottom, extend only when `blocks.size == 1` and program is an editor or uniform. | Fixes top blank space universally; prevents bottom overflow over pager dots. | Multi-pane Vim tabs would still show bottom padding for dots (which may be desirable to keep dots legible). |
| **D. User Preference ("Padding: None")** | Direct users to Settings -> Terminal Padding -> `None`. | Zero code changes. | Shells also lose padding; touch targets near screen edges become tighter. |

---

## 4. Recommendations for Discussion

1. **Top Padding Extension**:
   Extending row 0's background into `0f..insetTop` (when `extendPadding == true`) is universally safe because there are no floating pager dots or system gestures at the top of the terminal carousel.
2. **Bottom Padding Extension**:
   - Single pane (`blocks.size == 1`): If the row is uniform (or program is Vim/Neovim), extend down to fill the fractional line gap and bottom inset.
   - Multi-pane carousel (`blocks.size > 1`): Protect the `pagedBottom` area so the pager dots remain legible on `palette.background`, or let the background flow behind the dots if contrast permits.
