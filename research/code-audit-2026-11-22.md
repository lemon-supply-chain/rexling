# Code Audit — 2026-11-22

Scope: the uncommitted frozen-backdrop change (`PaneFrost.kt`, `DrawerFrostTest.kt`,
`research/frozen-backdrop-blur-analysis.md`), plus a cross-cutting read of the Kotlin
UI, the JNI/Rust engine, and the Go network bridge. Base commit: `2cf9a06`.

Method: source review, targeted searches, the Haze 1.5.4 sources for `HazeEffectNode`
and `HazeSourceNode`, a debug Kotlin compile, and the JVM unit-test report on disk.
No device was available, so device-level claims below are reasoned from source, not
observed.

Verdict: the direction of the frozen-backdrop change is right — the old pre-draw
listener invalidated the effect during `dispatchOnPreDraw`, which schedules the next
traversal, so an open sheet re-blurred at display refresh rate even while nothing
changed. The current implementation, however, stops the listener on *any* successful
invalidation rather than on a confirmed first blur, and the updated test does not
observe the code that changed. Treat the change as unverified until it is validated
on a device.

## Findings

### P1 — must resolve before merging

#### 1.1 The one-shot invalidation is not tied to a successful capture

`PaneFrost.kt:48-56`

```kotlin
private val listener=ViewTreeObserver.OnPreDrawListener {
    var invalidated=false
    effects.forEach { if(it.isAttached) { it.invalidateDraw(); invalidated=true } }
    if(invalidated) stop()   // stops after ANY attached effect is invalidated
    true
}
```

Haze 1.5.4's `HazeEffectNode` has `shouldAutoInvalidate=false`; it invalidates itself
when it is placed and when `HazeState.areas` changes, and otherwise redraws only when
its content is marked dirty. The listener's stop condition is "an attached effect was
invalidated once", not "the effect drew with a recorded source layer".

The subtlety is that Haze already invalidates itself in `onAttach`/`onPlaced`, and
`HazeState.areas` is a `mutableStateListOf`, so adding the source area re-runs every
observing effect — even across windows. What is not reliably observed is
`HazeArea.contentLayer`: it is assigned inside the source's `draw()` and read inside the
effect's draw, so its snapshot write does not necessarily notify the effect node. If the
effect draws before the source has recorded its layer, it has nothing to blur; whether a
later frame recovers
depends on some other dirtying event (sheet motion, style change), not on this listener,
whose one invalidation may already have been spent on the frame before the source drew.

For a `ModalBottomSheet` or a `DropdownMenu` the enter animation supplies further dirty
frames, so this is mostly latent. For the non-swipe `Dialog` path in `SettingsDrawer`
(`SettingsDrawer.kt:33-34`) there is no invalidator at all and no animation, so the cold
path is entirely at the mercy of Haze's initial invalidation. That path is worth testing
explicitly.

