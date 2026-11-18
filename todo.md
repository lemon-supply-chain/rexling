# Upstream Sync Plan & Status

Synchronized all upstream features and UI/UX design from `e62c6193ff0591e1549884c48f3600dd787f17a5` through `da7a0f9fa45759ffcf2f01d41865c3bbec614742` (`upstream/main`), while preserving repository customizations:
- Package name `dev.lemon.rex` preserved.
- Icon rainbow marquee and glint effects (`RainbowMarquee.kt`, `ProgramStack.kt`, `TerminalChrome.kt`) preserved.
- Custom vector artwork system (`ProgramArtwork.kt`, `ProgramVectorPicture.kt`, `ProgramVocabulary.kt`) preserved.
- QR scanner dialog with camera permissions preserved.
- Chinese localization (`res/values-zh/` and language toggle) preserved.
- Neovim shortcut profiles preserved.
- Custom terminal padding setting preserved.
- Built-in Tailscale sign-out and session reset preserved.
- Predictive local echo preserved.

---

## Commit Checklist

- [x] **1. `e62c619`** - Add reconnecting local TCP port forwards through Rex
  - **Full Hash**: `e62c6193ff0591e1549884c48f3600dd787f17a5`
  - **Area**: Port Forwarding, Rust Core, Android Service
  - **Status**: Synced to `PortForwardService.kt`, `PortForwards.kt`, `forward.rs`, `forwards.rs`.

- [x] **2. `af91b55`** - Add SSH and built-in Tailscale with smoother keyboard rendering
  - **Full Hash**: `af91b55ad59c3e6fc41bd2eebc4a0b64f5b2dc98`
  - **Area**: Network & SSH, Tailscale, Go Daemon, UI & IME
  - **Status**: Synced `rexling-network` (Go daemon), `NetworkSheet.kt`, `NetworkSettings.kt`, and viewport smoothing.

- [x] **3. `c65cd06`** - Keep healthy terminal connections through network handoffs
  - **Full Hash**: `c65cd06800817e9765127ddc3bd5adbaa317315c`
  - **Area**: Network Resilience, JNI Engine
  - **Status**: Synced to `rexling-core` network reconnect & route tracking.

- [x] **4. `5596fe6`** - Keep terminal prompt footers visible above the keyboard
  - **Full Hash**: `5596fe6f67cee83de3984acf05f0cac0efec4f36`
  - **Area**: Keyboard Viewport, Terminal UI
  - **Status**: Synced `TerminalImeViewport.kt` and `TerminalImeHandoff.kt`.

- [x] **5. `b4e671f`** - Stabilize terminal redraws and detect semantic input regions
  - **Full Hash**: `b4e671f635a9e643ac23ba57cbc1114b04562925`
  - **Area**: Ghostty Shim, Terminal Rendering, Semantic Zones
  - **Status**: Synced `ghostty_shim.c` and semantic prompt navigation in `TerminalView.kt`.

- [x] **6. `296fc99`** - Add OSC clipboard, semantic command actions, and terminal progress
  - **Full Hash**: `296fc998383188cf1344a51d894d68ef8fa51a99`
  - **Area**: Terminal Features, OSC Escape Sequences, Progress Bar
  - **Status**: Synced `TerminalClipboardDelivery.kt`, `TerminalProgress.kt`, `progress.rs`.

- [x] **7. `b225407`** - Support pasting SSH keys and compact the server editor
  - **Full Hash**: `b2254071deafa6ffb24b43f8a43819df1697d85e`
  - **Area**: Server Configuration UI, SSH Keys
  - **Status**: Synced compact `ServerEditor` with SSH key paste and route selection.

- [x] **8. `bf50fa1`** - Preserve Gboard word context and support suggestion corrections
  - **Full Hash**: `bf50fa139768a3970df8a516e4fe9544cd05bf1e`
  - **Area**: IME / Gboard Integration
  - **Status**: Synced `TerminalImeLine.kt` word context handling.

- [x] **9. `ad3e8ae`** - Keep Gboard layout stable when switching panes
  - **Full Hash**: `ad3e8ae09d5b780a0873f550b792cdd9db1b35d1`
  - **Area**: Terminal Panes, Keyboard State
  - **Status**: Synced `TerminalImeHandoff.kt` pane focus management.

