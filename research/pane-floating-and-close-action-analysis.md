# Pane Floating Constraints and Explicit Close Action Removal in Rexling

## 1. Executive Summary

This document addresses two related architectural and UX concerns in Rexling's multi-pane and floating layout subsystem following upstream Build 170:
1. **Why floating panels ("Float pane") are disabled or missing**: Upstream Build 170 coupled `allowFloatingOverlay` and `allowFloat` to `PackageManager.FEATURE_SENSOR_HINGE_ANGLE` (`hasHinge`). This hardware-level feature check is exclusively present on specific foldable devices (such as Samsung Galaxy Z Fold) and is completely absent on tablets, emulators, other foldable devices (e.g., Pixel Fold, OnePlus Open depending on OEM HAL), and standard devices, rendering `allowFloat` perpetually `false`.
2. **Explicit "Close pane" button removal**: In `MultiPaneLayout`, the top-right corner actions pill (`PaneCornerActions`) previously exposed an explicit `ChromeIcon.CLOSE` button. In a multi-pane terminal workflow, placing an instant close button in the corner action pill presents an unacceptable misclick hazard that terminates live CLI processes. The explicit close button is removed from `PaneCornerActions`, retaining guarded close paths via the Terminal Actions Drawer (with confirmation) and the TabPaneSwitcher swipe actions.

---

## 2. Root Cause Analysis: Why Panes Cannot Float

### 2.1 Hardware Sensor Coupling in `MainActivity.kt`
Upstream Build 170 (`c731026`) introduced floating pane overlays for large screens, but gated them behind:
```kotlin
val unfoldedFullScreen = isUnfoldedFullScreen()
val hasHinge = remember(context) {
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
}
val allowFloatingOverlay = unfoldedFullScreen && hasHinge
```
And in `MultiPaneLayout` instantiation:
```kotlin
allowFloat = allowFloatingOverlay && tiles.size > 1
```

In `MultiPaneLayout.kt`:
```kotlin
onFloat = if (allowFloat && !floating) ({ menuFor = null; onFloat(pane.id) }) else null
```
When `allowFloat` is `false`, `onFloat` evaluates to `null`, and `PaneCornerActions` omits `PaneCornerAction(ChromeIcon.FLOAT, "Float pane", ...)`.

#### Consequences of the `hasHinge` Requirement:
- **Tablets (Pixel Tablet, Galaxy Tab, etc.)**: `minOf(width, height) >= 600dp` and `unfoldedFullScreen` is `true`, but tablets possess no physical folding hinge (`hasHinge == false`). Floating overlay support was completely disabled on all tablet devices.
- **Other Foldables & Emulators**: Many foldable devices expose custom hinge HALs or report hinge state via Jetpack WindowManager rather than `FEATURE_SENSOR_HINGE_ANGLE`. Android Virtual Devices (AVD) also report `false`.
- **Solution**: Decouple `allowFloatingOverlay` from `hasHinge`. The condition `isUnfoldedFullScreen()` already enforces standard Android criteria for expanded workspace layouts (`minOf(window.width, window.height) >= 600f * density` and `window.width >= screen.width * 0.95f` without multi-window split).

### 2.2 Rex Protocol Invariant: Minimum One Docked Pane
In both Rust backend (`rexling-core/jni/src/engine.rs`) and Compose UI (`TabPaneSwitcher.kt`):
```rust
let tiled: Vec<_> = layout.iter().filter(|p| p["floating"] == false).collect();
ensure!(tiled.len() > 1, "Keep one docked pane in this tab");
```
A tab must contain at least two tiled panes before one can be converted into a floating pane. If a tab has only a single pane (`tiled.len() <= 1`), floating is rejected by protocol to maintain a docked workspace baseline.

---

## 3. Removal of the Explicit Close Buttons

### 3.1 Problem Statement
Explicit close buttons (`ChromeIcon.CLOSE`) were previously visible in two prominent locations:
1. **`MultiPaneLayout.kt` Pane Corner Actions**:
   In `MultiPaneLayout.kt`, the top-right corner action pill (`PaneCornerActions`) previously exposed `add(PaneCornerAction(ChromeIcon.CLOSE, "Close pane", it))`. In a multi-pane terminal workflow, placing an instant close button in the corner action pill presents an unacceptable misclick hazard that terminates live CLI processes.
2. **`TabPaneSwitcher.kt` (Sessions / Tabs / Panes Drawer)**:
   In `TabPaneSwitcher.kt`, the header previously rendered an explicit close `IconButton(onClick=onClose)` with `ChromeIcon.CLOSE` on the right side of the segmented tabs (`Sessions | Tabs | Panes`). Because `TabPaneSwitcher` is a `ModalBottomSheet` that already features a top drag handle (`DrawerHandle()`), supports swipe-down dismissal, scrim tapping, and the system back gesture, an explicit close button in the header was redundant, visually cluttered, and unnecessarily compressed the segmented control pills.

### 3.2 Solution
1. Remove `onClose` action emission from `PaneCornerActions` in `MultiPaneLayout.kt`.
2. Remove the trailing `IconButton` close button from `TabPaneSwitcher.kt` so the segmented control `[Sessions | Tabs | Panes]` occupies the full header width symmetrically with `padding(horizontal = 16.dp)`.
3. Safe pane and session termination remains preserved through:
   - **Terminal Actions Drawer**: Tap terminal menu -> "Close pane" -> Confirmation alert dialog.
   - **TabPaneSwitcher**: Swipe pane row -> "Close pane" -> Confirmation alert dialog.
   - **Terminal Session**: Standard shell exits (`exit`, `Ctrl+D`).
   - **Drawer Dismissal**: Standard bottom sheet drag down, scrim tap, or back gesture.

---

## 4. Two-Stage Expansion in PreferencesSheet

### 4.1 Problem
`PreferencesSheet` previously used `rememberModalBottomSheetState(skipPartiallyExpanded = true)` with content capped at `heightIn(max = 600.dp)`. This prevented users on modern tall phones, foldables, and tablets from dragging the preferences panel to full screen to view all settings sections at once.

### 4.2 Solution
1. Configured `rememberModalBottomSheetState(skipPartiallyExpanded = false)` in `PreferencesSheet.kt`.
2. Wrapped content in `BoxWithConstraints` and dynamically set `heightIn(max = (maxHeight - 64.dp).coerceAtLeast(200.dp))`.
3. When opened, the sheet rests at a comfortable half-screen height (`PartiallyExpanded`), and smoothly expands to full screen (`Expanded`) when dragged upward or scrolled.
