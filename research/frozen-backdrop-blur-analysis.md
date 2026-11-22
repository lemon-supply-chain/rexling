# Frozen Backdrop Blur Analysis for Frosted Panels

Date: 2026-11-22  
Scope: `PaneFrost.kt`, `MainActivity.kt`, `SettingsDrawer.kt`, `TabPaneSwitcher.kt`, `DrawerFrostTest.kt`

## 1. Background & Problem Statement

Rexling uses frosted glass effects for several translucent surfaces, including modal bottom sheets (`PreferencesSheet`, `TabPaneSwitcher`, `ServersSheet`, `ActivitySheet`), popups, and floating overlays.

### Current Architecture
Currently, backdrop blur is handled via `dev.chrisbanes.haze:haze:1.5.4`:
- **Source Layer**: The main workspace composable registers as a haze source via `Modifier.frostSource(workspaceHaze, workspaceBackdrop)`.
- **Target Surfaces**: Translucent sheets apply `Modifier.drawerFrost(...)`, which applies `Modifier.paneFrost(...)`.
- **Cross-Window Invalidation**: Because bottom sheets and popups run in separate Android windows (`DialogWindow` / `PopupWindow`), their Compose draw trees do not automatically invalidate when the underlying terminal view draws. To maintain live backdrop blur, Rexling utilizes `PopupFrostInvalidator`:
  ```kotlin
  private val listener = android.view.ViewTreeObserver.OnPreDrawListener {
      effects.forEach { if (it.isAttached) it.invalidateDraw() }
      true
  }
  ```
- **Live Update Semantics**: Every time the terminal updates (cursor blinks, text arrives via PTY, smooth scrolling, or marquee animation), `it.invalidateDraw()` causes the popup's `HazeEffectNode` to re-execute GPU blur via `RenderEffect.createBlurEffect()` on Android 12+ (API 31+). This behavior is asserted in `DrawerFrostTest.kt`:
  ```kotlin
  ui.runOnIdle { green.value = true }; ui.waitForIdle(); Thread.sleep(80)
  val updated = sample()
  assertTrue("Overlay $kind must update with its host: $updated", updated.green > maxOf(updated.red, updated.blue) + .04f)
  ```

### Performance Implications
Executing GPU Gaussian blur across a full-screen canvas (e.g. 1080x2400 or 1440x3120) at 60Hz or 120Hz creates substantial memory bandwidth and fill-rate load on mobile GPUs. When a user opens a modal sheet, continuous live blur re-renders during gestures, scrolling, or while waiting for input, competing for resources with active terminal emulation.

---

## 2. Technical Feasibility of "Blur Once on Open"

Rendering the blur once upon opening and freezing it for the lifetime of the panel is **completely feasible** and represents a well-established mobile graphics optimization ("Frozen Backdrop Snapshot").

### Core Concept
1. **On Panel Request**: Immediately prior to or during the first frame of panel display, capture the underlying workspace.
2. **Execute Single Blur Pass**: Apply Gaussian blur (optionally downsampled) to generate a static texture (`ImageBitmap` or cached `GraphicsLayer`).
3. **Render Panel Over Static Texture**: Use the frozen blurred texture as the panel's background layer, clipping or offsetting according to the panel's animated position.
4. **Disarm Continuous Invalidation**: Remove or bypass `PopupFrostInvalidator`'s `OnPreDrawListener` during the panel lifecycle.
5. **Dismiss & Clean Up**: Recycle the blurred texture once the panel closes.

---

## 3. Architecture & Implementation Options

### Option A: Downsampled Snapshot Layer (Recommended)

Instead of relying on Haze's continuous render loop across window boundaries:
1. **Snapshot Capture**:
   - Capture the current terminal/workspace canvas using Compose `GraphicsLayer` recording (`rememberGraphicsLayer()` / `layer.record { ... }`) or `PixelCopy`.
2. **Downsampling (4x Optimization)**:
   - A 24dp blur radius obliterates high-frequency spatial details. Downscaling the backdrop by 4x (e.g. from 1080x2400 to 270x600) reduces the blur pixel count by 93.75% ($1/16$).
   - Blurring the 4x downsampled bitmap takes $< 1.5\text{ ms}$ on mobile GPUs and requires $< 650\text{ KB}$ of memory.
