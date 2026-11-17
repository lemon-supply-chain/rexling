# Android port forwarding for Rexling

Status: implemented and verified; see "Implementation status".
Date: 2026-11-17
Tested server: Rex 0.1.0-preview.20, API revision 16, macOS arm64.

## Question

Can Rexling forward a port that a program listens on in a Rex terminal to the
Android device, so that tapping a terminal link opens the page through that
forward? The forward must close when the terminal's `ports_changed` event says
the port is gone.

## Conclusion

Yes. Android cannot use `adb forward` from an app, but it can run an ordinary
loopback TCP listener in its own process and bridge each accepted connection
through a Rex tunnel block. This needs no root and no VPN service.
Chrome and Rexling share the device's network namespace, so Chrome can reach a
listener bound to `127.0.0.1` in the Rexling process while the forward service
keeps that process alive.

The phone also cannot call Rex.app's local `rex forward` registry: that CLI
talks to a server and keeps its forward state on the host. The equivalent
capability has to be built from the server's public control API and the
`com.superlogical.tunnel` block.

The server already exposes the required pieces:

- `com.superlogical.terminal.ports` (API revision 15) lists listening TCP ports
  for a terminal block.
- `ports_changed` (API revision 16) replaces that list when the ports change.
- `com.superlogical.tunnel.dial` (API revision 14) is the `ssh -L` equivalent:
  the block connects to a fixed `host:port` on the server for each data stream
  its owner opens.

Rexling currently cannot call any of these. Its live control connection is
pinned to API revision 8, and a `ports` call on that connection fails with
`revision_too_low`.

The recommended implementation is a separate API-revision-16 worker in
`rexling-core`. It owns its own control connection, creates one detached tunnel
block per forward, binds a loopback listener, bridges TCP to
`rex.data.bytes.v1` data streams, and closes the forward only on a complete
`ports_changed` event that no longer contains the target port.

`rex forward open` is a useful reference but does not satisfy the last
requirement. It opens the forwarded URL, but it stayed active more than 30
seconds after the source port disappeared. The auto-close behavior must be
implemented in Rexling.

## Implementation status

Landed on 2026-11-17:

- `rexling-core/src/forward.rs`: one state-machine worker per forward, with
  the phases and port authority rules in this document, the loopback listener,
  one detached `com.superlogical.tunnel.dial` block, the per-connection
  `rex.data.bytes.v1` bridge, half-close handling, revision-15 polling,
  revision-16 `ports_changed`, `blob_read` completion, and tunnel/lifecycle
  cleanup.
- `rexling-core/src/control.rs`: `connect_forward()` (revision 16, minimum 0),
  a configurable minimum for the welcome check, and `call_with_seq()` returning
  the reply's event sequence.
- `rexling-core/jni/src/forwards.rs` and the JNI exports:
  `forwardCreate`, `forwardPoll`, `forwardSelect`, `forwardClose`,
  `forwardDestroy`, `forwardCloseAll`.
- Android: `PortForwardService`, `PortForwardRegistry`/`PortForwardLinks`,
  link interception in `RexConnection.bindTerminalView`, the port-selection
  dialog, and a `Port forwards` sheet with per-forward close.
- `rexling-core/tests/forward_live.rs`: three `#[ignore]` end-to-end tests
  against an installed server. They create disposable sessions, forward an
  HTTP server, assert that the page loads through the local port, and assert
  closure on `ports_changed`, on user cancellation, and on session close.

Verified on the installed Rex `0.1.0-preview.20` server:

- `cargo test --lib forward::` passes.
- `cargo test --test forward_live -- --ignored` passes all three tests,
  including an HTTP 200 through the forwarded port and a `port_gone` close
  after the source server exits.
- `./gradlew :app:testDebugUnitTest` passes, including the new
  `PortForwardTest`, and `:app:assembleDebug` builds with the rebuilt
  `librex_android.so`.

Two protocol details only surfaced during the implementation:

- A session event is nested one level deeper than the CLI's `rex events`
  output: the control message is
  `{"type":"event","payload":{"type":"session_event","payload":{"session_id":...,"event":{...}}}}`.
  Reading `payload["event"]` instead of `payload["payload"]["event"]` silently
  ignores every port event.
- `ports_changed` is emitted by the server's background scan. That scan runs
  often while the terminal has input or output and only every five minutes
  while it is idle. A port can therefore disappear from `ports` before any
  event arrives. This is server behavior, not a client bug, and it explains
  why the reference forward stayed active. The worker still closes
  immediately on the event; a terminal that prints during shutdown makes the
  event arrive promptly.

The implementation adds one detail not in the original design: each tunnel
label carries a per-install tag (`rexling fwd <tag> <host>:<port>`). On connect
the worker lists the session's detached blocks and closes unowned tunnel
blocks with the same tag, so a killed app cannot leave orphans behind while
other devices' forwards stay untouched.

## Verified protocol behavior

The local Rex server supports API revisions 0 through 16:

```text
$ rex version
rex 0.1.0-preview.20
api: revision 16 (supports 0 to 16)
```

Calling `ports` on a terminal block works through the Rex CLI:

