# Code Audit — 2026-11-25, Tab Stack Fan Working Tree

Scope: the uncommitted working tree at base `639f52f`: the tab stack fan
(`TabStackFan.kt`, `MainActivity.kt`, `TerminalChrome.kt`, `TabRail.kt`,
`FloatingTabBar.kt`), the re-added launch reveal (`LaunchReveal.kt`,
`MainActivity.kt`, `todo.md`), the preferences drawer handle
(`PreferencesSheet.kt`, `PaneFrost.kt`, `SettingsScreensTest.kt`), and their
tests and research notes.

Method: full diff read, source review of the fan's layout and pointer paths, a
Compose hit-test check of the scrim against a sibling bar, git history for the
launch reveal, the JVM suite and `ktfmtCheck`. The instrumented suite could not
run: the only attached device refused the test APK install
(`INSTALL_FAILED_USER_RESTRICTED`), so device-level claims below are reasoned
from source and the Compose hit-test model, not observed.

Verdict: the fan geometry and paging model are well covered by unit and
instrumentation tests, and the preferences back-gesture regression from the
previous audit is fixed. Two issues should be resolved before merging: the
fan's scrim makes the tab bar unreachable while the fan is open, and the launch
reveal is re-enabled unchanged after having been removed for first-frame jank.

## Findings

### P1 — must resolve before merging

#### 1.1 The fan scrim swallows every tap on the floating tab bar

`TabStackFan.kt:508-535`, `MainActivity.kt:1316-1392`

The fan is composed **after** `FloatingTabBar` in the same `Box`, so its
full-size scrim is above the bar. The scrim leaves taps inside
`FanLayout.safeZone` unconsumed "so the underlying tab bar keeps receiving
clicks", but Compose pointer input is exclusive between siblings: the hit test
stops at the first hit sibling, and the scrim (a full-size `pointerInput` node)
is hit for every point over the bar. Not consuming the event only lets it travel
up the parent chain; it never reaches the bar sibling.

Consequences while the fan is open:

- Tapping the active tab again cannot toggle the fan closed
  (`onFanToggle` is unreachable).
- Tapping another tab cannot switch panes or dismiss the fan through the bar's
  `onFanDismiss`.
- The grip's long-press drag cannot start, so `onDragStart` cannot dismiss the
  fan.
- Only tapping outside the safe zone, Back, selecting a card, or opening a
  drawer closes the fan.

`TabStackFanTest` checks that safe-zone taps do not dismiss the fan, but it never
asserts that the bar receives them, so the suite stays green.

Fixed in the same pass: `TabStackFan` now takes a `bar` slot and composes it
between the dismiss scrim and the cards, so the bar's tabs, grip and actions win
the hit test; `TabStackFanTest` gained a real-touch test that closes the fan by
tapping the active tab through the open fan.

Fix options: split the scrim and the cards into separate layers and place the
bar above the scrim (cards stay on top of both), size the scrim to exclude the
bar/safe zone, or use `PointerInputModifierNode.sharePointerInputWithSiblings()`
on the scrim. Add a test that clicks a bar tab through the open fan.

#### 1.2 The launch reveal is re-enabled unchanged after being removed for jank

`LaunchReveal.kt`, `MainActivity.kt:66`, `todo.md:346`

`LaunchReveal.kt` is byte-identical to the file deleted in `a2ff121`
("remove the disabled launch reveal"): `diff` against
`a2ff121^:…/LaunchReveal.kt` is empty. That commit recorded that the paper-tear
splash had been disabled with an `if (false)` guard because it **janked the
first frame**, and the review follow-up removed it. This tree re-adds the same
implementation and calls `installLaunchReveal()` on Android 12+ again, with no
change to the animation or its full-screen per-frame path drawing. The same
`todo.md` edit also rewrites the historical status from "disabled on device (it
janked the first frame)… deleted" to a plain "Added", removing the reason.

Fix: re-validate on a physical device (the previous attempt failed) before
keeping it, or leave it disabled; either way restore the outcome note in
`todo.md` so the next reviewer does not repeat the experiment.

### P2 — should resolve

#### 2.1 The fan's first active frame uses root coordinates as local

`TabStackFan.kt:467-478`

The composable returns early while inactive, so its container origin is only
captured by `onGloballyPositioned` after the fan becomes active. On that first
frame `containerOrigin` is still `Offset.Zero` and the root-space anchor is
treated as local: the cards start at the wrong place and
`resolveFanDirection` can pick the wrong side, then both snap on the next
frame. Capture the origin while inactive (compose the origin probe before the
early return) or pass the workspace origin in from `MainActivity`.

#### 2.2 Fanned cards have no visible titles

`TabStackFan.kt:660-665`, `MainActivity.kt:1334-1376`

`TabFanItem.title` is only used for `contentDescription`; the card itself is a
bare `PaneProgramTile`. Two panes of the same program (two `zsh`, two `nvim`)
are visually identical apart from the selected border, so the user cannot tell
which pane a card opens. The fan research (`research/tab-stack-fan-out-analysis.md`
§4.2.7) explicitly calls for a compact title/label and a working/unread badge.
Add a short label (or a title pill under the card) and keep the selected and
working states distinguishable.

### P3 — consider

#### 3.1 A single tap on a primary card is dead or pages

`TabStackFan.kt:611-640`, `TabStackFanTest.kt:164-170`

The middle primary card's single tap does nothing and the outer primary cards
page instead of selecting; selection always needs a double tap. This is
intentional and tested, but a tap that produces no feedback is a discoverability
risk. Consider selecting on a single tap and paging only from the stacked
layers.

#### 3.2 Stacked cards and the scrim lack accessibility labels

`TabStackFan.kt:655-680`, `TabStackFan.kt:529-534`

Only primary cards get `contentDescription`; the stacked paging cards and the
full-screen scrim are focusable clickables with no label. Give the stack cards a
localized "more panes" description and mark the scrim as decorative (or hide it
from accessibility).

#### 3.3 Stale `windowStart` after the pane count shrinks

`TabStackFan.kt:456-457`

`LaunchedEffect(items.size)` only resets `windowStart` when it exceeds the item
count. Between those values the state can stay above `maxStart` while
`computeFanLayout` clamps it, so the paging state and the visible page disagree
until the next tap. Clamp the state itself, not only the layout.

#### 3.4 Minor costs and lifecycle

- `MainActivity.kt:1334-1376` recomputes `fanItems` for the current window even
  when the fan is closed (`fanSourceWindow` falls back to `window`).
- `LaunchReveal.kt:65-70` never cancels its 560 ms `ValueAnimator`; an activity
  destroyed mid-reveal keeps the paper view alive until the animation ends.
- The fan is wired only for floating bars; docked bottom bars and docked side
  rails do not fan, while the research note recommended a workspace-level
  overlay for both. Record this as an intentional scope limit if so.

## Verification

- `ktfmtCheck` and `testDebugUnitTest` pass on the tree, including
  `TabStackFanGeometryTest` (direction, three-card window, side-stack caps).
- The tab/floating instrumentation suite (37 cases) passed on the previous
  device before this audit; `TabStackFanTest` was not executed here, and the
  current device refused the test APK install (`INSTALL_FAILED_USER_RESTRICTED`).
- No device run for the new launch reveal or the scrim pass-through; those two
  findings are reasoned from source, the Compose hit-test model, and git history.
