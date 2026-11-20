# Why tapping pi's input box does not show the soft keyboard

Date: 2026-11-19
Scope: tap-to-show-IME behavior for the `pi` coding agent (earendil-works/pi-coding-agent) running in a Rexling pane; fork vs upstream (shulhi/rexling) comparison.

## Symptom

With pi in the foreground pane and the Android soft keyboard hidden, tapping
pi's input box does not summon the keyboard. The tap is silently forwarded to
the PTY as a mouse click. The keyboard only opens via the KeyboardBar toggle
button or "take control".

## Root cause chain

1. pi's TUI startup sequence (verified in pi 1.5.2 bundle, `chunk-FF6XOTWB.js`):
   `ESC[?1049h` (alternate screen) + `ESC[?7l` + `ESC[?1000h ESC[?1002h ESC[?1003h ESC[?1004h ESC[?1006h`
   (all-motion SGR mouse tracking, `mouse ?? true`) + `ESC[2J ESC[H` + `ESC[?25l` (cursor hidden).
   pi also emits OSC 133 zone markers (`133;A` prompt start, `133;B` input start, `133;C` on submit).

2. Tap routing — `TerminalView.terminalTap()` (identical in fork and upstream):
   - Mouse-tracking pane → click forwarded via `onMouseClick`; auto-keyboard only if
     `frame.isSemanticInputRow(row)` or `row == inputCursorRow(frame)` and `onCursorTap` fires.
   - No mouse tracking + `hasSemanticPrompt` → semantic-row tap → `keyboard()`.
   - Otherwise → `performClick()` → `keyboard()` when controlled.

3. `isSemanticInputRow` requires `inputFlags` bit 16. Shim `rp_input_flags()`:
   `accepting = at_prompt && (!semantic_seen || semantic_accepting)`.
   libghostty `Terminal.cursorIsAtPrompt()` (src/terminal/Terminal.zig:2941):
   "If we're on the secondary screen, we're never at a prompt" → **false on the
   alternate screen**, regardless of pi's OSC 133 markers. Bit 32
   (`hasSemanticPrompt`, `known`) also requires the primary screen. So both
   semantic branches are dead for pi.

4. `inputCursorRow = cursorY ?: cursorAnchorY.takeIf { allowHiddenCursorInput && cursorAnchorX != null }`.
   pi hides its cursor → `cursorY == null`; `allowHiddenCursorInput` is set only for
   `ProgramKind.CODEX/CLAUDE` (`RexConnection.bindTerminalView`) → null for pi.

5. `onCursorTap → view.keyboard()` is allowlisted to CODEX/CLAUDE
   (fork adds ANTIGRAVITY). pi resolves to `ProgramKind.TERMINAL` (no PI kind;
   only a ProgramCatalog icon) → never fires.

6. The generic `performClick()` fallback is unreachable because pi enables
   mouse tracking (branch 2 wins first).

Net effect: tap = forwarded mouse click, no IME. Same in the upstream app.

## Fork vs upstream comparison

| Piece | Upstream (da7a0f9) | Fork (a43276e) | Pi impact |
|---|---|---|---|
| `terminalTap` / `performClick` / `keyboard()` | — | identical | same |
| `Models.isSemanticInputRow` / `hasSemanticPrompt` | — | identical | same |
| `ghostty_shim.c` (input flags, row marking) | — | identical | same |
| `ghostty-source.json` (libghostty 108cacb) | — | identical | same |
| `allowHiddenCursorInput` kinds | CODEX, CLAUDE | CODEX, CLAUDE | pi excluded in both |
| `onCursorTap` kinds | CODEX, CLAUDE | CODEX, CLAUDE, ANTIGRAVITY | pi excluded in both |
| pi IME viewport anchor | none | `TerminalImeAnchors`: pi → CONTENT (pan to last painted row) | fork pans correctly **after** the keyboard opens; never opens it |

The 33 unmerged upstream commits contain nothing for pi: d181aee (hidden Claude
cursor anchors) and d92d836 (Enter submits) are already ported in the fork.

Conclusion: **not a fork regression**. Upstream never supported tap-to-show-IME
for pi either. The feature simply has no path for an alternate-screen,
mouse-tracking, hidden-cursor TUI. Claude/Codex work because they render inline
on the primary screen and emit OSC 133, so `at_prompt` is true and the semantic
input row is marked (cell attribute bit 30 over rows input_start..input_end).