```text
$ rex block call -b block:... ports
{"status":"ok","ports":[{"port":55884,"address":"127.0.0.1",
"protocol":"tcp","first_seen_at":"2026-11-17T06:09:54Z","pid":34925}],
"processes":[...]}
```

A local forward created with the reference CLI was reachable from the host:

```text
$ rex forward open --no-open -s session:... http://127.0.0.1:7878/
http://localhost:55499/

$ curl http://localhost:55499/
HTTP/1.1 200 OK
```

After the source HTTP server was stopped, `ports` returned an empty list but
the forward remained:

```text
$ rex forward ls --json
{
  "forwards": [{
    "state": "active",
    "listen": "127.0.0.1:55896",
    "connect": "127.0.0.1:55884"
  }]
}
```

This confirms that auto-close is not provided by the reference command.

## Protocol rules that matter

### `ports` reply

The reply is `{status, ports, processes}`, sometimes with `truncated` and `id`.

- `status: ok` means every process in the terminal was inspected. An empty
  list then means nothing is listening.
- `status: partial` means some processes could not be inspected. A missing
  port may still be open.
- `status: unsupported` means the server cannot inspect the terminal, or its
  program has exited.
- A missing port is authoritative only when `status` is `ok` and `truncated`
  is not set.
- A truncated reply has an `id`; `blob_read` returns the complete list.
- Entries are one per protocol/address/port. Prefer `127.0.0.1`, then `::1`,
  then the reported address. Normalize `0.0.0.0` and `::` to a loopback target.

### `ports_changed`

The event arrives inside a `block_event`:

```json
{
  "type": "event",
  "payload": {
    "type": "session_event",
    "payload": {
      "session_id": "session:...",
      "event": {
        "type": "block_event",
        "payload": {
          "block_id": "block:...",
          "creator_name": "com.superlogical.terminal",
          "name": "ports_changed",
          "payload": {"status": "ok", "ports": [], "processes": []}
        }
      }
    }
  }
}
```

Every event carries the complete list. Replace the previous list; do not treat
it as a diff. The server sends no initial event when a client attaches late, so
the worker must call `ports` once after `session.attach`.

Only a complete event can prove that a port closed:

```text
status == "ok" && !truncated
```

Killing the forward on `partial`, `truncated`, or `unsupported` replies would
drop working forwards. `unsupported` with an empty list is what the server
sends after the terminal program exits; in that case, close on the terminal
block's `child_exited` or `block_closed` event instead of treating
`unsupported` alone as proof.

### Tunnel block

Create a detached block in the source terminal's session:

```json
{
  "session_id": "session:...",
  "flavor": "com.superlogical.tunnel.dial",
  "options": {
    "host": "127.0.0.1",
    "port": 5173,
    "client_address": "127.0.0.1:49152"
  },
  "label": "rexling fwd 127.0.0.1:5173"
}
```

The calling client becomes the block's owner. Only that owner may open its data
streams. Each stream uses:

```text
subprotocol: rex.data.bytes.v1
data_ref_id: conn:<unique-name>
```

Open the stream with `data.connect`, send the one-time ticket in the HELLO
frame, then exchange raw binary frames. An empty binary frame is a TCP
half-close. A stream ending with an error resets the TCP connection.

The existing upload path already proves that Rexling can open a
`rex.data.bytes.v1` stream: see `rexling-core/src/upload.rs` and
`rexling-core/jni/src/uploads.rs`. The same HELLO/WELCOME parsing in
`rexling-core/src/data.rs` can be factored out and reused.

Close the tunnel block explicitly with `block.close` when the forward ends.
Otherwise a process death leaves an orphaned tunnel block that may keep
listening on the server.

## Current Rexling gaps (before the implementation)

Every gap listed here is now closed; this section is kept as the starting
point for the change and for reviewers checking that each one has a
counterpart in the implementation.

- `rexling-core/src/control.rs:118` connects the live terminal control at API
  revision 8. `ports` requires revision 15, and the tested call returned
  `revision_too_low`.
- `rexling-core/src/control.rs:121` already uses a separate revision-11
  connection for uploads. A separate revision-16 connection for forwarding is
  the same pattern and avoids changing the terminal contract.
- `Control::connect_revision` is private and fixes `api_min_revision` to 7.
  Add a public `connect_forward()` (revision 16, minimum 0) or expose a
  revision-aware constructor for the forward worker.
- `Control::call` returns only the response payload. Add a `call_with_seq`
  variant so the worker can order a `ports` reply against queued events.
- `rexling-android/app/src/main/java/dev/shulhi/rex/TerminalView.kt:468` opens
  links directly with `Intent.ACTION_VIEW`. There is no forward step.
- `rexling-android/app/src/main/java/dev/shulhi/rex/TerminalView.kt:47` allows
  `onOpenLink` to be replaced, and
  `RexConnection.bindTerminalView` already knows the selected block, so link
  interception has a clean insertion point.
- `rexling-android/app/src/main/java/dev/shulhi/rex/Models.kt:66` has no port
  or forward state. A small forward status model is needed.
- Rexling has no `ServerSocket`, TCP listener, tunnel block, or port-event code.

## Recommended architecture

### Native forward worker

Add `rexling-core/src/forward.rs` plus JNI entry points analogous to uploads:

```text
forwardCreate(requestJson) -> handle
forwardPoll(handle)        -> stateJson
forwardClose(handle)
forwardCloseAll()
```

Each request includes:

```text
endpoint, session, sourceBlock?, targetHost?, targetPort?, selectionMode
```

`targetPort` is present for an explicit URL or `host:port`, and absent for a
block-target request that may need automatic selection. `selectionMode=auto`
may enter `AwaitingSelection`; `selectionMode=explicit` must not.

The worker runs on a dedicated Tokio current-thread runtime:

1. Open a new control connection with `api_revision = 16` and
   `api_min_revision = 0`.
2. Inspect the welcome. If the negotiated revision is below 15, report
   `unsupported`.
3. Attach to the source session.
4. Call `com.superlogical.terminal.ports` once. Choose a target address for the
   requested port.
5. Bind `127.0.0.1:0`; record the chosen local port.
6. Create the detached `com.superlogical.tunnel.dial` block.
7. Publish `active` with `localPort`, `localUrl`, target and tunnel block ID.
8. Accept loopback connections. For each connection:
   - allocate `conn:<uuid>`;
   - call `data.connect` on the worker's control connection;
   - open `/api/data` with `rex.data.bytes.v1`;
   - send HELLO and await WELCOME;
   - bridge TCP bytes and WebSocket binary frames in both directions;
   - translate TCP half-close to an empty binary frame and vice versa.
9. While the control connection is open, consume `ports_changed` and lifecycle
   events.
10. On close, stop accepting, reset active streams, call `block.close`, and
    close the control connection.

The worker must not use the terminal actor's control connection. Sharing it
would either force revision 16 onto the live terminal path or serialize
forwarding behind terminal traffic.

### Forward state machine

The forward is one state-machine task per click identity: the pending
auto-selection identity
`(endpoint, session, sourceBlock, select)`, then the resolved key
`(endpoint, session, sourceBlock, targetHost, targetPort)`. It owns the control
connection, the listener, the tunnel block and the connection count. UI calls
send commands to it; the control connection and stream tasks send events to it.
No other task mutates its state.

#### State variables

```text
ForwardPhase
  New
  Connecting
  Resolving
  AwaitingSelection
  Binding
  CreatingTunnel
  Active
  Stopping
  Closed
  Failed

PortAuthority
  Unknown
  Complete
  Partial
  Truncated
  Unsupported

PortMode
  Disabled
  PollOnly
  EventDriven

ControlPhase
  Disconnected
  Connecting
  Attached
  Reconnecting
  Closed

StreamPhase
  Ticket
  Handshake
  Bridging
  LocalHalfClosed
  RemoteHalfClosed
  Closed
  Reset

CloseReason
  User
  PortGone
  PortAddressChanged
  SourceBlockClosed
  SourceProgramExited
  SessionClosed
  TunnelClosed
  ControlLost
  TaskRemoved
  Error

TerminalOutcome
  Closed
  Failed

FailureCode
  UnsupportedServer
  ConnectFailed
  NotListening
  NeedsPort
  BindFailed
  TunnelFailed
  ControlFailed
  Internal
```

`PortAuthority` is not a substitute for `ForwardPhase`. A forward can be
`Active` while its port knowledge is `Partial`, `Truncated`, or `Unsupported`.
Only `Complete` authorizes a close caused by a missing port.

`PortMode` controls how port state is observed:

- `EventDriven`: negotiated API revision 16 or later and a source block exists.
  Call `ports` once, then replace the snapshot from each matching
  `ports_changed` event.
- `PollOnly`: negotiated API revision 15 and a source block exists. Call
  `ports` once, then call it again every 15 seconds because revision 15 has no
  `ports_changed` event.
- `Disabled`: no source block was supplied. There is no authoritative port
  stream, `PortAuthority` stays `Unknown`, and a missing port never closes the
  forward.

V1 deliberately uses revision 15 as its compatibility floor even though the
tunnel block exists from revision 14. Revision 14 has no `ports` method, so it
cannot provide the click-time validation or the auto-close guarantees this
design needs.

`Stopping` carries a `TerminalOutcome` and an optional `FailureCode`. Every
non-terminal phase exits through `Stopping`, even when it has not allocated a
listener or tunnel yet. `Closed` and `Failed` are the only terminal phases, and
they are entered only after cleanup finishes.

Universal transitions apply in every non-terminal phase:

| Event | Guard | Next | Action |
| --- | --- | --- | --- |
| `close(user)` or `cancel` | - | Stopping | `User`; outcome `Closed` |
| task removed | - | Stopping | `TaskRemoved`; outcome `Closed` |
| control lost after attach | v1 close-on-loss policy | Stopping | `ControlLost`; outcome `Failed` |
| control event seq gap | - | Stopping | `ControlFailed`; outcome `Failed` |
| source `block_closed` | source block matches | Stopping | `SourceBlockClosed`; outcome `Closed` |
| source `child_exited` | source block matches | Stopping | `SourceProgramExited`; outcome `Closed` |
| `session_destroyed` | source session matches | Stopping | `SessionClosed`; outcome `Closed` |
| tunnel `block_closed` | tunnel block exists and matches | Stopping | `TunnelClosed`; outcome `Failed` |

