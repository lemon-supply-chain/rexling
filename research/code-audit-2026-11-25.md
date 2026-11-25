# Code Audit — 2026-11-25

Scope: the uncommitted working tree at base commit `884ab44` (22 files, +638/−314):
the skeuomorphic tile material (`ProgramVectorPicture.kt`, `ProgramStack.kt`), the
countdown tile override, the frost wheel (`NeovimShortcutWheel.kt`,
`NeovimShortcutUi.kt`, `MainActivity.kt`), the preferences-sheet drag guard
(`PreferencesSheet.kt`), the side-rail action order (`TerminalChrome.kt`), the
catalog 1.8.48 sync, and the matching test and doc updates.

Method: full diff read, source review of every touched call path, Material3 1.5.0
bytecode for `SheetState.hide()`, `rememberSheetState` and the back handling in
`BottomSheetKt` / `ModalBottomSheetDialogWrapper`, catalog data checks, the JVM
suite, `ktfmtCheck`, and WebKit renders of the 13 changed tiles. No device run
completed (see *Verification*), so device-level claims below are reasoned from
source and bytecode, not observed.

Verdict: the tile material and the catalog sync are sound — the chrome geometry
matches the artboard, the generated catalog verifies, and the JVM suite is green.
One behavioural regression is serious enough to fix before merging: the
`confirmValueChange` guard that stops body drags from dismissing the preferences
sheet also stops the system back gesture, which is the sheet's primary dismiss
affordance. Two rendering-cost and corner-geometry issues should be resolved in
the same pass.

## Findings

### P1 — must resolve before merging

#### 1.1 Back / predictive back no longer dismisses the expanded preferences sheet

`PreferencesSheet.kt:86-91`

```kotlin
val sheetState =
    rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { next ->
            next == SheetValue.Expanded || !isExpanded || startedInHandleZone
        },
    )
```

The guard is meant to stop a body swipe from dismissing the sheet. It also
applies to every other path that asks `SheetState` to settle on `Hidden`, and
Material3 routes the back gesture through exactly that path:

1. `SheetState.hide()` reads `confirmValueChange` and returns without animating
   when it answers `false` (verified in the 1.5.0 bytecode: `getConfirmValueChange`
   → `Function1.invoke` → early return before `animateTo`).
2. `BottomSheetImpl` builds `animateToDismiss` as `scope.launch { sheetState.hide() }`
   plus `onDismissRequest()` when the sheet ended up hidden
   (`BottomSheetKt$BottomSheetImpl$animateToDismiss$1$1$1` invokes `SheetState.hide`), and
   hands that lambda to the predictive-back handler as `settleToDismiss`
   (`BottomSheetKt$BottomSheet$4$1`), which is also the modal fling settle target.
3. `BottomSheetKt` registers `PredictiveBackHandler` around that `settleToDismiss`
   lambda.
4. `ModalBottomSheetDialogWrapper.cancel()` is overridden to return immediately,
   so the dialog does not consume back on its own.

Consequence: while the sheet is expanded, back does nothing. The only remaining
ways out are the close button, a scrim tap, and a drag that starts in the handle
zone. That is a regression against a standard affordance and an accessibility
problem, and nothing in the diff's tests covers it.

Fix: keep the drag guard, but give back an explicit path — `BackHandler(enabled = isExpanded) { onClose() }`
inside the sheet content registers after Material3's handler and therefore wins.
The alternative, dropping `Hidden` from `confirmValueChange` and intercepting the
header drag instead, removes the belt-and-braces protection that
`blockExpandedBodyDismiss` cannot give for drags that start outside the scroll
column. Either way this needs a device check, because the instrumented suite
could not run here.

### P2 — should resolve

#### 2.1 Per-tile chrome, gradients and paints in the fallback path

`ProgramStack.kt:140-165` builds `SkeuomorphicCardChrome`, a copied plate `Path`
and a gradient `Paint` per composed tile, then `remember`s them per instance. The
chrome only depends on the plate path and the accent colour, and the accent comes
from a small fixed set (14 manifest skins plus the neutral and countdown
palettes). A cache keyed by accent — or building the chrome inside
`SkeuomorphicCardChrome.defaultPlate` once per process — avoids one native
`RadialGradient`, one native `LinearGradient`, five `Paint`s and a `Path` copy per
tile, which matters in `ActivitySheet` and `FloatingPaneDock` where tiles are
composed in lists.

