# Android Release APK Size Optimization Analysis & Implementation

## 1. Actual Achieved Optimization Results

Release build comparison before and after applying optimizations:

- **Before**: `38.55 MB` (compressed APK), `45.79 MB` (uncompressed payload)
- **After**: `26.94 MB` (compressed APK), `31.04 MB` (uncompressed payload)
- **Total Reduction**: **-11.61 MB (-30.1% overall reduction)**

| Component | Before (Compressed) | After (Compressed) | Difference | Optimization Applied |
| :--- | :--- | :--- | :--- | :--- |
| `lib/arm64-v8a/librex_network.so` | 18.67 MB | 15.16 MB | **-3.51 MB** | Tailscale `ts_omit_*` build tags via `featuretags` |
| `lib/arm64-v8a/librex_android.so` | 5.92 MB | 4.31 MB | **-1.61 MB** | Rust `[profile.release]` Fat LTO + Zig `ReleaseSmall` |
| Embedded Nerd Fonts | 5.34 MB (4 files) | 1.33 MB (1 file) | **-4.01 MB** | Redundant weights unreferenced; pruned by AGP shrinker |
| `libc++_shared.so` | 1.36 MB | 0.00 MB | **-1.36 MB** | Removed dead library (zero dynamic dependents) |
| `classes.dex` | 4.89 MB | 3.75 MB | **-1.14 MB** | R8 fullMode + pruned CameraX/ZXing Proguard rules |
| **Total Release APK** | **38.55 MB** | **26.94 MB** | **-11.61 MB** | |

---

## 2. Implemented Changes

### A. Native Library Optimizations
1. **Removed `libc++_shared.so`**:
   - `llvm-readelf -d` proved neither `librex_android.so` nor `librex_network.so` requires C++ runtime.
   - Removed copy step in `rexling-core/scripts/build_native.py` and added packaging exclusion in `rexling-android/app/build.gradle.kts`.
2. **Tailscale Modular Trimming (`librex_network.so`)**:
   - Integrated `tailscale.com/cmd/featuretags --min --add=netstack,ipnbus,serve,acme,udptransport,nattraversal,portmapper,bakedroots,useproxy` in `rexling-core/scripts/build_native.py`.
   - Stripped unused enterprise/server subsystems (WebDAV, Taildrop, AWS/Kube, etc.) while keeping the UDP transport, NAT traversal, port mapping, and TLS root requirements needed for direct P2P (see `research/tailscale-p2p-derp-investigation.md`).
3. **Rust & Zig Release Profile (`librex_android.so`)**:
   - Added `[profile.release]` with `opt-level = "s"`, `lto = "fat"`, `codegen-units = 1`, and `strip = true` in `rexling-core/Cargo.toml`.
   - Switched Zig Ghostty build from `-Doptimize=ReleaseFast` to `-Doptimize=ReleaseSmall`.

### B. Font Packaging Exclusion
- Updated `rexling-android/app/src/main/java/dev/lemon/rex/TerminalFonts.kt` to use `jetbrains_nerd_regular` with `Typeface.CustomFallbackBuilder` synthetic styling.
- Because `jetbrains_nerd_bold`, `jetbrains_nerd_italic`, and `jetbrains_nerd_bold_italic` are no longer referenced in code, AGP's `isShrinkResources = true` automatically drops all three 2.4 MB files from the release APK without needing to physically remove them from disk.

### C. DEX & ProGuard Rules
- Replaced `-keep class androidx.camera.** { *; }` and `-keep class com.google.zxing.** { *; }` in `rexling-android/app/proguard-rules.pro` with scoped rules matching only the QR scanner requirements.
- Enabled `android.enableR8.fullMode=true` in `rexling-android/gradle.properties`.

---

## 3. Real Device Verification

- Device: Redmi K50 Ultra (22081212C, Android 15)
- Installation: `adb install -r rexling-android/app/build/outputs/apk/release/app-release.apk` (Success)
- Launch & Runtime: Process `dev.lemon.rex` started cleanly with zero runtime crashes or missing symbol exceptions.
- Unit Tests: All debug and release unit tests passed (`BUILD SUCCESSFUL`).