Late events after entering `Stopping` are recorded for diagnostics and do not
change the result.

#### Main transition table

| Current | Event | Guard | Next | Action |
| --- | --- | --- | --- | --- |
| New | `start` | key has no live forward | Connecting | Open revision-16 control |
| Connecting | welcome | revision >= 16 and source block | Resolving | `EventDriven`; `session.attach` |
| Connecting | welcome | revision == 15 and source block | Resolving | `PollOnly`; `session.attach` |
| Connecting | welcome | revision >= 15 and no source block | Resolving | `Disabled`; `session.attach` |
| Connecting | welcome | revision < 15 | Stopping | `UnsupportedServer`; outcome `Failed`; close control |
| Connecting | error or timeout | - | Stopping | `ConnectFailed`; outcome `Failed`; close control |
| Resolving | `ports` reply, explicit target | complete and contains target | Binding | Record target address |
| Resolving | `ports` reply, explicit target | complete and missing target | Stopping | `NotListening`; outcome `Failed` |
| Resolving | `ports` reply, explicit target | partial/truncated/unsupported | Binding | Start optimistically; keep authority |
| Resolving | `ports` reply, block target | complete, exactly one port | Binding | Select that port |
| Resolving | `ports` reply, block target | complete, multiple ports | AwaitingSelection | Publish choices to UI |
| Resolving | `ports` reply, block target | complete, zero ports | Stopping | `NotListening`; outcome `Failed` |
| Resolving | `ports` reply, block target | partial/truncated/unsupported | Stopping | `NeedsPort`; outcome `Failed`; ask for an explicit port |
| Resolving | no port validation | `PortMode == Disabled` | Binding | Continue with explicit target |
| Resolving | `ports_changed` | matching, queued | Resolving | Apply after initial reply; re-evaluate |
| AwaitingSelection | `selectPort(port)` | port was in the reply | Binding | Record selected address |
| AwaitingSelection | `cancel` | - | Stopping | User |
| AwaitingSelection | `ports_changed` or poll reply | complete, choices changed | AwaitingSelection | Replace choices |
| AwaitingSelection | `ports_changed` or poll reply | complete, zero choices | Stopping | `NotListening`; outcome `Failed` |
| AwaitingSelection | `ports_changed` or poll reply | partial/truncated/unsupported | AwaitingSelection | Keep authority and choices |
| Binding | listener bound | - | CreatingTunnel | Record local port |
| Binding | bind error | - | Stopping | `BindFailed`; outcome `Failed`; close control |
| Binding | `ports_changed` | complete, target missing | Stopping | `PortGone`; outcome `Closed` |
| Binding | `ports_changed` | complete, only another address remains | Stopping | `PortAddressChanged`; outcome `Closed` |
| Binding | poll reply | complete, target missing | Stopping | `PortGone`; outcome `Closed` |
| Binding | poll reply | complete, only another address remains | Stopping | `PortAddressChanged`; outcome `Closed` |
| CreatingTunnel | `session.new_block` reply | block ID present | Active | Publish local URL; start accept loop |
| CreatingTunnel | error | - | Stopping | `TunnelFailed`; outcome `Failed`; close listener |
| CreatingTunnel | `ports_changed` | complete, target missing | Stopping | `PortGone`; outcome `Closed` |
| CreatingTunnel | `ports_changed` | complete, only another address remains | Stopping | `PortAddressChanged`; outcome `Closed` |
| CreatingTunnel | poll reply | complete, target missing | Stopping | `PortGone`; outcome `Closed` |
| CreatingTunnel | poll reply | complete, only another address remains | Stopping | `PortAddressChanged`; outcome `Closed` |
| Active | `ports_changed` | complete, target missing | Stopping | `PortGone`; outcome `Closed` |
| Active | `ports_changed` | complete, only another address remains | Stopping | `PortAddressChanged`; outcome `Closed` |
| Active | `ports_changed` | complete, target present | Active | Update process labels only |
| Active | `ports_changed` | partial/truncated/unsupported | Active | Keep authority; do not close |
| Active | poll reply | complete, target missing | Stopping | `PortGone`; outcome `Closed` |
| Active | poll reply | complete, only another address remains | Stopping | `PortAddressChanged`; outcome `Closed` |
| Active | poll reply | complete, target present | Active | Update process labels only |
| Active | poll reply | partial/truncated/unsupported | Active | Keep authority; do not close |
| Stopping | cleanup complete | outcome `Closed` | Closed | Publish reason |
| Stopping | cleanup complete | outcome `Failed` | Failed | Publish failure and error |
| Stopping | cleanup timeout or panic | - | Failed | Record `Internal` |

The happy path is:

```text
New -> Connecting -> Resolving -> Binding -> CreatingTunnel -> Active
                         |                                      |
                         +-> AwaitingSelection -> Binding       |
                                                                |
                         ports_changed(complete, missing) -> Stopping -> Closed
```

`Stopping` is the only state allowed to release resources. It always performs
the same cleanup, regardless of reason:

1. mark the handle `stopping`, so duplicate close requests are no-ops;
2. stop the accept loop;
3. reset every active stream;
4. `block.close` the tunnel block when one exists; `not_found` means success;
5. close the control connection;
6. close the listener;
7. publish the terminal outcome.

