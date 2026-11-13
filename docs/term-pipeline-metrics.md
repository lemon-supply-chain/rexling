# Terminal wire pipeline metrics

Baseline for the native terminal replica and the `RXT4` update packet, recorded
before changing how a frame discovers its dirty rows. The numbers are a
regression baseline, not a benchmark contest: they answer "where does one frame
spend its time".

Reproduce everything below with:

```sh
cd rexling-core
cargo test -p rexling-jni --release pipeline_metrics -- --ignored --nocapture
```

The harness is `rexling-core/jni/src/metrics_tests.rs`. It needs no network and
no device; it feeds a real Kitty image transmission (1408×1920 RGBA,
base64, chunked like `terminal-browser` does) into a `Replica` that started from
a Ghostty snapshot of an 88×64 terminal with 16×30 cells, then measures `frame()`
and `updates::encode()`.

## Host baseline (macOS arm64, release, best of 5)

| metric | value | meaning |
| --- | --- | --- |
| `ingest.repaint_mb` | 14.45 | one full-screen image repaint, base64 |
| `ingest.mb_per_s` | 3838 | bytes the replica parses per second |
| `frame.idle.cells` | 5632 | cells handed out for a frame with no change |
| `frame.idle.ms` | 0.247 | cost of `frame()` when nothing changed |
| `frame.one_row.cells` | 5632 | cells handed out after typing one row |
| `frame.one_row.ms` | 0.249 | cost of `frame()` after typing one row |
| `frame.repaint.cells` | 5632 | cells handed out with a new full-screen image |
| `frame.repaint.ms` | 4.716 | ingest + `frame()` for an image repaint |
| `frame.repaint.placements` | 1 | image placements reported |
| `frame.repaint.images` | 0 | pixels re-sent for an unchanged generation |
| `wire.full.bytes` | 107556 | first packet, text only |
| `wire.full.flag` | 3 | full frame |
| `wire.one_row.bytes` | 1826 | one changed row on the wire |
| `wire.one_row.flag` | 2 | row delta |
| `wire.idle.bytes` | 17 | packet when nothing changed |
| `wire.idle.flag` | 0 | frame unchanged |
| `wire.full_image.bytes` | 10920997 | first packet that carries the pixels |
| `wire.encode_one_row.ms` | 0.506 | `encode()` re-diffing the frame against the last packet |
| `budget.text_fps` | 249 | frames/s this host can ingest and encode for typed text |
| `budget.repaint_fps` | 212 | frames/s this host can ingest and encode for repaints |

## Observed device baseline (Android 15)

Measured with `adb logcat` while the app displayed a `terminal-browser` block
over Tailscale; the image is 1008×1530 (4.9 MB) after the phone claimed the pane.

| observation | value |
| --- | --- |
| frames decoded and cached | 3–8 per second |
| effective ingest | ≈ 25–40 MB/s |
| stream drops after the fix below | 1 at attach, 0 in the following 3 minutes |
| process memory | native heap 45 MB, Dalvik 55 MB |

## Improvement measured against this baseline

The baseline above sent every frame through a full grid walk and then asked the
wire encoder to re-diff all 5632 cells. Ghostty already reports which rows it
touched (`ghostty_render_state_row_iterator_next_dirty`), so a frame can carry
only those rows. Both paths are now measured side by side in the same harness.

| metric | before | after | change |
| --- | --- | --- | --- |
| `frame.idle.cells` | 5632 | 0 | no rows copied for an unchanged frame |
| `frame.idle.ms` | 0.251 | 0.000 | — |
| `frame.one_row.cells` | 5632 | 88 | one row |
| `frame.one_row.ms` | 0.248 | 0.005 | 49× |
| `wire.encode_one_row.ms` | 0.510 | 0.295 | 1.7× |
| `wire.one_row.bytes` | 1826 | 1826 | unchanged: the wire was already a delta |
| `wire.idle.bytes` | 17 | 17 | unchanged |

The wire did not change at all; the saving is in the renderer's own work. Frames
stay full — the old numbers — for `frame.idle.cells`, etc. whenever a reader
actually needs every row:

- a pane running a shell or pi with predictive echo on (`Predictor::requires_full_frame`),
- while a prediction is pending, and
- after a reader error, when nothing holds the previous screen (`force_full_frame`).

