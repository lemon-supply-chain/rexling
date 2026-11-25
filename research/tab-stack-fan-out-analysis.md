# Stacked Tab Icon Fan-Out Research (With Floating Tab Bar Considerations)

Date: 2026-11-25
Status: Research only (no code modified)

## 1. Summary

Yes, fanning out stacked tab cards when the user taps an **already-selected** multi-pane tab (`blocks.size > 1`) is technically feasible and fits Rexling's existing `ProgramStack` and `PaneProgramTile` components cleanly.

However, because Rexling supports a **floating tab bar** (`FloatingTabBar` + `TerminalTabs(floating = true)` / `TerminalTabRail(floating = true)`) in addition to docked bottom bars and docked/island side rails, **inline** expansion inside the tab row/column will clip, collide with `TabSizing`, or jump the floating bar's coordinates. An **anchored overlay** (rendered in the workspace coordinate space without resizing the tab bar) is the viable architecture, provided several floating-bar and pane-model edge cases are handled.

---

## 2. Current Implementation Findings

### 2.1 Stacked Icon Rendering (`ProgramStack.kt`)
- `ProgramStack` (`ProgramStack.kt:30-87`) renders up to **3** tiles (`minOf(items.size, 3)`):
  - Front tile (`selected`) tilted `-6f` degrees when `others.isNotEmpty()`, sized `22.dp x 18.dp` (unframed) or `34.dp x 27.dp` (framed).
  - Up to 2 background tiles (`others`) shifted right by `step` (`4.dp` unframed, `6.dp` framed) and tilted `+3f` / `+6f` degrees.
- `ProgramStack` only receives `programs: List<ProgramIdentity>` and `selected: Int`; it does not receive `BlockItem` IDs, pane titles, working/progress state per background pane, or floating/minimized flags.
- `ProgramStack` applies `Modifier.clearAndSetSemantics` (`ProgramStack.kt:61`) so the tab reads as a single accessibility node.

### 2.2 Tap Behavior on Tabs
- Bottom bar (`TerminalChrome.kt:1461-1465`) and side rail (`TabRail.kt:408-414`) both invoke `onSelect(w.id)` -> `RexConnection.selectWindow(id)` (`RexConnection.kt:1071-1090`) -> `selectBlock(id)` (`RexConnection.kt:996-1033`).
- `selectBlock` returns immediately as a no-op when `id == navigationRequest.block || (id == current.selectedBlock && !navigationRequest.pending)` (`RexConnection.kt:1008-1012`).
- Consequently, tapping the already-selected tab currently performs no action.

### 2.3 Floating Tab Bar & Layout Constraints
- **Floating bottom bar** (`TerminalChrome.kt:1143-1186`, `MainActivity.kt:1271-1284`):
  - Fixed capsule dimensions: `FloatingTabBarWidth = 360.dp`, `FloatingTabBarHeight = 52.dp`.
  - Contains `FloatingTabBarHandle` (`40.dp`), a `BoxWithConstraints` wrapping a `clipToBounds()` `LazyRow`, and `TerminalActions`.
  - Tabs use `tabWidths(...)` (`TabSizing.kt:11-75`), shrinking inactive tabs down to `48.dp` and the active tab to `104.dp..160.dp`.
- **Floating side rail** (`TabRail.kt:118-284`, `FloatingTabBar.kt`):
  - Width is `48.dp` (`TabRailStyle.ICONS`) or `144.dp` (`TabRailStyle.TITLES`), height hugs the tab list (`wrapContentHeight()`, capped at `maxHeight`).
  - When parked near the left or right screen edge (`FloatingTabBarEdgeZone = 16.dp`, `hideAtEdges = true`), `FloatingTabBar` auto-hides behind a side chevron after `FloatingTabBarIdleHideMillis = 4_000L` (`FloatingTabBar.kt:128-133`).