Cleanup must be idempotent and time-bounded. `Failed` is terminal only after
that cleanup has completed. A failed forward must not leave a listener or
tunnel block behind.

#### Port observation rules

A port snapshot is the latest full object from the initial `ports` reply, a
`ports_changed` event, or a revision-15 poll. "Full object" does not mean the
authority is complete; `status` and `truncated` decide that separately.

```text
PortSnapshot {
  status: string
  truncated: bool
  ports: [{port, address, protocol, pid?}]
  processes: [...]
  observedAt: timestamp
  observedSeq: u64
  generation: u64
  source: Initial | Event | Poll
}
```

`generation` belongs to the control connection, not the server. Increment it
every time a new control connection is established. A snapshot or asynchronous
`blob_read` result from an older generation is discarded.

Authority mapping:

| Snapshot | PortAuthority | May close on missing target? |
| --- | --- | --- |
| `status == "ok"`, no `truncated` | Complete | Yes |
| `status == "partial"` | Partial | No |
| `truncated == true` | Truncated | No, unless `blob_read` replaces it |
| `status == "unsupported"` | Unsupported | No |
| unknown status | Partial | No |

Evaluate authority in this order: `unsupported`, then `truncated`, then
`status == "ok"`, otherwise `partial`.

Authority is recomputed from every new snapshot; it is never sticky. A later
complete event can authorize closure after an earlier partial snapshot, and a
later partial or truncated event withdraws that authority again.

`ports_changed` always replaces the previous snapshot. It is never applied as a
diff. An event for another block or session is ignored.

If a truncated snapshot has an `id`, `blob_read` may produce a complete
replacement. If `blob_read` fails, retain the truncated snapshot and keep the
forward.

`blob_read` is asynchronous. While it runs, keep the forward active and keep
the truncated authority. When a complete result arrives, replace the snapshot
only if its generation and source observation are still current, then evaluate
the transition table. A complete result that no longer contains the target
closes with `PortGone`; a stale or incomplete result changes nothing.

The `ports` request is a block method call:

```text
com.superlogical.terminal.ports
{"session_id": "...", "block_id": "...", "args": {}}
```

In `PollOnly` mode, run the first call during `Resolving`, then repeat it every
15 seconds while the phase is `Resolving`, `AwaitingSelection`, `Binding`,
`CreatingTunnel`, or `Active`. Stop the timer on entry to `Stopping`. A poll
whose reply overlaps a lifecycle event is still safe because the state machine
is serialized, but an older poll reply must not overwrite a newer event
snapshot. Compare `observedSeq` or a local monotonic observation number and
discard a reply that is older than the current snapshot.

#### Event ordering

Every response has a `seq`: the `seq` of the last event the server had queued
for the connection when it started the request, or 0 when none was queued.
Event `seq` values are contiguous and increase by one. The initial `ports`
response and `ports_changed` events therefore share one ordering space.

1. Call `ports` while continuing to receive events.
2. On the response, apply its payload as a snapshot with
   `observedSeq = response.seq`.
3. Drain events that arrived before the response in order.
4. Apply an event only when `event.seq > response.seq`; skip events at or below
   the response's `seq`, because the result already includes them.
5. For each matching `ports_changed` with a higher `seq`, replace the snapshot
   with `observedSeq = event.seq` and evaluate the transition table.

`Control::call` already queues events that arrive while a request is in
flight, so the worker must drain that queue after every call. It must not
assume the response is newer than every event already received.

The current `Control::call` returns only the response payload. Its
`last_seq()` is the newest event read from the wire, which may be newer than
the response's `seq`; it cannot be used to recover the response sequence after
events were queued. The forward worker needs a small control API change, such
as `call_with_seq()` returning `{payload, seq}`, before this ordering rule can
be implemented correctly.

`next_message` currently fails on an event sequence gap. For the forward
worker that is a control failure: enter `Stopping` with `ControlFailed` and do
not apply the out-of-order event.

After a control reconnect, reset `PortAuthority` to `Unknown`, call `ports`
again, reset the applied response sequence, and only then allow closure on a
missing port. The new connection's `seq` starts again at 1. Never close from a
snapshot that was gathered before the reconnect.

#### Startup and selection behavior

For an explicit URL or `host:port`, the initial `ports` call is a validation
step:

- complete snapshot containing the target: proceed;
- complete snapshot missing the target: fail with `NotListening`;
- incomplete snapshot: proceed optimistically, because the port may exist but
  the server could not inspect every process.

Matching is by port first. For a loopback alias or the Rex server host, choose
the preferred address among that port's entries. For an explicit IP literal,
prefer the exact address; normalize `0.0.0.0` and `::` to loopback. The address
chosen here becomes `targetAddress`, and later events close the forward with
`PortAddressChanged` if only a different address remains.

If the caller supplied no `sourceBlock`, skip port validation entirely and set
`PortAuthority = Unknown`. Native forwarding can still work for an explicit
`host:port`, but automatic close from port events is unavailable.
A request with neither `sourceBlock` nor `targetPort` is rejected before the
state machine starts, because there is no way to choose a port.

For a terminal-block target with no explicit port, the initial snapshot must
be complete to choose automatically:

- group entries by port number; one distinct port: use it and choose the
  preferred address (`127.0.0.1`, then `::1`, then the reported address);
- multiple distinct ports: enter `AwaitingSelection` and show the ports and
  process labels, matching `rex forward open`;
- zero ports: fail with `NotListening`;
- incomplete snapshot: fail with `NeedsPort`, telling the user to provide an
  explicit port.

Several entries for the same port number are address variants, not separate
choices. Address preference is applied only within the selected port. If the
selected address later disappears while the port remains on another address,
the forward closes with `PortAddressChanged`; v1 does not retarget an existing
tunnel block.

#### Port-change behavior while active

`ports_changed` is evaluated only when the event names the source session and
source block. It is then evaluated only against the selected address and port:

| Change | Result |
| --- | --- |
| target address and port still present | keep active; update labels |
| port present on another address only | stop with `PortAddressChanged`; tunnel target is fixed |
| target absent from a complete snapshot | stop with `PortGone` |
| target absent from a partial/truncated/unsupported snapshot | keep active |
| status changes but target remains | keep active |
| source block closes | stop with `SourceBlockClosed` |
| source terminal program exits | stop with `SourceProgramExited` |
| session closes | stop with `SessionClosed` |
| tunnel block closes unexpectedly | stop with `TunnelClosed` |

Do not poll `ports` continuously on revision 16. The event is the change
signal. For a revision-15 server, which has `ports` but no event, use the
bounded 15-second poll with the same complete-snapshot guard.

`PortGone` is an expected terminal result, not a transport failure: publish
`Closed` with reason `PortGone` after cleanup. The tunnel must be closed before
the listener disappears from the UI.

Port events may arrive while an RPC such as `session.new_block` is in flight.
They are queued by the control layer. When the RPC completes, process the
queued events before publishing `Active`. If a complete event already says the
target is gone, go directly to `Stopping` and close the tunnel that was just
created.

An event that reports `unsupported` after the terminal program exits is not
proof that the port closed. The source block's `child_exited` or `block_closed`
event is the authoritative lifecycle signal. Conversely, a complete event with
`status == "ok"` and no target is sufficient even if no lifecycle event has
arrived yet.

The active port-event path is:

```text
ports_changed(seq > lastApplied, session and block match)
  -> replace PortSnapshot
  -> authority := Unsupported | Truncated | Complete | Partial
  -> if authority != Complete: stay Active
  -> else if selected address and port present: stay Active
  -> else if same port only on another address:
       Stopping(PortAddressChanged) -> cleanup -> Closed
  -> else:
       Stopping(PortGone) -> cleanup -> Closed
```

Every stop caused by this path closes the tunnel block before the local
listener, so a race cannot leave the browser pointed at a live loopback port
whose tunnel is gone.

#### Per-connection state

Each accepted TCP connection has its own state, owned by the forward task's
connection table:

| Current | Event | Next | Action |
| --- | --- | --- | --- |
| Ticket | `data.connect` reply | Handshake | Open `/api/data` with `rex.data.bytes.v1` |
| Handshake | valid 32-byte WELCOME | Bridging | Start both copy directions |
| Handshake | REJECT, close, or timeout | Reset | Close TCP |
| Bridging | local TCP EOF | LocalHalfClosed | Send empty binary frame |
| Bridging | remote empty binary or normal close | RemoteHalfClosed | `shutdown(Write)` on TCP |
| LocalHalfClosed | remote close | Closed | Release slot |
| RemoteHalfClosed | local EOF | Closed | Release slot |
| Any | error or forward stopping | Reset | Close both sides |

The worker should cap concurrent streams, for example at 64, and reject new
accepts beyond the cap rather than growing memory without bound.

#### Timers and resource limits

- Control connect and each RPC: 10 seconds.
- Listener bind and tunnel-block creation: 10 seconds.
- `data.connect` and data-stream handshake: 10 seconds each.
- `blob_read` for a truncated port list: 10 seconds.
- Cleanup: 2 seconds for each close attempt; never block shutdown forever.
- Active streams per forward: 64.
- Revision-15 port poll: 15 seconds; revision 16 uses events.
- Optional idle close: not part of v1. If added, it must be a separate
  `Stopping` reason and must not be confused with a port event.

#### Reuse and idempotency

The forward registry key is:

```text
endpoint + session + sourceBlock + targetHost + targetPort
```

- A pending auto-selection request uses
  `endpoint + session + sourceBlock + select`, and a second click on the same
  unresolved target attaches to that state instead of creating another
  `AwaitingSelection` entry.
- After the user selects a port, index the same handle under the resolved key
  as well. Keep both indexes until the handle reaches a terminal phase.
- `Active` key: return the existing local URL immediately.
- `Connecting`, `Resolving`, `Binding`, or `CreatingTunnel`: attach the new
  click as a waiter on the existing transition.
- `Stopping`: wait for terminal state, then create a new forward.
- `Closed` or `Failed`: create a new forward.

This prevents duplicate tunnel blocks when a user taps the same link twice.

#### Reconnect policy

V1 uses `CloseOnControlLoss`: the listener and active streams close, and the
UI reports that the forward ended. This is deterministic and avoids leaving a
listener that cannot open data streams.