So a full-screen graphics pane such as `terminal-browser`, `nvim` or a TUI gets
the dirty path; a shell prompt keeps the predictable path.

## What the numbers say

1. **The frame walk is cheap, and the post-hoc diff is not.** `frame()` costs
   0.25 ms, but `encode()` costs 0.51 ms — twice as much — because it compares
   all 5632 cells (each with its own `String`) to discover that one row changed.
   Both walk the whole grid even when a single row moved.
2. **The wall is ingest, not rendering.** A repaint is 14.45 MB; this host parses
   it at ~3.8 GB/s, a phone at ~25–40 MB/s. `terminal-browser` produces ~780 MB/s
   of full-screen image frames, so the phone is roughly 20× short. No renderer
   change closes that gap; it can only be tolerated (resume instead of snapshot
   re-download, coalesce to the newest complete frame).
3. **The wire already diffs.** One changed row is 1826 bytes; an unchanged frame
   is 17 bytes; only the first packet carrying pixels is 10.9 MB. So bandwidth
   work belongs in the image-generation cache, which is where it went.
4. **The next lever is image bytes, not rows.** A repaint copies the pixels five
   times today: Ghostty storage → `Frame.images.rgba` → wire packet → JNI
   `byte[]` → Kotlin `ByteArray` → `Bitmap`. The last two are removable by
   handing the decoder a direct `ByteBuffer` over the packet. That is ~5 MB per
   frame per repaint (10 MB at 1408×1920), around 40–80 MB/s at the observed
   device cadence, and it is the next measurable step.

## Scroll optimization compatibility

Reviewed on 2026-11-13 against local `35823fa2fcc116035a1215a027da22f37139888d`:
[upstream scrolling change 601c385](https://github.com/michaelcosj/rexling/commit/601c3852fe4ff4db6fcb201d059266c1fe37506c).
The recommendation is to adapt the useful parts, not cherry-pick it unchanged.
A disposable three-way merge using its parent `bb9cb5c` reports conflicts in
nine of the ten changed files, including a modify/delete conflict for the
upstream `src/graphics.rs`, which this project does not use.

- Row-shift deltas and moving row recordings with their content address
  scrolling, not the same workload as the existing dirty-row optimization.
  Preserve absent-versus-empty rows and full-frame recovery when combining
  them; the upstream encoder assumes a complete grid.
- The packet tag alone does not establish compatibility. Both projects use
  `RXT4`, but their image payloads, placements and preview encoding differ.
  Extend this project's encoder and decoder together rather than replacing
  either with the upstream implementation. The tag is a local JNI format, not
  a Rex server protocol upgrade.

The exact upstream head has passing Rust/Kotlin tests and an APK build.
Its instrumented tests were compiled, not run on a device; those checks do
not validate a port into this project's dirty-row/image-cache pipeline.
Both projects already pin the same Ghostty revision. A staged port should begin
with the independent local-scroll queue bypass, then row-shift/cache reuse.

### Selective port

The port is implemented without importing the upstream graphics module:

- Local `scroll` commands bypass pending input/paste acknowledgements; stale
  block checks remain in the actor, wheel events remain on the ordered input
  path, and the existing local 8 ms render pacing is unchanged.
- `RXT5` adds one signed little-endian 16-bit row shift immediately after
  `input_flags`. The rest of the packet retains this project's image and
  JSON-preview layout. The decoder still supports `RXT1`–`RXT4`.
- The encoder enables shifts only when both frame bases contain every row,
  the offset change is smaller than the viewport, and at least half the
  overlapping rows match. Other frames keep the existing sparse dirty-row
  path. Missing bases still request a complete frame. Every uncovered row
  must be supplied before the decoder accepts a shifted packet.
- The view moves row recordings by the scroll offset and verifies drawing
  content before reuse. Physical cell/link row numbers are ignored only for
  this drawing comparison; decoder cell positions and link hit testing still
  use the new viewport row. Changed text, styles, colors or links invalidate
  the recording.

Regression coverage includes positive/negative shifts, large jumps, sparse
bases, malformed shifts, preserved hyperlinks and local image placements,
and an actor with an indefinitely pending paste acknowledgement. An Android
bitmap test compares reused rows (including link underlines) with a fresh
view and asserts one new recording for a one-line scroll. Host Rust/Kotlin
tests and release APK compilation pass; the bitmap/device test is compiled
but has not been run on an Android device.
