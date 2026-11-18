# Built-in Tailscale Lifecycle and Logout Mechanism

## Context & Overview

Rexling embeds Tailscale (`tailscale.com/tsnet`) via a Go daemon (`rexling-network`) to enable direct peer-to-peer connectivity to Tailscale nodes and Rex servers on private tailnets without requiring the external Android Tailscale VPN client app.

Previously, built-in Tailscale only supported interactive enrollment (`login`) and unauthenticated enrollment cancellation (`cancel_login`). Once a node reached `Running` status, there was no exit or sign-out mechanism exposed in either the native daemon or the Android settings UI:
- `cancel_login` rejected running nodes with an error (`"Tailscale is already connected; sign-in was not cancelled"`).
- `TailscaleStatusCard` in `NetworkSheet.kt` displayed only a static checkmark icon when `state == "Running"`.

## Architecture & Lifecycle

### 1. Tailscale Node Deregistration

In `tailscale.com/tsnet`, the embedded node operates through a `LocalClient` interface:
- Calling `local.Logout(ctx)` notifies the Tailscale coordination/control server (e.g. `controlplane.tailscale.com` or Headscale) that the node is logging out.
- The control server revokes or expires the node key and session.
- `tsnet.Server.Close()` terminates background WireGuard listeners and Goroutines.

### 2. State Directory Cleanup

tsnet maintains persistent configuration files in `Dir/` (specifically `tailscaled.state` and log configurations).
To guarantee that a subsequent sign-in begins with a completely fresh device identity rather than reconnecting as the previous node:
- The daemon calculates `stateDir = filepath.Join(config.Directory, hex.EncodeToString(sum[:16]))`.
- Upon `logout`, `os.RemoveAll(stateDir)` deletes all persisted credentials for that control server URL.
- The daemon marks `state.config.Suspended = true` to prevent any asynchronous server dials from reviving the node before explicit user configuration.

### 3. Android Client Integration

- **Command Bridge**: `NetworkSettings.logout()` sends the `"logout"` JSON command to the daemon.
- **Preference State**: Updates `tailscale_suspended = true` and `tailscale_prepared = false` in `SharedPreferences("network")`.
- **UI & UX**:
  - `TailscaleStatusCard` presents a "Sign out" (`R.string.sign_out`) action adjacent to the connected indicator.
  - A confirmation dialog (`SettingsConfirmDialog`) warns the user that signing out disconnects Rexling and clears the device pairing.
  - On confirmation, the UI transitions back to `Not set up` (`Off`), showing the `Set up` / `Sign in` actions again.