#### 2.2 Withdrawn: the tilted stack tiles are cached, and the marquee corner fits

Both claims in the first draft of this audit were wrong, and the measurements are
kept here so they are not re-raised:

- `ProgramStack.kt:121` calls `drawTile(…, 0f)` and applies the tilt with
  `graphicsLayer { rotationZ }`, so the stacked cards hit the bitmap cache. The
  rotated branch of `drawTile` has no caller in main sources at all — it is worth
  deleting the parameter or documenting why it exists, but it costs nothing today.
- The marquee corner is not a mismatch. Measured against the compiled plate: the
  plate's nearest point to its own bounding corner sits 5.68 artboard units along
  the diagonal, and the 7 dp ring on a 34 dp tile sits 6.19 units from the tile
  corner, i.e. 0.68 dp inside the card edge at 2.75× density. The squircle's
  visual corner radius works out to ~7.6 dp, so `7.dp` is a good approximation.

### P3 — worth cleaning up

1. **Square fallback when the plate is missing.** `ProgramStack.kt:171-174` falls
   back to `drawRect(colors.first())` when `SkeuomorphicCardChrome.defaultPlate`
   returns null. `defaultPlate` returns null when `program_tiles.json` has no
   parseable `plate`, and the result is an unrounded rectangle — the pre-change
   code drew a rounded rect. Cheap fix: keep a `RoundedCornerShape(radius)`
   background behind the canvas.
2. **`sheetStateRef` is written during composition.** `PreferencesSheet.kt:92`
   assigns `sheetStateRef = sheetState` on every composition so that
   `confirmValueChange` can read `isExpanded`. It works (the second write is a
   no-op), but the sheet state also depends on
   `rememberModalBottomSheetState` keying on the `confirmValueChange` lambda:
   `rememberSheetState` calls `Composer.changed` on it. That lambda is only stable
   because Kotlin 2.2's strong skipping memoizes it; a future capture of an
   unstable value would recreate `SheetState` on every recomposition and reset the
   sheet mid-gesture. A `LaunchedEffect(sheetState) { snapshotFlow { sheetState.currentValue } … }`
   or passing the state down explicitly would remove the implicit dependency.
3. **Two observable states that nothing observes.** `startedInHandleZone`
   (`:79`) and `handleZoneBottomPx` (`:80`) are read only from the pointer-input
   lambda, `confirmValueChange` and the nested-scroll connection, never from
   composition, so `mutableStateOf`/`mutableFloatStateOf` cost two recompositions
   per gesture and one on first layout for nothing. Plain holders suffice.
4. **Two alias tables drift silently.** `program_index.json` `aliases` is
   generated from `artwork/program-icons/rexling/manifest.json`;
   `ProgramVocabulary.Default.aliases` is hand-written. `tofu`, `ue`,
   `claude-code`, `g++`, `gcc`, `ipython`, `lazygit`, `tig` and `vite-plus` exist
   only in the manifest, `nu`, `rex-cli`, `rex-server` only in the vocabulary
   (all three are dead there: `nu` is in `ProgramIdentity.shellNames` and the
   `rex-*` names are matched by the `REX` kind branch first). Observable effect:
   `resolve(listOf("node", "tofu"))` returns the `NODE` identity, so OpenTofu
   launched under a JS runtime keeps the node tile. The new
   `everyProgramTileIsReachableFromTheVocabulary` test guards the executable set
   but not the alias targets; extending it to `aliases.values ⊆ executables` and
   to agreement with the manifest would close this.
5. **The sync script still needs a manual metadata edit.**
   `sync_superlogical_icons.mjs` rewrites `sha256` and `glyph_sha256` but not
   `catalog_version` / `catalog_published_at`, so the version in
   `metadata.json` is hand-edited after every sync (this one included) and can
   silently disagree with the tiles. `catalog.packageRelease` is already parsed
   for the log line — write it back.
