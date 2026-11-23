# Tailscale P2P Direct Connection and DERP Relay Investigation

## 1. Issue Summary

In the Release build of Rexling, peer-to-peer (P2P) connections fail even when both nodes are on the same local area network (LAN). Instead, traffic falls back to Tailscale DERP relay servers, causing extreme latency (150ms-500ms+), bandwidth throttling, and connection drops.

## 2. Root Cause Analysis

During recent APK size optimization, `librex_network.so` was pruned using Tailscale's modular feature tags:
```python
tags = subprocess.check_output(
    ["go", "run", "tailscale.com/cmd/featuretags", "--min", "--add=netstack,ipnbus,serve,acme"],
    cwd=network, text=True
).strip()
```

The `--min` flag strips all features not explicitly listed in `--add`. This resulted in the following critical omissions:

1. **`ts_omit_udptransport`** (`buildfeatures.HasUDPTransport = false`):
   - Defined in Tailscale upstream: *"UDP transport to peers (if omitted, all peer traffic is relayed over DERP)"*.
   - In `wgengine/magicsock/magicsock.go`:
     ```go
     const hasUDPTransport = buildfeatures.HasUDPTransport && runtime.GOOS != "js"

     func (c *Conn) sendUDP(...) (sent bool, err error) {
         if !hasUDPTransport {
             return false, errNoUDP
         }
         ...
     }
     ```
   - When disabled, Magicsock unconditionally rejects all outgoing and incoming UDP packets to peers with `errNoUDP`.
   - WireGuard cannot transmit UDP directly over LAN, making direct P2P impossible regardless of LAN proximity. All traffic is forced onto DERP over HTTPS/TCP.

2. **`ts_omit_nattraversal`** (`buildfeatures.HasNATTraversal = false`):
   - Disables STUN endpoint discovery, disco hole-punching, and peer path probing.
   - Prevents endpoints from discovering each other's direct LAN or public IP addresses.

3. **`ts_omit_portmapper`** (`buildfeatures.HasPortmapper = false`):
   - Disables NAT-PMP, PCP, and UPnP port mapping capabilities.

4. **`ts_omit_bakedroots`** and **`ts_omit_useproxy`**:
   - `ts_omit_bakedroots` strips embedded fallback CA roots, which can cause TLS handshake issues on certain Android versions.
   - `ts_omit_useproxy` strips proxy support (`HTTP_PROXY`, `ALL_PROXY`, system proxy), preventing DERP connections in environments where direct outbound traffic is restricted or throttled.

## 3. Disambiguation: "Deleting Tailscale" Scenarios

| Scenario | Impact on DERP | Impact on P2P | Explanation |
| :--- | :--- | :--- | :--- |
| **Pruning Tailscale features via `featuretags --min`** | Forced to use DERP; DERP may fail if `bakedroots`/`useproxy` missing | **Completely broken** (`errNoUDP`) | `udptransport` and `nattraversal` were stripped in the release build. |
| **Deleting/revoking node in Tailscale Admin Console** | **Broken** (401/403 unauthorized) | **Broken** | The node loses authentication, node key is invalidated, and it cannot fetch the DERP map or node peers. |
| **Uninstalling standalone Tailscale Android App** | **No impact** | **No impact** | Rexling embeds its own independent `tsnet` daemon with separate state, identity, and WireGuard stack. |

## 4. Remediation Plan

Update `rexling-core/scripts/build_native.py` to retain essential P2P networking capabilities while preserving the pruning of heavy cloud and enterprise modules:

```python
tags = subprocess.check_output(
    [
        "go", "run", "tailscale.com/cmd/featuretags",
        "--min",
        "--add=netstack,ipnbus,serve,acme,udptransport,nattraversal,portmapper,bakedroots,useproxy",
    ],
    cwd=network,
    text=True,
).strip()
```

### Expected Outcome:
- Retains ~80-90% of the size savings achieved from dropping enterprise/cloud/CLI modules (Kube, AWS, BGP, Synology, WebDAV, etc.).
- Restores full UDP transport and NAT traversal: nodes on the same LAN establish direct WireGuard UDP sessions with `<2ms` latency.