- **Positioning formula** (`FloatingTabBar.kt:93-114, 226`):
  - `FloatingTabBar` measures its child via `onSizeChanged { size = it }` and computes `freeWidth = areaWidth - size.width - margin * 2`, `freeHeight = areaHeight - size.height - margin * 2`, and `resting = Offset(margin + position.x * freeWidth, margin + position.y * freeHeight)`.
  - **Key constraint**: Changing the bar's measured `size` shifts `resting` and moves the whole floating bar across the screen, and can change `edge` auto-hide eligibility (`FloatingTabBar.kt:121-127`).

---

## 3. Architectural Comparison

### Option A: Inline Fan Inside `LazyRow` / `LazyColumn` (Not Recommended)
- **Why it breaks**:
  1. `LazyRow` has `.clipToBounds()` (`TerminalChrome.kt:1285`) and fixed `FloatingTabBarHeight = 52.dp`, clipping tilted/fanned cards vertically and horizontally.
  2. `FloatingTabRail` in `ICONS` mode is only `48.dp` wide—there is no horizontal room for cards to fan out inline.
  3. Expanding the bar's `size` alters `freeWidth`/`freeHeight` in `FloatingTabBar`, causing the floating capsule/rail to jump position (`FloatingTabBar.kt:101-103`) and violating the fixed-capsule contract (`FloatingTabsTest`).

### Option B: Anchored Workspace Overlay Fan (Recommended)
- Keep the tab bar's layout size fixed (`FloatingTabBarWidth` x `FloatingTabBarHeight` or rail `width` x `pillHeight`).
- Track the active stacked tab's anchor bounds in root/workspace coordinates (`onGloballyPositioned` on the `ProgramStack` host `Box`).
- When the user taps an **already-selected** tab with `blocks.size > 1`, open an in-window overlay anchored to that tab's bounds inside `RexWorkspace` (or `FloatingTabBar`'s full-size `BoxWithConstraints`, though `RexWorkspace`-level overlay also covers docked bottom bars and docked side rails uniformly).
- Animate each pane card (`PaneProgramTile` + optional compact title/status pill) from the stack origin (`0f`, `-6°/+3°/+6°`) out to its fanned coordinates via `graphicsLayer`, using `RexTokens.motion.fastSpatial()` / `defaultSpatial()` (snapping when `!LocalMotionEnabled.current`).

---

## 4. Edge Cases to Handle

### 4.1 Floating Tab Bar Edge Cases
1. **Arbitrary 2D placement & screen-edge flipping**:
   - Because `FloatingTabBar` can be dragged to the top, bottom, left, right, or center (`position: Offset` in `[0..1, 0..1]`):
     - **Near top edge**: fanning upward clips under the status bar / `TopBar`; must flip **downward** when top clearance is insufficient.
     - **Near left/right edges**: a horizontal fan centered on the anchor must clamp horizontally within workspace margins so outer cards do not clip off-screen.
     - **Floating side rail**: fan **rightward** when parked on the left side, **leftward** when parked on the right side, and toward the side with larger available width when parked near center.
2. **Side-rail 4-second idle auto-hide (`FloatingTabBarIdleHideMillis = 4_000L`)**:
   - `FloatingTabBar` only bumps `interaction` on pointer down inside its `Column` (`FloatingTabBar.kt:230-241`).
   - If the fan overlay stays open >4s on an edge-parked floating rail without touching the rail itself, `hidden` becomes `true`, disappearing the anchor bar under the open fan.
   - **Mitigation**: Pass `fanOpen` into `FloatingTabBar` to pause the idle-hide timer (same as `moving`), and auto-dismiss the fan if `hidden` transitions to `true`.
3. **Bar drag, tab drag-reorder, rail style swipe, and list scroll**:
   - Long-pressing `FloatingTabBarHandle` (`moving = true`), long-pressing a tab to reorder (`drag.id != null` in `TerminalTabs`), opening the tab context menu (`menuId != null`), swiping `TerminalTabRail` (`ICONS <-> TITLES`), or scrolling `LazyRow`/`LazyColumn` (`list.isScrollInProgress`) moves or alters the anchor bounds.
   - **Mitigation**: Immediately collapse the fan whenever any of these starts.