Full `rex forward new` parity is a later policy:

- keep the listener but stop accepting;
- reconnect with the stored reconnect token;
- reattach the session and reclaim the tunnel block;
- call `ports` again, resetting authority to `Unknown`;
- recreate the block if it was closed;
- return to `Active`.

If that policy is added, it must be a distinct field or state, not a silent
side effect of the port-event handler.

#### Invariants

- `Active` implies `listener == Bound`, `tunnelBlock != null`, and
  `control == Attached`.
- `PortAuthority == Complete` is the only authority that may close a forward
  because a port is missing.
- `PortAuthority == Complete` is valid only for the current control generation;
  a reconnect resets it to `Unknown`.
- In `PortMode == Disabled`, no port observation may move the forward to
  `Stopping`.
- A `ports_changed` event without a matching source block and session is
  ignored.
- Applied event sequence numbers are strictly increasing with no gaps; a gap
  is a control failure.
- No tunnel block is created until the local listener is bound, so
  `client_address` always names the real local port.
- `Stopping` and `Failed` leave no intentionally owned listener, stream, or
  tunnel block alive. Process death can still orphan the server-side block;
  stale-label cleanup handles that case.
- `Stopping` is idempotent; repeated close requests do not create a second
  cleanup pass.
- A `Closed` or `Failed` handle is never reused; a new click creates a new
  state machine.
- Every snapshot replacement is full, ordered, and associated with its source
  block.

### Closure rules

Close the forward when any of these is true:

- a complete `ports_changed` event or complete poll reply for the source block
  no longer contains the chosen address and port;
- the target port remains but only on another address;
- the source terminal block sends `block_closed`;
- the source terminal program sends `child_exited`;
- the source session sends `session_destroyed`;
- the user closes the forward or removes the app task;
- the control connection cannot be restored and the app chooses to stop
  forwarding.

Keep the forward when:

- `ports_changed` is `partial`, `truncated`, or `unsupported`;
- a poll reply is `partial`, `truncated`, or `unsupported`;
- the source block still exists but the server cannot inspect it;
- only the status changed and the chosen port is still present.

When the target port is missing from a complete reply but the forward was
created from a URL with an explicit port, close with `port_gone` and notify the
UI. Do not silently recreate the tunnel.

`PortGone`, `PortAddressChanged`, `SourceBlockClosed`, `SourceProgramExited`,
and `SessionClosed` are expected ends: their terminal outcome is `Closed`.
`UnsupportedServer`, `ConnectFailed`, `NotListening`, `NeedsPort`,
`BindFailed`, `TunnelFailed`, `ControlFailed`, and `Internal` are failures and
end in `Failed`.

### Link-click flow

Use `TerminalView.onOpenLink` as the interception point. `RexConnection` should
set it when binding a terminal:

1. Validate the URL with `TerminalLinks.webUrl`.
2. Only consider `http://` links whose host is loopback or the Rex server host.
   Do not forward arbitrary internet hosts.
3. Parse the port. With no explicit port, use the scheme default only when the
   port snapshot actually contains it. Otherwise treat the link as a
   block-target request: one distinct port is selected automatically, and
   several distinct ports enter `AwaitingSelection`.
4. If a forward for `(endpoint, session, sourceBlock, host, port)` is already
   active, rewrite and open immediately.
5. Otherwise start a native forward and poll until it reports `active` or a
   terminal failure. A short timeout of a few seconds is enough. If the native
   forward enters `AwaitingSelection`, show the selection UI instead of
   timing out.
6. Rewrite only the origin:

   ```text
   http://localhost:5173/app?x=1#top
   ->
   http://127.0.0.1:49152/app?x=1#top
   ```

   Keep path, query and fragment. Always use `127.0.0.1` in the rewritten URL
   to avoid Android resolving `localhost` to IPv6 first.
7. Launch the browser with the existing `ACTION_VIEW` intent.
8. If forwarding is unsupported or the port is not listening, do not silently
   open a phone-local `localhost` URL that cannot work. Show a short message,
   or open the original URL only when it is not loopback.

The forward service must be running before the browser moves to the foreground.
Starting it from the link tap is safe because the tap originates from the
foreground Rexling activity. If the service cannot start, fail the click
instead of leaving an unreachable `127.0.0.1` URL in Chrome.

`https://` links should not be forwarded in the first version. The browser
would validate a certificate for the original host against `127.0.0.1`, which
fails. Supporting them needs a TLS-terminating local proxy, a trusted local
certificate, or a host-mapping mechanism. That is a separate project.

### Android lifetime

A plain listener inside the activity's process is not reliable once Chrome
comes to the foreground. Add a small foreground service, for example
`PortForwardService`:

- `foregroundServiceType="connectedDevice"`;
- ongoing low-importance notification with the active port count;
- a `Close all` action;
- `stopWithTask="true"`;
- start when the first forward becomes active and stop when the last one
  closes.

The service, not the activity, owns the native forward handles and the forward
registry. The activity only requests a forward and opens the rewritten URL.
`onDestroy` and `onTaskRemoved` call `forwardCloseAll`; the notification's
`Close all` action does the same.