- [x] **10. `663df32`** - Skip superseded pane and session navigation before execution
  - **Full Hash**: `663df32d0c80d80043dc022af5f11c70cd7a22d4`
  - **Area**: JNI Engine, Navigation Queue
  - **Status**: Synced `input_queue.rs` navigation deduplication.

- [x] **11. `0a2f8f9`** - Extract cursor-aware keyboard correction model and test edit sequences
  - **Full Hash**: `0a2f8f9dbb343da9aa4c97640439d1d390fda726`
  - **Area**: IME Architecture, Unit Testing
  - **Status**: Synced `TerminalImeLine.kt` and `TerminalImeLineTest.kt`.

- [x] **12. `4187a1c`** - Add smooth local scrollback and reuse shifted row drawings
  - **Full Hash**: `4187a1ce33c1adba0c9664ffd73b0dc9294ccc91`
  - **Area**: Terminal Rendering, Scrollback Performance
  - **Status**: Synced `TerminalScrollMotion.kt` and row reuse logic.

- [x] **13. `f595ab8`** - Improve tab activity badges, Gboard input and screen-awake preferences
  - **Full Hash**: `f595ab896f9d36b0d70daa25e2012018200d630b`
  - **Area**: Preferences, Tab UI, IME
  - **Status**: Synced `KeepScreenAwake.kt`, `ActivityBadges.kt`, and updated `PreferencesSheet.kt`.

- [x] **14. `a2bd804`** - Honor program cursor modes and stabilize keyboard viewport navigation
  - **Full Hash**: `a2bd804d843b7314635499679ad7c285cbd65e84`
  - **Area**: Ghostty Shim, Cursor Appearance, Viewport Navigation
  - **Status**: Synced `ghostty_shim.c`, `TerminalUpdates.kt`, `TerminalView.kt`.

- [x] **15. `6948dbd`** - Add per-server network routing and improve Tailscale setup
  - **Full Hash**: `6948dbd6e3f304faada679143b3250e83ec017eb`
  - **Area**: Network & Tailscale, Server Profiles
  - **Status**: Synced `NetworkSettings.kt`, route tracking, and `TailscaleSetupTest.kt`.

- [x] **16. `a530fc6`** - Add automatic localhost forwarding and unify drawer styling
  - **Full Hash**: `a530fc64d5f8107a1017e90b551649c09d05810d`
  - **Area**: Port Forwarding, Drawer UI Components
  - **Status**: Synced `ForwardPolicy.kt`, `ForwardLinkUi.kt`, `SettingsDrawer.kt`.

- [x] **17. `7e1cd8e`** - Add drag-and-drop keyboard toolbar customization
  - **Full Hash**: `7e1cd8ecf997558af4b9fe18bfd3b9129173bdf9`
  - **Area**: Keyboard Toolbar, Customization UI
  - **Status**: Synced `KeyboardToolbar.kt`, `KeyboardToolbarSheet.kt`, and `ToolbarLayoutTest.kt`.

- [x] **18. `ee1269f`** - Reuse live floating terminal streams when reopening panes
  - **Full Hash**: `ee1269fa5da27e69c1c4ee00b8686f0aafe097f5`
  - **Area**: JNI Engine, Terminal Stream Caching
  - **Status**: Synced `RetainedFloating` stream caching in `rexling-core/jni/src/engine.rs`.

- [x] **19. `4f2cfae`** - Add editable Ctrl shortcut sequences
  - **Full Hash**: `4f2cfae090f0c0ae2f8d36371cb1fbe24cb47f89`
  - **Area**: Keyboard / Ctrl Shortcuts, Customization UI
  - **Status**: Synced `ControlShortcutConfig.kt`, `ControlShortcutsSheet.kt`, and `ControlShortcuts.kt`.

- [x] **20. `68d1e35`** - Add structured Neovim shortcut editing and groups
  - **Full Hash**: `68d1e35d1f8553d1fb525f674516be702e8ebdb3`
  - **Area**: Neovim Shortcuts, Drag-and-drop Group Editor
  - **Status**: Synced `NeovimShortcutEditor.kt`, `NeovimShortcutForm.kt`, `NeovimShortcutsSheet.kt`.