6. **Side-rail order has one-sided coverage.** `PaneChromeTest` asserts
   `activity, switcher, keyboard` with `sideRail = true` for both rail styles, but
   the bottom bar's `keyboard, switcher, activity` branch (`TerminalChrome.kt:1624-1630`)
   has no ordering assertion, so a swap there would pass.
7. **The rim/specular test samples one column.** `skeuomorphicTileStackDraws…`
   reads the vertical centre line only; an asymmetric or missing left/right rim
   would not be caught. Sampling both corners would cost two more `getPixel`
   calls.
8. **Cached tile bitmaps are `ceil`-sized but blitted 1:1.**
   `ProgramVectorPicture.kt:219-233` allocates `ceil(width) × ceil(height)` and
   then `drawBitmap(cached, 0f, 0f)` with no scale, so a fractional width leaves a
   sub-pixel transparent sliver and the cached path is up to 1 px wider than the
   direct path. Pre-existing, but now that both paths share the chrome it is worth
   making them agree.

## Resolution (2026-11-25, same day)

Device: Redmi `21091116AC` (Android 13), kept awake with `stay_on_while_plugged_in=7`.
Every fix was built and exercised on the device before the next one.

| Finding | Fix | Device check |
| --- | --- | --- |
| 1.1 back | `BackHandler(enabled = isExpanded) { onClose() }` inside the sheet content | `SettingsScreensTest.systemBackClosesTheExpandedPreferencesSheet` (new, injects `KEYCODE_BACK` synchronously) |
| 2.1 per-tile chrome | `SkeuomorphicCardChrome.forAccent` + `plateFill` caches keyed by accent/palette, one shared plate path | `ProgramTileRenderingTest` skeuomorphic + countdown |
| new: countdown copy | `CountdownIcon` uses `terminalProgressDescription` instead of the English-only `TerminalProgress.description` | `countdownTileUsesBlackPlateAcrossCatalogAndFallbackPrograms`, which failed on this zh device before the fix |
| 3.1 square fallback | rounded `drawRoundRect` when the plate is missing | tile rendering tests |
| 3.2 sheet state | plain `SheetStateHolder` plus a remembered `confirmValueChange` lambda | `SettingsScreensTest` (3 tests) |
| 3.3 gesture state | plain `SheetGesture` holder, no composition state | `SettingsScreensTest` (3 tests) |
| 3.4 alias drift | seven aliases added (`gcc`, `g++`, `ipython`, `lazygit`, `tig`, `tofu`, `ue`), new `catalogAliasesResolveThroughTheVocabulary`, and `ProgramArtworkTest`'s candidate case moved to an artwork-only alias | JVM + `catalogResolvesTilesGlyphsVariantsAndAliases` |
| 3.5 sync script | writes `catalog_version`, `catalog_published_at` and refreshes the note's version | `--write` rerun is idempotent, `--check` still verifies |
| 3.6 bottom bar order | fixture renders both `sideRail` values and asserts each order | `PaneChromeTest` (12 tests) |
| 3.7 one-column rim | both side rims sampled | `skeuomorphicTileStackDrawsOuterRimShadowAndSpecularAtAllSizes` |
| 3.8 ceil blit | cached bitmap blitted into the exact bounds | `sizeCachedTileReplayIsDeterministic` + tile tests |

Left in place: `drawTile`'s unused `rotation` parameter, because another agent was editing the
same file and the parameter costs nothing.

Final state: JVM 329 passed / 1 optional skipped, `ktfmtCheck` clean, and 27 instrumented tests
across `ProgramTileRenderingTest`, `SettingsScreensTest`, `PaneChromeTest` and
`TerminalProgressUiTest` green in a single run.

Caveat: this MIUI device is flaky across repeated instrumentation runs. The test APK uninstall
can fail (`DELETE_FAILED_INTERNAL_ERROR`) and an activity occasionally fails to attach
(`No compose hierarchies found`), and which test it hits moves between runs. Per-class runs and
the full 27-test run were green; an extra `captureToImage` round in the side-tabs fixture was
enough load to trigger it, so that round now asserts order only.