## Fix options

- **A. First-class pi identity (Kotlin only, smallest).** Add `ProgramKind.PI`
  (names `pi`; path fragments `/@earendil-works/pi-coding-agent/`, `/.pi/agent/`),
  include it in `allowHiddenCursorInput` and `onCursorTap`. Tapping the exact
  hidden-cursor row (cursorAnchorY) opens the keyboard. Limitation: pi's input
  box is ~3 rows tall; border rows still won't match.
- **B. Anchor-row tap fallback (Kotlin only, best UX).** In `terminalTap`'s
  mouse branch, for programs whose `TerminalImeAnchors.anchor` is CONTENT
  (pi, cursor-agent), also treat a tap on the IME anchor row
  (`cursor ?: lastPaintedRow`) as a keyboard request while still forwarding the
  click. Makes the whole prompt area tappable; no native change.
- **C. Honor OSC 133 on the alternate screen (native, widest blast radius).**
  Shim: `accepting = semantic_accepting || (at_prompt && !semantic_seen)` and
  relax `known` for alt-screen when `semantic_seen`. pi's own 133;A/B markers
  would then drive `isSemanticInputRow` exactly like Claude's. Requires host
  tests; diverges from upstream's shim; affects every program that emits OSC 133
  on the alt screen.

Manual paths that already work: KeyboardBar toggle (MainActivity:386),
`takeControl()`.

## Implemented: option A behind a sealed ProgramProfile hierarchy (2026-11-19)

The per-program special cases were scattered across five decision points, three
of them hardcoded `ProgramKind` sets. They are now one sealed hierarchy in
`rexling-android/app/src/main/java/dev/lemon/rex/profiles/`, one file per program:

- `ProgramProfile.kt` — sealed interface with safe defaults (`hiddenCursorInput`,
  `cursorTapKeyboard`, `imeAnchor`, `edgePolicy`, `keyboardSuggestions`) plus the
  `resolve(identity)` first-match resolver and the `matchesCandidates()` basename
  / path-fragment matcher (the former `ImeAnchorRule.matches` semantics).
- `PiProfile`, `CursorAgentProfile`, `ClaudeProfile`, `CodexProfile`,
  `AntigravityProfile`, `EditorProfile`, `YaziProfile` — `data object`s; only
  pi and cursor-agent match by name/path, the rest by `ProgramKind`.
- `ImeAnchor.kt` / `TerminalEdgePolicy.kt` — the two vocabulary enums plus the
  `imeAnchorRow`/`lastPaintedRow` screen extensions, moved from the deleted
  `TerminalImeAnchor.kt` / `TerminalEdgePolicy.kt` rule tables.

Consumption: `RexConnection.bindTerminalView` reads `hiddenCursorInput` and
re-resolves inside `onCursorTap` for `cursorTapKeyboard` (live-identity rule
kept); `TerminalCarousel` resolves once per block and feeds `imeAnchor`,
`edgePolicy` and `keyboardSuggestions`. Visitor was considered and rejected:
the variation axis is the program set, not operations, and every consumer reads
plain data — sealed + data objects give the same exhaustiveness without
accept/visit boilerplate.

pi's tap-to-keyboard chain now works via option A: RXT7 `cursor_position` →
`cursorAnchorY` → `hiddenCursorInput` makes `inputCursorRow()` return it → tap
on that row fires `onCursorTap` → `cursorTapKeyboard` → `view.keyboard()`.

Behavior deltas vs the rule tables (all deliberate):
- pi and cursor-agent additionally get `hiddenCursorInput` + `cursorTapKeyboard`
  (the fix itself; cursor-agent previously only had the CONTENT pan anchor).
- vim and the wider editor family now also disable keyboard suggestions
  (previously only `kind == NEOVIM` did); modal editors never want autocorrect.
- Editor recognition broadened from exact-name/executable-fragment checks to
  basename matching over all reported candidates (safe direction).

Verification: `ProgramProfileTest` (7 cases: pi/cursor-agent/yazi/agents/editors/
generic/matcher), `ImeAnchorTest` (6 screen cases moved over); full unit suite
309/309 green. androidTest compile errors in NeovimShortcutUiTest,
NetworkUiTest and TerminalImeViewportTest pre-date this change (verified on a
stashed tree) and are untouched.
