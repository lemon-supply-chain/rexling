# Preferences Sheet Full-Screen Height Investigation

Date: 2026-11-25

## Summary

Two distinct factors explain why the Preferences bottom sheet does not occupy the full screen height depending on whether you inspect the installed release build (`dev.lemon.rex`, versionCode `169`) or the current workspace debug build (`dev.lemon.rex.debug`).

## 1. Installed Release Build (`dev.lemon.rex`, versionCode 169 / commit `a9ff5dc`)

In commit `a9ff5dc` of `PreferencesSheet.kt`:
- The sheet used `rememberModalBottomSheetState(skipPartiallyExpanded = true)` and `contentWindowInsets = { WindowInsets(0, 0, 0, 0) }`.
- However, the scrollable content `Column` hardcoded `.heightIn(max = 600.dp)`.
- On the test device (`21091116AC`, `1080x2400`, `440 dpi` = `2.75 px/dp`, `96 px` status bar, `160 px` bottom navigation bar):
  - Header (`DrawerHandle` `16.dp` + title row `48.dp`) = `64.dp` (`176 px`).
  - Body max height = `600.dp` (`1650 px`).
  - Maximum sheet height = `664.dp` (`1826 px`), leaving `414 px` (`150.5 dp`) uncovered above the sheet (`y = 414..2240`).
  - Observed live on `I7CEJNQCJN4P59AE`: `top_y = 414 px` upon opening (`p1_open.png`), and dragging the handle upward (`540,440 -> 540,80`) produces zero movement (`p2_drag_up_handle.png`).

## 2. Current Workspace Code (`PreferencesSheet.kt` / `dev.lemon.rex.debug`)

Commit `2cf9a06` removed the `600.dp` cap and updated `PreferencesSheet.kt`:
- `PreferencesSheet.kt` lines 92–96: `rememberModalBottomSheetState(skipPartiallyExpanded = false, ...)`.
  - When first opened, the sheet lands in `PartiallyExpanded` (`top_y = 1322 px` on `I7CEJNQCJN4P59AE`) rather than expanding immediately.
- `PreferencesSheet.kt` lines 132–133 & 182: uses `BoxWithConstraints` to compute `contentMaxHeight = (maxHeight - 64.dp).coerceAtLeast(200.dp)` and sets `.heightIn(max = contentMaxHeight)` on the scrollable body.
  - Dragging upward expands the sheet (`cur2_body_up.png`, `cur3_handle_up.png`).
- **Remaining top gap (`0..96 px` status bar area)**:
  - `PreferencesSheet.kt` line 125 passes `contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) }` to `ModalBottomSheet`, with `containerColor = Color.Transparent` (line 121).
  - Bytecode inspection of `androidx.compose.material3.BottomSheetKt` (`material3-android:1.5.0`) confirms `BottomSheetImpl` applies `Modifier.windowInsetsPadding(contentWindowInsets())` inside `Surface` **outside** the caller's `content` lambda.
  - Because `Modifier.drawerFrost(...)` is applied inside `content` on `PreferencesSheet`'s `Column` (line 135) rather than on the `Surface`, the `96 px` (`34.9 dp`) status bar inset sits above the frosted drawer as transparent/dimmed scrim (`top_y = 96 px` even when fully expanded).
  - Note also: `SettingsScreensTest` (`settingsRemainReadableInBothThemes`, `expandedPreferencesSheetOnlyDismissesFromTopHandleZone`, `systemBackClosesTheExpandedPreferencesSheet`) passed on `I7CEJNQCJN4P59AE`.

## Possible Directions (No Code Changed Yet)

1. **Extend frosted backdrop under the status bar when expanded**: pass `contentWindowInsets = { WindowInsets(0, 0, 0, 0) }` to `ModalBottomSheet` and apply `WindowInsets.safeDrawing.only(WindowInsetsSides.Top)` padding *inside* the `frost` `Column` (above `DrawerHandle()`), so the frosted surface covers `y = 0` while content avoids the status bar.
2. **Open directly at expanded height (if desired)**: restore `skipPartiallyExpanded = true` (though note `PreferencesSheet.kt` currently relies on `PartiallyExpanded` -> `Expanded` state transitions and tests `expandedPreferencesSheetOnlyDismissesFromTopHandleZone`).
3. **Sync the installed release APK**: if testing the release app (`dev.lemon.rex`) on the phone, it is still build `169` (`2026-11-20`), which predates the `2cf9a06` removal of `heightIn(max = 600.dp)`.