The manifest already declares `INTERNET`, `ACCESS_LOCAL_NETWORK`,
`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, and
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, so no new manifest permission is needed.
Reuse the existing notification-permission flow. Keep `PortForwardService`
separate from `BackgroundMonitorService`, because the monitoring preference may
stop that service while a browser session still needs its loopback listener.

If the process dies, the server-side tunnel block can become orphaned. Give
each tunnel block a recognizable label such as
`rexling fwd <host>:<port>` and close stale blocks with that label on the next
connection. Do not use a fixed client ID; Rex assigns a new client ID after a
reconnect.

## UX details

- Starting a forward can take a moment; show "Opening through port forward..."
  rather than a frozen tap.
- A failure should say whether the server is too old, the port is no longer
  listening, or the tunnel could not be created.
- Reuse an active forward for the same source block and target instead of
  creating duplicate tunnel blocks.
- Keep the forward alive while the user browses; close it on the port event,
  explicit user action, server disconnect, or app task removal.
- Consider a compact list of active forwards in the existing overflow sheet,
  with per-forward close. This is not required for the first version, but it
  makes orphan cleanup and troubleshooting much easier.

## Risks and limitations

- **Host header:** the browser sends `Host: 127.0.0.1:<localPort>`, not the
  original server name. Vite and most dev servers accept loopback hosts, but
  servers that validate the Host header or generate absolute URLs may fail.
- **Redirects and absolute asset URLs:** a redirect to the original port will
  bypass the forward and fail on the phone. A raw TCP tunnel cannot rewrite
  HTTP. Fixing this requires an HTTP-aware local proxy.
- **HTTPS:** certificate mismatch on `127.0.0.1`; not supported in v1.
- **Cookies, service workers and origins:** the page's origin becomes
  `http://127.0.0.1:<localPort>`, not the original origin. Origin-bound state
  may not carry over.
- **Local exposure:** any app on the phone can connect to a loopback listener.
  Bind only `127.0.0.1`, use an ephemeral port, and close promptly.
- **Port scan completeness:** a port may be missing because the process runs as
  another user, detached from the terminal, or inside tmux. Follow the
  completeness rules rather than polling aggressively.
- **Event latency:** `ports_changed` can arrive seconds to minutes after a
  change. The initial `ports` call is authoritative at click time, but closure
  may be delayed.
- **Throughput:** one WebSocket per TCP connection adds framing overhead and a
  handshake. It is fine for development pages, not a general-purpose proxy.
- **Revision coupling:** API revision 16 is newer than the live terminal
  contract. Keep the forward worker isolated and feature-detect it rather than
  upgrading the terminal actor.

## Implementation phases

### Phase 1: working HTTP forward

- Rust forward worker, JNI create/poll/close.
- `ports` validation and tunnel-block creation.
- Loopback listener and bidirectional bridge.
- Kotlin link interception and URL rewrite for `http`.
- `PortForwardService`, notification, manual close, and task-removal cleanup.

### Phase 2: lifecycle correctness

- `ports_changed` auto-close with complete-event rules.
- `block_closed` and `session_destroyed` cleanup.
- Reuse and stale-block cleanup.

### Phase 3: broader URL support

- HTTP-aware proxy for Host/Origin/Location rewriting.
- HTTPS strategy, if required.
- Active-forward UI and diagnostics.

## Validation plan

- Rust unit tests:
  - close only on `status == ok && !truncated`;
  - keep on `partial`, `truncated`, and `unsupported`;
  - prefer `127.0.0.1`, then `::1`;
  - close on source-block and session lifecycle events;
  - state transitions from starting to active to closed.
- Host integration:
  - create a temporary session and HTTP server;
  - start a forward;
  - verify an HTTP request through the local port;
  - stop the server and assert the forward and tunnel block close.
- Android instrumentation:
  - mocked native forward returns a local port;
  - tapping a loopback link opens the rewritten URL;
  - a non-loopback link passes through unchanged;
  - an unsupported server or missing port shows an error and does not open a
    phone-local loopback URL.
- Device test:
  - run a Vite or Python server in a Rex terminal;
  - tap its link in Rexling;
  - verify the page and HMR WebSocket load;
  - stop the server and verify the notification and local listener disappear.

## Open decisions

Resolved for the current implementation:

- **HTTP only.** `https://` links are not intercepted. Opening them through a
  loopback port would fail certificate validation, and a TLS-terminating proxy
  with a trusted local certificate is its own project. Unhandled links keep
  the old browser behavior.
- **Stay alive while the page is open.** A forward closes on a complete
  `ports_changed`, a source block/session close, user action, or app task
  removal. Leaving the terminal pane does not close it.
- **On demand only.** Rexling never forwards every listening port
  speculatively; it forwards the port of the tapped link, or asks the user to
  choose when the terminal has several ports.
- **Forward list UI.** The overflow menu has a `Port forwards` sheet with the
  target, local address, connection count, and a per-forward `Close`, plus the
  notification's `Close all`.

Still open:

- HTTPS support (TLS proxy or host mapping).
- HTTP-aware rewriting of `Host`, `Origin`, and `Location` for servers that
  validate them or redirect with absolute URLs.
- `rex forward new` style reconnect after a control loss; v1 deliberately
  stops and reports the failure.
