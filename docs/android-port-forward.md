# Port forwarding in Rexling

Rexling can open a terminal's `http://` link through a port that a program
listens on in the Rex session. The phone cannot use `adb forward`, and it
cannot call Rex.app's local `rex forward` registry, so it builds the equivalent
from the server API:

1. the phone opens a loopback TCP listener on `127.0.0.1:0`;
2. it creates a detached `com.superlogical.tunnel.dial` block in the source
   session;
3. every accepted browser connection opens one `rex.data.bytes.v1` stream
   through that block and is bridged byte for byte.

The forward lives in `PortForwardService` and therefore survives the activity
going to the background while Chrome is in front.

## User behavior

- Tapping a link whose scheme is `http` and whose host is loopback
  (`localhost`, `127.0.0.0/8`, `::1`) or the configured Rex server host starts
  a forward.
- Other links, `https://` links, and arbitrary internet hosts keep the normal
  browser behavior.
- An explicit port wins. Without one, Rexling uses port 80 only when the
  terminal is listening on it; otherwise one distinct port is selected
  automatically and several distinct ports show a chooser.
- The browser receives
  `http://127.0.0.1:<local-port><same path>?<same query>#<same fragment>`.
  The `https` ceiling, `Host` header, redirects, and origin-bound cookies are
  the documented limits of a raw TCP tunnel.
- A forward closes when a complete port snapshot no longer contains the chosen
  address and port, when the source block or program ends, when the session
  ends, when the user closes it, or when the app task is removed.
- The ongoing notification shows the number of active forwards and has a
  `Close all` action. The overflow menu has `Port forwards` for per-forward
  close, matching `rex forward ls` and `rex forward close`.

## State machine

One native worker owns one forward. UI calls only submit commands; the worker
publishes a snapshot with a monotonic revision.

```
new -> connecting -> resolving -> binding -> creating_tunnel -> active
                          |
                          +-> awaiting_selection -> binding

any non-terminal phase -> stopping -> closed | failed
```

The worker state is the tuple `(phase, authority, portMode, reason, failure)`.
`phase` is the lifecycle position, `authority` describes whether the latest
port snapshot can prove a disappearance, `portMode` selects how snapshots are
observed, and `reason`/`failure` are set when the worker enters `stopping`.

| Current | Event | Guard | Next | Action |
| --- | --- | --- | --- | --- |
| `new` | start | request valid | `connecting` | Open revision-16 control |
| `connecting` | welcome | revision >= 16 and source block | `resolving` | Use `event` port mode |
| `connecting` | welcome | revision == 15 and source block | `resolving` | Use `poll` port mode |
| `connecting` | welcome | revision >= 15 and no source block | `resolving` | Use `disabled` port mode |
| `connecting` | welcome | revision < 15 | `stopping` | `failed`, `unsupported_server` |
| `connecting` | connect error | - | `stopping` | `failed`, `connect_failed` |
| `resolving` | `ports` reply | explicit port present | `binding` | Record target address |
| `resolving` | `ports` reply | explicit port missing from complete snapshot | `stopping` | `failed`, `not_listening` |
| `resolving` | `ports` reply | explicit port in incomplete snapshot | `binding` | Continue optimistically |
| `resolving` | `ports` reply | automatic target, one port | `binding` | Select that port |
| `resolving` | `ports` reply | automatic target, several ports | `awaiting_selection` | Publish choices |
| `resolving` | `ports` reply | automatic target, zero ports | `stopping` | `failed`, `not_listening` |
| `resolving` | `ports` reply | automatic target, incomplete snapshot | `stopping` | `failed`, `needs_port` |
| `awaiting_selection` | `selectPort(port)` | port is still present | `binding` | Record selected address |
| `awaiting_selection` | complete port snapshot | zero choices | `stopping` | `failed`, `not_listening` |
| `binding` | listener bound | - | `creating_tunnel` | Record local port |
| `creating_tunnel` | tunnel block created | - | `active` | Publish local URL and accept connections |
| `active` | complete snapshot with target | - | `active` | Refresh labels only |
| `active` | complete snapshot, target absent | - | `stopping` | `closed`, `port_gone` |
| `active` | complete snapshot, same port on another address | - | `stopping` | `closed`, `port_address_changed` |
| any non-terminal | source block/program/session closes | matching source | `stopping` | `closed`, source reason |
| any non-terminal | tunnel block closes | matching tunnel | `stopping` | `failed`, `tunnel_failed` |
| any non-terminal | user close or task removal | - | `stopping` | `closed`, `user` |
| any non-terminal | control loss or event sequence gap | - | `stopping` | `failed`, `control_failed` |
| `stopping` | cleanup complete | - | `closed` or `failed` | Publish terminal snapshot |

- `portMode` is `event` with API revision 16 and a source block, `poll` with
  revision 15 (15-second `ports` calls), and `disabled` without a source block.
- A port snapshot is `complete` only for `status == "ok"` without
  `truncated`. `partial`, `truncated`, and `unsupported` never close a working
  forward. A truncated snapshot with an `id` is completed with `blob_read`.
- `ports_changed` carries the full list and replaces the previous snapshot.
  The first `ports` reply and later events share one `seq` space; the worker
  skips events at or below the reply's `seq` and requires strictly increasing
  event sequence numbers.
- `port_gone`, `port_address_changed`, source block/program/session close, and
  user close end as `closed`. Unsupported server, connection failure, missing
  port, bind/tunnel failure, and control failure end as `failed`.
- App task removal uses the same close path as a user close (`user`) in v1.
- Cleanup stops accepting, aborts streams, closes the tunnel block, closes the
  control connection, and then publishes the terminal phase.

## Source map

| Piece | File |
| --- | --- |
| Native worker and state machine | `rexling-core/src/forward.rs` |
| Revision-16 control and reply `seq` | `rexling-core/src/control.rs` |
| JNI registry | `rexling-core/jni/src/forwards.rs` |
| Kotlin models, URL rules, registry | `rexling-android/.../PortForward.kt` |
| Foreground service | `rexling-android/.../PortForwardService.kt` |
| Forward list sheet | `rexling-android/.../PortForwardsSheet.kt` |
| Link interception | `RexConnection.bindTerminalView` |

## Limits

- `https` is not forwarded; a local TLS proxy with a trusted certificate is
  required for that.
- No `-R`/share, no `--bind *`; the listener always binds `127.0.0.1`.
- A control loss closes the forward instead of reconnecting.
- Servers that validate `Host`/`Origin` or redirect to the original port can
  fail; fixing that needs an HTTP-aware proxy.
- `ports_changed` is produced by the server's background scan. While the
  terminal is idle the scan may be minutes apart, so closure can lag the
  actual process exit. The worker closes as soon as the event arrives.

## Verification

```sh
# Rust unit tests for snapshots, authority, and address preference
cd rexling-core && cargo test --lib forward::

# End-to-end against an installed server (creates disposable sessions)
REX_SERVER=unix:///path/to/server.sock cargo test --test forward_live -- --ignored

# Android JVM tests and APK
cd rexling-android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The live tests cover an HTTP 200 through the forwarded port, automatic port
selection with a chooser round trip, a `port_gone` close from
`ports_changed`, and cleanup when the session closes. Device verification of
the foreground service and browser handoff still needs a physical phone.
