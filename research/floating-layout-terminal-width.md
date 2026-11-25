# Terminal width when switching panes in a floating layout

## Symptom

With a left or right floating tab bar enabled, switching to another pane or tab
shows the terminal at the width it had while the rail was still docked, then it
snaps to the floating width a moment later.

## Why the width is not "already known" everywhere

Rex stores the terminal size **per block**, and a client sets it only for a
block it owns (or when nobody owns it). There is no session-wide width to push
when the floating toggle changes:

- The app knows the **current viewport's** grid (`RexConnection.lastSize`). That
  is why the visible pane resizes as soon as the layout changes.
- Other panes' blocks keep the size from the last client that viewed them. The
  carousel only reports dimensions for the center pane
  (`TerminalCarousel.kt:269`), and the tiled layout calls `client.resize` only
  for the live pane (`MultiPaneLayout.kt:399`); neighbours cache their grid but
  never push it.
- `selectBlock` / `openPane` used to send only `block` / `session`. The engine
  opened the data stream with the block's stored size, the app displayed that
  first frame, and only then `claimActivePane` sent `take` + `resize` with the
  floating grid. That intermediate frame is the jump.
- Pre-resizing every block when floating toggles is not equivalent:
  - Multi-pane layouts have a different width per pane; there is no single grid
    to apply.
  - Background blocks may be owned by the desktop client; the server defers
    such a resize until that client takes the block, so the write is wasted and
    can race another client.
  - It sends N RPCs and a SIGWINCH repaint to every background TUI the user is
    not looking at.

## Fix

The grid the app already has rides with the switch instead of being recomputed:

1. `RexConnection.gridArgs` (`RexConnection.kt:859`) appends `columns` / `rows`
   from `lastSize` to the `block` and `session` commands
   (`selectBlock` at `:1006`, `openPane` at `:1049`).
2. `pane_size` (`engine.rs:191`) parses and clamps the optional grid.
3. `open_block_stream` (`engine.rs:3100`) sends a control `resize` for the
   incoming block **before** `Data::open` (`engine.rs:3215`). The server applies
   it immediately when the caller owns the block or nobody does, and otherwise
   defers it until this client takes ownership. The first streamed screen is
   therefore already at the current layout's grid; no extra `take` / `resize`
   round trip is needed.

The switch-time value is not a new calculation: it is the viewport grid the
client already measured. Only the pane being opened is touched.

## Verification

- `live_block_switches_size_the_incoming_pane_before_its_stream` (ignored like
  the other live tests; needs `REX_TEST_SERVER`):
  `REX_TEST_SERVER=https://… cargo test --manifest-path rexling-core/jni/Cargo.toml live_block_switches_size_the_incoming_pane_before_its_stream -- --ignored`
  - Without the fix the first frame after the switch is the stored size
    (`80` columns); with it the first frame is the requested `61` / `71`.
- Native: 109 jni tests and 50 core tests pass; `cargo fmt --check` is clean.
- Android: `ktfmtCheck`, unit tests, and 37 tab/floating instrumentation tests
  pass.

## Limits

A pane owned by another client still shows that owner's size until this client
takes it; the fix cannot pre-resize someone else's pane. That is intentional and
matches Rex ownership.