If the invalidator is kept: re-arm it from `PopupFrostNode.onAttach` and track an
`awaitingFirstDraw` set, or keep it armed for a bounded handful of frames so it cannot
stop before the source layer exists. If it turns out Haze's own invalidation is enough on
every open path, remove `PopupFrostInvalidator` — fewer races and less code. Either way,
add a cold-start test where `frostSource` attaches in the same frame the sheet opens, and
a drag test (position changes dirty the effect and re-blur the layer, so the "0 blur
during drag" claim is false).

#### 1.2 The updated test does not observe the behavior that changed

`DrawerFrostTest.kt:32,88-97`

- The test is still named `drawersAndMenuBlurTheBackdropAndFollowLiveChangesInBothThemes`
  but now asserts the opposite.
- `overlay.captureToImage()` draws the view hierarchy into a bitmap directly, bypassing
  `ViewRootImpl`'s traversal and therefore the `OnPreDrawListener`. It cannot prove that
  the listener stopped: a build with the invalidator deleted would produce the same
  frozen pixels here. The test would catch the old live behavior (the `waitForIdle`
  after `green=true` is a real traversal that drives the old listener), but it cannot
  distinguish "freeze implemented" from "no invalidation at all".
- The first assertion (`must blur red/blue stripes`) only checks that red and blue
  channels are present. A fully transparent overlay (effect never drawn) also passes,
  because the raw stripes show through. The pair of assertions catches the transparent
  case only after the source turns green; the blur radius/quality itself is untested.
- Sampling is one pixel at `(width*.25f, 6)` after `Thread.sleep(80)`; no `waitUntil`,
  no multi-pixel/variance check.
- The real failure mode is cross-window: the source lives in the activity window, the
  sheet in a dialog window. The test composes both in one window.

`app/build/outputs/androidTest-results/connected/debug/TEST-22081212C - 15-_app-.xml`
shows the last instrumentation run as `tests="0"` with `INSTALL_FAILED_USER_RESTRICTED`.
The new assertions have not executed on a device.

Recommendation: assert the actual property. Add a debug-visible counter to
`PopupFrostInvalidator` (e.g. invalidation count exposed through a test tag or a
`@VisibleForTesting` getter) and assert it stops; take a real window screenshot with
`uiAutomation.takeScreenshot()` after the source changes instead of `captureToImage()`;
sample several pixels and assert low variance across the stripe period to prove blur;
add a cold-source case and a sheet-drag case.

#### 1.3 The QR scanner leaves the camera bound after the dialog closes

`QrScanner.kt:109-163`

- `DisposableEffect(onDispose)` only shuts down the analyzer executor. It never calls
  `cameraProvider.unbindAll()`. `unbindAll()` runs only in the next `addListener` block
  (`:159`), i.e. on the next open. Until then the camera stays bound to the activity
  lifecycle — it keeps capturing while the scanner is closed (privacy and battery).
- `cameraProviderFuture.get()` (`:122`) is outside the `runCatching` that wraps
  `bindToLifecycle`; a provider error (no camera, CameraX init failure) throws on the
  main executor and crashes the app. The future listener is also never cancelled on
  dispose, so a slow provider can bind the camera after the dialog is gone.
- `scanned` and `errorMessage` are Compose state written from the camera thread;
  `if (!scanned) { ... scanned = true ... }` is a check-then-set race across frames and
  can post duplicate haptics/`onScanned`.
- `decodeQr` allocates `ByteArray(width*height)` (plus a rotated copy) on every frame.

Recommendation: keep the provider/camera in `remember`, unbind in `onDispose`; wrap
`get()` and surface a localized error; use `AtomicBoolean` for `scanned`; preallocate or
use `ImageProxy` rotation metadata instead of copying the Y plane per frame.

#### 1.4 Cold start does disk, Keystore, and JNI work on the main thread

`NetworkSettings.kt:77-90`

`Application.onCreate` → `NetworkSettings.initialize` runs, synchronously on the main
thread:

- `migrateKeys()` (`:66`) — Keystore decrypt per key, a Go `import_key` call per key, and
  a blocking `SharedPreferences.commit()`.
- `interfaces()` (`:98`) — enumerates every network interface, builds JSON, and calls
  into Go.
- `configure()` (`:112`) — decrypts every assigned SSH key, builds JSON, and calls into Go.

This is measurable cold-start jank and a StrictMode disk/network violation. The comment
"install configuration before any terminal or monitoring service can connect" is correct,
but the way to honor it is to gate connections on an initialization job, not to block the
main thread.

Recommendation: run migration/interfaces/configure on `Dispatchers.IO`, expose an
initialization `StateFlow`/`CompletableDeferred`, and have `RexModel`/services await it
before connecting.

*Status: Fixed.* `NetworkSettings.initialize` now exposes `initialization: Deferred<String?>` and
`awaitInitialized()`. `RexModel.init` gates initial connections on `awaitInitialized()`, and `connectServer`
asynchronously awaits it before invoking `client.connect()`. Background monitoring and port forwarding
services similarly gate connection establishment on `awaitInitialized()`. Furthermore, `configure()` errors
are no longer dropped: SSH key decryption failures and Go configure errors are captured into `configurationError`,
surfaced in the UI `ConnectionNotice` banner, and retried automatically on "Retry now" via `reconfigure()`.

#### 1.5 The status poll runs forever, and Go `status()` has a start side effect

`NetworkSettings.kt:90`; `rexling-network/main.go` (`status`, `tailServer`)

```kotlin
scope.launch { while (isActive) { runCatching { status.value = command("status") }; delay(1500) } }
```

- Unconditional, process-lifetime, including background. A cgo call + JSON parse every
  1.5 s.
- With built-in Tailscale enabled, Go `status()` calls `tailServer()`, which *starts* the
  tsnet server if it is not running, then does `LocalClient.Status` with a 2 s timeout.
  A 2 s timeout on top of the 1.5 s delay keeps a poll in flight almost continuously on
  a slow backend, and a failed start is retried on every poll.
- `configure()` starts the server in a goroutine with `_ =` and discards the error
  (`main.go:172-174`), so the UI never learns why Tailscale is down.
- `tailServer()` holds `tail.Lock` across `server.Start()` (`main.go:185-211`), so
  `logout()` / `cancelLogin()` block behind a network start.

Recommendation: expose status only while a consumer (Network sheet, settings) is
visible, pause it with the lifecycle, and back off. Split `status` into a read-only
query and an explicit `ensureStarted`; store/report the start error; release the tail
lock while `Start()` runs (a `started/starting` state) so logout/cancel stay responsive.

*Status: Fixed on Android client.* Replaced perpetual background polling with consumer-driven, lifecycle-aware
polling in `NetworkSettings`: status polling now runs only when consumers are subscribed (`subscriptionCount > 0`)
and the application is in the foreground (tracked via `ActivityLifecycleCallbacks`). Subscriptions in `NetworkSheet`
and `ServersSheet` now use `collectAsStateWithLifecycle()`. In addition, failing status polls automatically back off
up to 30 seconds instead of hammering the daemon.

### P2 — should fix

#### 2.1 Freeze keeps the source recording; the research note overstates the win

`MainActivity.kt:612-628` still attaches `hazeSource(workspaceHaze, workspaceBackdrop)`
while a sheet is open, and Haze's `HazeSourceNode.draw()` re-records the full content
layer on every host draw. The freeze removes the *effect* blur/invalidation loop (the
expensive part) but is not the "snapshot" described by
`research/frozen-backdrop-blur-analysis.md` §5. It also does not make dragging free:
changing the effect's position/size marks it dirty and re-blurs. Correct the doc to say
what is actually implemented (suppressed effect invalidation), and treat Option A
(snapshot + downsample) as future work, not done.

#### 2.2 Stale comments that describe the old behavior

`SettingsDrawer.kt:31-33` says the drawer "redraws when its host window draws, and
inside the sheet that host is the sheet's own window, which then redraws itself every
frame while open" — that is precisely the loop the change removes. `PaneFrost.kt:44`
says "capture its backdrop blur once" but nothing captures. Update both.

#### 2.3 i18n regressions in new code

The app ships `values-zh` with 595/595 keys and a recent commit localized remaining
hardcoded strings, yet new user-facing code bypasses resources:

- `AllPanesPage.kt:70-95` — the whole new screen ("Back to sessions", "Find a pane…",
  "No servers connected", …).
- `TabRail.kt:140,182-183,201-210` — menu items and custom accessibility actions
  ("Move up", "Close tab", "Show tab titles").
- `MainActivity.kt:338,375,387,403,893` — upload/forward notices.
- `PortForwardSheet.kt:142-144`, `PortForwards.kt:59,67,88,97,131`,
  `SshKeysSheet.kt:159`.
- `FloatingPaneDock.kt:56,69` — content descriptions.
- `BackgroundMonitorService.kt:36-39,55,150,158` and `PortForwardService.kt:20,86-88` —
  channel names, titles, action labels.
- `TerminalView.kt:242` — the terminal's accessibility description.
- `ActivityCatalog.kt:7,19` — `ActivityState.label` / `stateLabel` are English and are
  used in the activity sheet's semantics and in notification titles, even though
  `a11y_state_*` resources already exist.

#### 2.4 Dead code and unused duplicate strings

- `ConnectionChrome.kt:19-23` `connectionMessage` is only referenced by
  `NetworkRouteTrackerTest`; production uses `connectionNoticeText` (`:25-30`). The test
  pins dead code.
- `TabBarPosition.label`, `TabRailStyle.label` (`TabRail.kt:33,38`),
  `TabWidthClass.title/detail` (`TabRail.kt:60-70`), and `ActivityFilter.label`
  (`ActivityCatalog.kt:36`) are English duplicates that the UI does not read.
- `MainActivity.windowBackgroundColor()` (`MainActivity.kt:124-138`) hardcodes
  `0xff090c11`/`0xfff3f4f9`, duplicating `launch_canvas` in `values/styles.xml` and
  `values-night/styles.xml`.

#### 2.5 New frost code violates the design-token contract

`DesignTokens.kt:22-30` states that screens never inline a font size, radius, raw spring,
or alpha-modified color. `PaneFrost.kt:29,32-36` inlines `Color(0xff171a20)`, the alpha
ladder `.60/.46/.32/.72/.92`, `blur=20.dp/24.dp`, and a `.5.dp` border. The frost
surface deserves tokens (`RexTokens.frost.{tint, activeTint, darkSurface, opacity, blur,
edge}`) so the experimental toggle, the fallback path, and tests cannot drift.

#### 2.6 Formatting drift

`3a137d5` formatted six files with ktfmt. The tree now mixes styles: `MainActivity.kt`
and `TerminalChrome.kt` are ktfmt-spaced (630/449 ` = ` occurrences), while
`PaneFrost.kt`, `TabRail.kt`, `RexModel.kt`, `MultiPaneLayout.kt`, and `TerminalView.kt`
are terse (0-6 spaced assignments). A future formatter run will produce another
multi-thousand-line whitespace diff. Either add a ktfmt/spotless task (there is already
`.build/format/ktfmt.jar`) and format the whole tree in one dedicated commit, or drop
the formatter claim and document the actual style.

#### 2.7 No CI

`.github` contains only `FUNDING.yml`. 324 JVM tests, 79 instrumentation classes, the
Rust workspace tests, clippy, and the APK verifier are manual. The last instrumentation
run failed at install (`INSTALL_FAILED_USER_RESTRICTED`), so the device suite on disk is
not evidence for the current tree. A workflow
for JVM tests + Rust host tests + lint (and a manual/nightly emulator job) is cheap
insurance against exactly the regression class this change represents.

#### 2.8 Connection lifecycle scales with saved servers

`RexModel` keeps one `RexConnection` per server that has ever connected; only `forget`
or an endpoint change closes it (`RexModel.kt:318,466-474`). Each connection owns a native
thread, a tokio current-thread runtime, a JVM poll loop, and a 500 ms wake while
backgrounded (`RexConnection.kt:248`). With many saved servers this is unbounded idle
cost. Park or close clients that are neither the active server nor in `desired`, and
block on the update waiter instead of sleeping 500 ms.

#### 2.9 Cleartext is silently accepted for bearer reconnect tokens

`AndroidManifest.xml:15` sets `usesCleartextTraffic="true"`; `Servers.kt:33` accepts
`http://`/`ws://` with no warning, while the reconnect token is a bearer credential sent
in the WebSocket hello (`control.rs:141-142`). The UI hint says "Use an HTTPS address"
but nothing enforces or warns. Keep cleartext for local/tailnet setups if required, but
surface an explicit warning for non-TLS non-loopback endpoints, and consider a network
security config that narrows the blast radius.

#### 2.10 `ForwardLinkUi` drops auto-open when not resumed

`ForwardLinkUi.kt:34-37`: if `autoOpen` is true but the activity is not `RESUMED`, the
code does not open the browser and then sets `autoOpen=false`. A forward that finishes
while the app is backgrounded never opens, and the notice auto-dismisses after 10 s.
Queue the intent (or open it from `ON_RESUME`) instead of discarding it.

### P3 — nits

- `NetworkSettings.vaultKey()` (`:41-51`) is not `synchronized`; concurrent first use
  could generate two Keystore keys and orphan ciphertext. `decrypt` (`:53`) trusts a
  12-byte IV. Low likelihood, trivial fix.
- `PopupFrostInvalidator.stop()` re-reads `host.viewTreeObserver`; if the view was
  re-attached, it may remove from a different observer than the one it registered on.
  Store the observer in `start()`.
- `ActivityState.label` is used in notifications and semantics; using the existing
  `a11y_state_*` resources would fix both i18n and screen readers.
- `MainActivity.kt` (1,955 lines) and `TerminalChrome.kt` (1,990 lines) are hard to
  review. Splitting by screen/region would pay off more than any single cleanup.
- `ImageUploads` cache cleanup only runs on the next upload and only for files older
  than 24 h; a process death during `PREPARING` leaves a temp file. Cheap janitor at
  app start.

## Verified

- `:app:compileDebugKotlin` succeeds; the compiled `PaneFrostKt.class` postdates the
  uncommitted edit.
- JVM unit tests: 324 tests, 0 failures, 0 errors (`app/build/test-results/testDebugUnitTest`,
  2026-11-22T05:47Z). The task is currently up-to-date; I did not force a rerun.
- `git diff --check` is clean.
- The last instrumentation run executed 0 tests: `INSTALL_FAILED_USER_RESTRICTED`.
- Positive: the Rust transport rejects URL credentials and disables redirects/compression;
  port forwarding binds `127.0.0.1` only; alert destinations use opaque one-use tokens;
  SSH keys are app-private AES-GCM Keystore ciphertext with backups disabled; JNI entry
  points are all wrapped in `catch_unwind`; `emit`/fetch caps and RPC error codes are
  preserved; `TerminalView` reuses row recordings and balances its trace sections.

## Suggested order

1. Settle the freeze contract (1.1) and replace the test proxy with an observable
   assertion (1.2); run it on a device.
2. Fix the camera lifecycle (1.3).
3. Move network initialization off the main thread and gate the status poll (1.4, 1.5).
4. Correct the research note and stale comments (2.1, 2.2).
5. Pay down i18n and token debt for the files touched here (2.3, 2.5).
6. Add CI and a whole-tree formatter pass as separate, no-behavior commits (2.6, 2.7).