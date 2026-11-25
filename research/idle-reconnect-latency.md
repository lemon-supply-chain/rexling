# Why an idle Rexling session reconnects slowly

## Symptom

After the phone or the app has been idle, Rexling keeps showing a live session
until the first interaction, then takes seconds to come back. A quick return
from the background always costs a fresh handshake, and a real network handoff
while the terminal is idle is not noticed at all until the user types.

## Lifecycle map

| State | What happens |
| --- | --- |
| Foreground, terminal idle | Control and data WebSockets stay open. The only traffic is a WebSocket Ping every 120 s. |
| Background / screen off | `MainActivity.onStop` → `RexModel.pause` → `RexConnection.pause` → engine `pause`: release session panes, then drop control and data. `onStart` → `resume` starts a new handshake. |
| Network handoff, foreground | A ConnectivityManager callback reaches the engine as a `network` command. The engine deliberately keeps the existing sockets. |

## Root causes

### R1. Route callbacks intentionally do not force a reconnect

`c65cd06 Keep healthy terminal connections through network handoffs` removed the
`force` branch after a validation run showed Android reporting `online=false` or
`force=true` while the sockets were still usable
(`rexling-android/validation/network-handoff-2026-11-14.json`). The current
handler (`rexling-core/jni/src/engine.rs:2117-2128`) is:

```rust
if self.paused || self.endpoint.is_none() { return Ok(()); }
if network && self.phase == "connected" { return Ok(()); }
```

`force` is still sent by `RexConnection.networkChanged` but is never read by the
engine. The validation file names the limit explicitly: *"Actual transport
failure still requires reconnecting."* When a real handoff (Wi-Fi → cellular,
VPN/Tailscale route change) kills the TCP path, the dead socket is kept until
some I/O fails.

### R2. The liveness probe cannot prove liveness

The heartbeat is a fire-and-forget WebSocket Ping every 120 s
(`engine.rs:836`, `control.rs:332`, `data.rs:127`). Nothing waits for a Pong,
`transport::receive` (`transport.rs:167`) has no read timeout, and no TCP
keepalive is configured. If the path died silently (NAT entry expired, radio
handoff), the next Ping write can still buffer successfully and the read stays
parked. The connection can look healthy for minutes; recovery starts only when
the user types or an RPC fails.

### R3. Resume and visibility do not probe

`resume` (`engine.rs:2962-2969`) only reconnects when the control connection is
already gone:

```rust
if self.endpoint.is_some() && self.control.is_none() { self.retry = ...; }
```

`visibility` (`engine.rs:877-885`) only marks the frame dirty. If the engine
still holds a half-dead socket — for example the process was frozen while the
network changed, or the app never paused — returning to the app does not check
the link.

### R4. Background always tears everything down

Every `onStop` releases panes and drops both channels
(`RexConnection.kt:1436`, `MainActivity.kt:160-171`). Returning therefore pays:
control handshake (SSH or HTTPS/Tailscale), `session.attach_all`,
`server.whoami`, session attach, data stream, pane claim. If the first attempt
fails because the radio or Tailscale daemon is still waking, `recover`
(`engine.rs:1183-1196`) backs off 1 → 2 → 4 → 8 → 15 s, and `resume` does not
reset that backoff.

### R5. Minor: 300 ms network debounce

`RexModel` waits 300 ms after a usable route before forwarding it
(`RexModel.kt:728-741`). This is small compared with the causes above.

## Latency budget

| Scenario | Time before recovery starts |
| --- | --- |
| Foreground idle, silent socket death | next input/RPC, or up to the 120 s heartbeat — and the heartbeat may not fail |
| Background, first attempt succeeds | full handshake + attach (typically 1–3 s, longer over SSH/Tailscale) |
| Background, first attempt fails | handshake timeout plus 1/2/4/8/15 s backoff |

## Fix options

- **A. Probe on resume/visibility.** When the app becomes visible (or the engine
  resumes), if `phase == "connected"`, issue a cheap RPC with a ~2 s deadline
  (or a Ping with a Pong deadline). On timeout, `recover` immediately and reset
  the backoff to 1. This covers both the frozen-process and the silent-death
  cases without disturbing healthy connections.
- **B. Give the heartbeat a deadline.** Remember the last received frame and
  treat a missing Pong after the next 120 s tick as a failure; or move to a
  ~30 s Ping/Pong while visible. This bounds R1/R2 to the probe interval.
- **C. Grace period on background.** Keep the sockets for 20–60 s after
  `onStop`; only release panes and drop channels if the app stays away. Quick
  app switches and screen unlocks then skip the handshake entirely.
- **D. Re-enable `force` for tracker reroutes only.** The tracker's `reroute` is
  a network-id/transport change, which is a real handoff, but `c65cd06` observed
  callbacks firing on healthy sockets, so this needs a new validation run before
  it can be trusted.

Recommended order: A and B first (small, testable), then C as a follow-up.

## Verification plan

- Native: a test that `resume` with a connected-but-silent control triggers a
  probe, enters `reconnecting` on timeout and resets `backoff`; a heartbeat test
  that a missing Pong fails within the deadline.
- Android: extend `NetworkTest` with a handoff while idle and assert the phase
  returns to `connected` without user input.
- Device: screen off for 2 minutes, screen on, measure time to a usable prompt
  with and without the probe.

## Evidence

- `rexling-core/jni/src/engine.rs:836` — 120 s heartbeat.
- `rexling-core/jni/src/engine.rs:1032-1038` — reconnect gate (`retry`, `network_online`, `paused`).
- `rexling-core/jni/src/engine.rs:1183-1196` — `recover` and exponential backoff.
- `rexling-core/jni/src/engine.rs:2117-2132` — network handler keeps connected sockets; `force` ignored.
- `rexling-core/jni/src/engine.rs:2962-2969` — `resume` only reconnects without a control channel.
- `rexling-core/src/transport.rs:167-181` — receive loop, no timeout or keepalive.
- `rexling-core/src/control.rs:332` — Ping without a Pong deadline.
- `rexling-android/.../RexConnection.kt:1436-1465` — pause/resume command path.
- `rexling-android/.../MainActivity.kt:160-171` — `onStop` pauses, `onStart` resumes.
- `rexling-android/.../RexModel.kt:728-741` — 300 ms network debounce.
- `rexling-android/validation/network-handoff-2026-11-14.json` — why route callbacks stopped forcing reconnects.