4. **Collision with `FloatingPaneDock` and `NeovimShortcutButton`**:
   - Both `FloatingPaneDock` (`MainActivity.kt:1255-1270`, `Alignment.BottomEnd`) and `NeovimShortcutButton` (`MainActivity.kt:1216-1254`) sit in the same terminal `Box`.
   - If the floating tab bar is parked at bottom-right, the fan overlay must sit above them in z-order (with a backdrop dismiss scrim or `paneObscured` awareness) so taps on fanned cards do not fall through to the dock or terminal.
5. **Foldables, split-screen, and IME resize**:
   - When the soft keyboard opens, `FloatingTabBar`'s container `Box` shrinks above `WorkspaceBottomBar` (`MainActivity.kt:1038-1284`). Anchor coordinates and available fan height must be recomputed dynamically (or auto-collapse on IME visibility / workspace size change).
   - On narrow split-screen windows (`< 440.dp`) with many panes (e.g., 5+ panes), fanned cards need a bounded spread or horizontal/vertical scrolling fallback rather than overflowing the viewport.

### 4.2 Pane Model, Selection & Navigation Edge Cases
6. **Card count mismatch (`> 3` panes)**:
   - `ProgramStack` visually caps at 3 tiles, but a tab can have 4+ panes (`window.blocks`).
   - Fanning out must render **all** relevant panes of that tab (not just 3), using the top 3 cards as the visual transition from the stack and scrolling/wrapping if pane count exceeds available space.
7. **Distinguishing panes of the same program**:
   - Two `zsh` or two `nvim` panes look identical as bare `PaneProgramTile`s. Each fanned card needs a compact title/label (`program.paneTitle(...)` / `state.titles[block.id]`), active border/tint, and working/unread badge so the user knows which card is which.
8. **Floating & minimized panes (`FloatingPanes.kt`, `minimizeFloatingPanes`)**:
   - `RexConnection.tabPrograms` (`RexConnection.kt:1063`) currently includes **all** `window.blocks`, including minimized floating panes.
   - Selecting a minimized floating pane requires `restoreMinimized = true` (like `openPane` at `MainActivity.kt:553`: `client.selectBlock(id, null, keepTiled = tiles != null, restoreMinimized = true)`), whereas `selectWindow` defaults `restoreMinimized = false`.
   - In unfolded multi-pane tiled mode (`tiles != null`), `keepTiled = true` must be preserved so switching focus between tiled panes does not collapse the multi-pane grid.
   - Decision needed on whether minimized floating panes should appear in the tab fan (with a floating indicator) or only `carouselPanes` / `visiblePanes`, keeping `ProgramStack` count consistent with the fan.
9. **Optimistic tab switch / rapid double-tap (`navigationRequest.pending`)**:
   - `MainActivity.kt:265-271` derives `activeWindowId` from `displayedBlock = navigation.block ?: state.selectedBlock`.
   - A rapid double-tap on an *inactive* tab could see `w.id == selectedId` on the second tap while `navigation.pending` is still `true` (`state.selectedBlock` hasn't settled yet).
   - **Mitigation**: Only treat a tap as a re-tap fan toggle when `!navigation.pending` (or when `w.id` was already the settled active window before the current gesture).
10. **Single-pane tabs & live state changes**:
    - Never fan out when `panes.size <= 1`.
    - Auto-collapse if `panes.size` drops to `<= 1`, `selectedId` changes, `displayedSession` changes, or a sheet/dialog (`TabPaneSwitcher`, `Settings`, `menuId`, `BackHandler`) opens.
11. **Haze backdrop blur (`captureTerminal`) & TalkBack**:
    - `FloatingTabBar` (`floatingTabs == true`) already enables `captureTerminal` (`MainActivity.kt:1082`). In **docked** mode (`floatingTabs == false`), `captureTerminal` may be `false`, so frosted fanned cards/pills must contribute a `fanBackdrop` boolean to `captureTerminal` (just like `floatingBackdrop` for `FloatingPaneDock` at `MainActivity.kt:1078, 1268`) or use opaque surfaces.
    - TalkBack: because `ProgramStack` clears descendant semantics, the active stacked tab should expose a localized state/action description (expand/collapse panes), and each fanned card should be an independent focusable `Role.Button` with `BackHandler(enabled = fanned)` support.
