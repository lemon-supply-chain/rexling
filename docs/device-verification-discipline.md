# Device verification discipline for input-path changes

Written 2026-11-15 after a failed change set had to be reverted in full. It
records how that attempt went wrong, which techniques actually produced evidence,
and the rules this project should follow next time. The code from that attempt is
gone; this note stays so the same shape of failure does not repeat.

## What happened

A request for one behaviour ("holding the backspace key should keep deleting")
grew into one working tree containing four independent behaviour classes:

1. structured key events for eight dock keys (a wire-format change),
2. a hold-repeat driver in `TerminalView` for `KEYCODE_DEL`/`KEYCODE_FORWARD_DEL`,
3. IME composition editing (`trimComposing`/`flushComposing`, code-point deletion),
4. input-queue binding and a new on-screen notice row.

Six release builds went onto the developer's own phone while they were working in
a live Rex session. In the middle of that, fixture sessions were created, the app
was driven to one of them, and the fixture was destroyed while the app was still
attached. The reported symptom became "nothing works: keyboard, dock keys, Del" —
a description compatible with at least five different causes:

- the pane was owned by another client, so the app was in viewing mode,
- the app was attached to a session that no longer existed,
- the navigation id used in the input-target comparison had drifted,
- the new structured events were rejected or ignored downstream,
- the hold-repeat driver swallowed the keyboard's own repeats.

Because all four change classes sat in one uncommitted tree, none of those could
be eliminated by bisecting. The only resolution was the developer's: revert
everything (`git checkout` plus `git clean`), which restored the previous
behaviour but also deleted the working notes that had been written alongside it.

## What produced real evidence

These techniques were worth the time and should be reached for first:

- **Disposable fixture probes.** `rex new <label> --shell=none -- python3 probe.py`
  where the probe sets raw mode and prints `repr(os.read(0, 64))`. Combined with
  `rex send-key --action press|repeat|release` and `rex api call block.event`,
  this shows the exact bytes a block receives for a given key event.
- **Schema as truth.** `rex api describe block.event` and
  `rex api describe client.key` documented the `action` enum, the physical key
  names and the "payload shape must match the type" rule — cheaper and more
  reliable than reverse engineering.
- **Server-side rendering as an oracle.** `rex capture -s <session> -b <block>`
  shows what the program's terminal actually holds, independent of what the phone
  screen shows. Counting characters before and after one press answers "did this
  reach the program" without trusting the client.
- **Deterministic UI driving.** `adb shell uiautomator dump` gives exact bounds for
  Compose nodes (`Escape`, `Backspace`, `Show extra keys`), so taps and long
  presses are reproducible instead of guessed from screenshots.
- **Boundary instrumentation.** `android_log` in the native actor plus a temporary
  Kotlin `Log.d` showed which side owned the drop: the Kotlin side proved the
  command was dispatched, and the native side proved whether it entered
  `send_input`, matched its target, and completed its acknowledgement.
- **`adb logcat -d -s <tag>`** to read those traces back after a manual tap.

## What misled

- **Logging the wrong path.** The first native traces were placed in the
  `execute_command` arm for `input`, but the actor routes input through
  `send_input` and the local queue. Empty logs looked like "the command never
  arrives" when it had arrived and been dropped later.
- **Reading absence of output as absence of input.** Escape at a shell prompt
  produces no visible change, so a delivered key looked identical to a dropped
  key. Always verify with a keystroke that must change the screen.
- **Driving the developer's live session.** Typing, backspace tests, session
  switches and killed fixtures all landed in the same session the developer was
  using, so observations mixed with their own actions.
- **Assuming the IME reports text.** `adb shell input text` and soft-keyboard
  presses went into the gboard composing buffer rather than the program, which
  made "typing does nothing" look like an app bug.
- **Treating a passing screenshot as proof.** A rendered terminal proves the data
  stream only; it says nothing about the input path.

## Rules for next time

1. **One behaviour class per patch.** Wire format, local timing, IME handling and
   queue binding are separate patches, applied in order from least to most
   invasive, each verified before the next is written.
2. **Never mix a feature with diagnostics in one tree.** Diagnostics are a
   separate patch that is removed (or kept behind a flag) before shipping.
3. **Keep each patch revertable on its own.** A branch or commit per patch, so a
   bad result costs one patch, not the whole attempt.
4. **Verify in a fixed order:** native unit tests, JVM tests, `:app:compileDebugKotlin`,
   `:app:assembleRelease`, install, then one device check that must change the
   screen. Do not proceed on a failed or ambiguous check.
5. **Use only disposable fixtures for device input tests**, create and destroy
   them in the same step, and never point the app at a fixture and then kill it.
6. **Record the environment of every device observation**: build id, session id,
   block id, who owned the pane, whether the pane was in viewing mode.
7. **Locate the boundary before changing code.** Determine whether the client
   dispatched, the native actor accepted, the server received, or the program
   ignored — then edit only that layer.
8. **Distinguish forward delete from backspace** in any report and in any test:
   `Del` deletes to the right of the cursor and is correctly inert at the end of a
   line, so it is a poor indicator of whether input works.
9. **A viewing-mode pane disables input by design.** If the app shows
   "接管控制" / "Take control", every key is meant to do nothing; confirm the pane
   is controlled before diagnosing anything else.

## Facts established during the attempt

Independent of the reverted code, these were measured against an owned local Rex
server and remain useful for any future repeat/keyboard work:

- `libghostty-vt` never generates key repeats; repeat is a host responsibility.
  Legacy encoding collapses `press` and `repeat` into the same bytes, while the
  kitty protocol's `report-event-types` flag (`report_events`, value 2) appends an
  event type: `press` omits it, `repeat` is `:2`, `release` is `:3`.
- Bare enter/tab/backspace keep their legacy bytes unless the program also pushed
  `report_all`, so a held backspace stays `0x7f` for press and repeat alike.
- Rex honours `action: press|repeat|release` on structured key events and encodes
  them for the program's current modes: backspace press `\x1b[127u`, repeat
  `\x1b[127;1:2u`, release `\x1b[127;1:3u`; with DECCKM set, arrows become
  `\x1bOD`/`\x1bOA`.
- A `block.event` whose key is `unidentified` is written verbatim and its `action`
  is ignored, so repeats cannot be expressed through the pre-encoded byte path.
- Gboard holds typed characters in its composing buffer; while a composition is
  pending, deletes belong to that buffer and never reach the program.
- The native input queue binds commands to an activation. Comparing the
  navigation id there is unsafe because the actor advances it while input is
  queued; a stale value silently dropped live typing until the two agreed again.

## References

- `rexling-android/TESTING.md` — per-feature device expectations.
- `rexling-android/VALIDATION.md` — dated validation log; add device observations
  here with the environment recorded.
- `rexling-android/PROTOCOL.md` — wire behaviour for `block.event`, input batching
  and ownership.
## Aftermath (2026-11-15)

Re-applying the smallest slice worked on the first device check: `TerminalView`
plus `HoldRepeat`, one behaviour class, one file plus one helper and its JVM test.
The four classes from the reverted attempt are still unshipped and must each get
their own patch and their own device evidence:

1. keyboard delete hold — **shipped** (`HoldRepeat`, `TerminalView`), confirmed by
   holding the soft keyboard's backspace,
2. structured key events for the eight repeatable dock keys,
3. IME composition editing (`trimComposing`/`flushComposing`, code-point deletion),
4. input-queue binding and a visible notice for undelivered presses.

Keep the ordering: nothing here justifies writing the next patch before the
previous one has a device observation that changed the program's screen.