3. **Screen-Space Texture Mapping**:
   - As a modal sheet slides up from the bottom (e.g., animated transition or drag-to-dismiss gesture), the background texture must remain stationary in screen coordinates.
   - The panel content clips and translates a viewport over the static blurred background:
     $$\mathbf{p}_{\text{texture}} = \mathbf{p}_{\text{panel}} + \mathbf{T}_{\text{panel\_offset}}$$
   - This creates a natural "window revealing a frosted world" visual effect without recalculating blur.
4. **Host Decoupling**:
   - Terminal mirror updates can remain obscured (`setMirrorObscured(true)`), saving terminal CPU/GPU work while the panel is active.

### Option B: Freezing Haze Pipeline & Disarming Invalidator

An alternative is preserving Haze while suppressing subsequent redraw passes:
1. **Single-Shot Invalidation**:
   - Trigger `invalidateDraw()` once when the sheet mounts, then detach the `OnPreDrawListener`.
2. **HazeSource Constraints**:
   - In Haze 1.5.4, `HazeSourceNode` manages `HazeArea.contentLayer`. If the underlying workspace continues drawing, `HazeSourceNode.draw()` re-records into `contentLayer`.
   - If `frostSource(visible = false)` is invoked, Haze disposes of `contentLayer` completely on reset/detach.
   - Consequently, freezing Haze without hacking internal state requires detaching listener invalidations while preventing the source node from re-recording.

---

## 4. Key Design Considerations & Trade-offs

| Aspect | Continuous Live Blur (Current) | Frozen Snapshot Blur (Proposed) |
| :--- | :--- | :--- |
| **GPU Blur Frequency** | Every frame while dirty ($60-120\text{ FPS}$) | Exactly once per panel opening ($1\text{ pass}$) |
| **Sheet Scrolling / Gestures** | Stutter risk on low/mid-tier devices | Silky smooth ($0\text{ blur cost}$ during scroll) |
| **Terminal Background Activity** | Visual updates show live behind blur | Visual background remains frozen at open snapshot |
| **Memory Footprint** | Low (managed in Haze render layer) | $\approx 600\text{ KB}$ transient bitmap if downsampled |
| **Test Requirements** | `DrawerFrostTest` asserts live green updates | `DrawerFrostTest` should assert freeze invariance |

### Visual Quality Assessment
Freezing the backdrop while a drawer is open is standard UX across iOS and Android system dialogs. Live updates behind a translucent sheet are rarely noticeable through 72% surface tint and 24dp blur, and can even be visually distracting.

---

## 5. Implementation & Scope of Resolution

The optimization has been implemented in `PaneFrost.kt` via a single-shot effect invalidation mechanism (Option B variant):
- **Bounded Settling Invalidation**: `PopupFrostInvalidator` attaches `OnPreDrawListener` to ensure that effects are invalidated only when the source layer (`HazeArea.contentLayer`) is confirmed to be recorded. It runs a bounded 2-frame countdown to ensure the popup receives and applies the source layer, then unregisters via `stop()`.
- **Eliminating Idle Host Invalidation Loops**: While a panel/drawer is open, subsequent host window redraws (such as terminal cursor blinking, stream updates, or marquee animation) no longer trigger invalidations on the popup's `HazeEffectNode`. This eliminates the 60-120Hz continuous blur loop during idle viewing and list interaction.
- **Design Token Compliance**: Inline raw colors and dimensions in `PaneFrost.kt` and `SettingsDrawer.kt` have been migrated to `RexTokens.frost`.
- **Scope & Limitations**:
  - The underlying `HazeSourceNode` in the activity still records into its layer when the host draws dirty frames; this optimization decouples the *popup effect* invalidation from the host.
  - Position/size changes during manual dragging still dirty the effect node and cause re-blurs.
  - Option A (full downsampled bitmap snapshot decoupling the source completely) remains documented as prospective future architecture.
- **Test Invariance**: `DrawerFrostTest.kt` was updated to verify that the overlay blurs with smooth color blending across stripe boundaries and remains frozen against subsequent host backdrop changes (`green.value = true`).