- [x] **21. `715dd4c`** - Render terminal screens before scrollback finishes loading
  - **Full Hash**: `715dd4c871efd3013d289e6eb1f3c7e754593ae4`
  - **Area**: Ghostty Shim, Stream Decoder, Screen-first Snapshot
  - **Status**: Synced `ghostty_shim.c` (`rp_decode_ready`, `rp_decode_next`) and `snapshot.rs`.

- [x] **22. `878248e`** - Use themed text colors in transparent settings drawers
  - **Full Hash**: `878248eecb5fae5f3964fc5f206a4cb6fa8e9d3d`
  - **Area**: UI / Theme Styling, Settings Drawer
  - **Status**: Synced `SettingsDrawer.kt` contentColor handling and `drawer-dark-text-2026-11-18.json`.

- [x] **23. `bc0b4c3`** - Add reusable SSH key library and server key selection
  - **Full Hash**: `bc0b4c338e553be0c3924f1141df90e3aa002a28`
  - **Area**: Network Daemon, SSH Key Management, Settings UI
  - **Status**: Synced `rexling-network` fingerprint calculation, `SshKeyLibrary.kt`, `SshKeysSheet.kt`, `ServersSheet.kt`.

- [x] **24. `d181aee`** - Keep hidden Claude cursor positions for input and keyboard panning
  - **Full Hash**: `d181aeead8eb333bf69b59620ca117a7a5c8e234`
  - **Area**: Terminal Core / RXT7 Wire Protocol, IME Anchor
  - **Status**: Synced `cursor_position` export in Ghostty, `RXT7` wire protocol in JNI and Kotlin, `cursorAnchorX`/`Y`.

- [x] **25. `81bce6c`** - Prepare release build 167
  - **Full Hash**: `81bce6cf9a7c36c49c71987ba62078516faef4bb`
  - **Area**: Release Packaging
  - **Status**: Bumped version code to 167 in `build.gradle.kts` and verified artifacts.

- [x] **26. `d92d836`** - Add an option for the keyboard's Enter to submit
  - **Full Hash**: `d92d836696db2256ffb861e6878b2d131f4a9b6c`
  - **Area**: Keyboard Preferences, Terminal Input
  - **Status**: Synced `enterSubmits` option in `PreferencesSheet.kt`, `TerminalView.kt`, and `TerminalInput.imeKeys`.

- [x] **27. `ecf9777`** - Stop typed input from delaying its own echo by a frame
  - **Full Hash**: `ecf9777df7f7fa2ff8ea97a31f0a202c4161794b`
  - **Area**: JNI Engine, Typing Latency
  - **Status**: Synced `scrolled_back` typing dirty render flag in `rexling-core/jni/src/engine.rs`.

- [x] **28. `89f18fe`** - Keep fast flings from sticking at the overscan edge
  - **Full Hash**: `89f18fe67d268d0ee54e19b996f8aeb9b5b2713f`
  - **Area**: JNI Engine, Scroll Motion
  - **Status**: Synced `scroll_instant` rendering flag in `rexling-core/jni/src/engine.rs`.

- [x] **29. `55520d7`** - Expect the current RXT7 packet header in the JNI smoke test
  - **Full Hash**: `55520d74268153b6fa620e74e30ef6823c914bf6`
  - **Area**: JNI Tests
  - **Status**: Synced `tools/jni-smoke/dev/lemon/rex/NativeBridge.java`.

- [x] **30. `da7a0f9`** - Prepare release build 168
  - **Full Hash**: `da7a0f9fa45759ffcf2f01d41865c3bbec614742`
  - **Area**: Release Packaging
  - **Status**: Bumped `versionCode = 168` in `build.gradle.kts`.

---

## Verification
- Rust Core (`rexling-core`): 50 passed, 3 ignored (6 suites).
- Go Network Daemon (`rexling-network`): 8 passed in 1 package.
- Android Application (`rexling-android`):
  - Debug compilation: `BUILD SUCCESSFUL`.
  - Debug unit tests: 310 passed, 1 skipped (`testDebugUnitTest` SUCCESS).
  - Debug APK assembly: `BUILD SUCCESSFUL` (`assembleDebug`).
