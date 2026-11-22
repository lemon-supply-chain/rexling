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

- [x] **31. `1b009b2`** - Keep Gboard stable when changing sessions and simplify candidate strip
  - **Full Hash**: `1b009b244d2d482d8c3629e46a74bbf3b76a6b57`
  - **Area**: IME / Gboard, Input Connection
  - **Status**: Synced `TerminalImeViewport.kt` and `TerminalCarousel.kt`.

- [x] **32. `2e02fc4`** - Auto-hide key toolbar when the keyboard is closed
  - **Full Hash**: `2e02fc434b9d0ce47101a07010a300d8d0cfafc8`
  - **Area**: Keyboard Toolbar, UI Layout, Settings
  - **Status**: Synced `toolbarWithKeyboardOnly` preference, `Modifier.keyboardRevealed`, `TerminalActions` keyboard trigger, and `PreferencesSheet.kt`.

- [x] **33. `7e62e98`** - Configure custom Tailscale device name
  - **Full Hash**: `7e62e987c47311746c078aeec5809da8a50672e8`
  - **Area**: Tailscale, Go Daemon, Network UI
  - **Status**: Synced `Hostname` configuration in `rexling-network/main.go`, `TailscaleHostname` validator in `NetworkSettings.kt`, and device name input in `NetworkSheet.kt`.

- [x] **34. `b58859e`** - Stop frosted settings drawers from invalidating every frame
  - **Full Hash**: `b58859e86470081bf5c822558509ce77e64a4b4e`
  - **Area**: Performance, Compose Rendering, Haze Blur
  - **Status**: Instantiated `val frost = Modifier.drawerFrost(...)` outside `ModalBottomSheet` across all settings sheets (`PreferencesSheet.kt`, `SettingsDrawer.kt`, `ServersSheet.kt`, `PortForwardSheet.kt`, `ShortcutProfileSheets.kt`), eliminating continuous redraws.

- [x] **35. `8c375c9`** - Move sessions into the unified tab and pane drawer
  - **Full Hash**: `8c375c9424c30c80ff2d12fb6230f2c4fe866385`
  - **Area**: Drawer UI, Tab & Pane Switcher, Session Navigation
  - **Status**: Unified Sessions into `TabPaneSwitcher.kt` (`SwitcherSessions`, `SessionsPage`, `sessionsPageHeight`), restored translucent styling, and updated `DrawerFrostTest.kt` and `SessionSwitcherTest.kt`.

- [x] **36. `8d60289`** - Prepare release build 169
  - **Full Hash**: `8d602897d1f7facfff23d21d76d4313ab89a3c98`
  - **Area**: File Uploads, Release Packaging
  - **Status**: Generalized image uploads to arbitrary files across `rexling-core/src/upload.rs`, JNI bridge, `ImageUploads.kt`, `MainActivity.kt` file picker, bumped `versionCode = 169` in `build.gradle.kts`.

- [x] **37. `27a07dc`** - Add a side tab rail, chosen by the app window's width
  - **Full Hash**: `27a07dc537df1a1a2384a56a62657e289bf53b92`
  - **Area**: Tab Navigation, Adaptive Layout
  - **Status**: Synced `TabRail.kt` (`TerminalTabRail`), `TabBarLayout`, `TabBarPosition`, `TabRailStyle`, `PreferencesSheet.kt` tab rail options.

- [x] **38. `7689225`** - Swipe or tap to expand and collapse the side tab rail
  - **Full Hash**: `76892251a37c6314f8842ae37a280c4fa0ba62f9`
  - **Area**: Gestures, Tab Rail Interaction
  - **Status**: Implemented rail collapse/expand animations, touch targeting and semantic accessibility.

- [x] **39. `8a01bdc`** - Show a tab's panes side by side when unfolded and full screen
  - **Full Hash**: `8a01bdcfc9d2f3a61dc4b6be2dbe7d507116a4a0`
  - **Area**: Multi-Pane Layout, Foldables
  - **Status**: Added `MultiPaneLayout.kt`, `multiPaneTiles`, `PaneSplitLine`, `Models.kt` split/zoom models.

- [x] **40. `1d4c7c0`** - Polish the multi-pane layout and let the side rail reach the screen edge
  - **Full Hash**: `1d4c7c0b89326f1406e2373752e399517dc00965`
  - **Area**: Window Insets, Multi-Pane UI
  - **Status**: Integrated edge-to-edge window insets, flush single pane styling, and seamless navigation clearance.

- [x] **41. `ec5fbff`** - Float the side rail beside multiple panes and keep its tabs in thumb reach
  - **Full Hash**: `ec5fbff0e68d879ba22fbc81b37cae2170327f23`
  - **Area**: Floating Rail, Ergonomics
  - **Status**: Added island floating rail beside tiled layout, preserving ergonomic spacing.

- [x] **42. `ae0f538`** - Drag pane borders in the multi-pane layout
  - **Full Hash**: `ae0f5386db8c5445aa4c1e40562be9cb4433606f`
  - **Area**: Split Resizing, Gestures
  - **Status**: Implemented border drag handles, `resizeSplit` command in `RexConnection.kt`, and ratio persistence.

- [x] **43. `82fc5d0`** - Zoom a pane from its corner menu in the multi-pane layout
  - **Full Hash**: `82fc5d0a688ee4102941ecae97c36d226a267a14`
  - **Area**: Zoom Controls, Tile Overlays
  - **Status**: Added corner actions pill, pane zoom toggle, and unzoom animation.

- [x] **44. `33adfb0`** - Keep foldable pane streams live across focus changes
  - **Full Hash**: `33adfb080ee11746f39dcf282d8d85f8185c88b7`
  - **Area**: JNI Mirroring, Background Streams
  - **Status**: Implemented pane mirroring cache (`mirror.rs`), `RexConnection.mirror()`, and live frame multiplexing.

- [x] **45. `87f72ed`** - Remove the bottom gap for single panes with side tabs
  - **Full Hash**: `87f72edf72e503378fc9e09d436ec539c368ff16`
  - **Area**: Viewport Padding, Terminal Layout
  - **Status**: Refined `flushSinglePane` and `singlePaneNavigationClearance` in `MainActivity.kt`.

- [x] **46. `736173a`** - Fix session recovery, pointer clicks, and foldable drawers
  - **Full Hash**: `736173a9856ad8d0c64eb3d96924c25608b61cba`
  - **Area**: Mouse Input, Session Recovery, Drawer Layout
  - **Status**: Handled mouse clicks once without triggering soft keyboard, offloaded Kitty bitmap generation, and bounded drawer headers.

- [x] **47. `bf90ea7`** - Add drag and split controls to foldable panes
  - **Full Hash**: `bf90ea7f631dfad43c3300a74797ebcfdb4c1bb6`
  - **Area**: Drag & Drop, Tile Reordering
  - **Status**: Added drag drop zones, tile reordering, and swap support across `MultiPaneLayout.kt` and JNI tests.

- [x] **48. `4d07e1a`** - Refine foldable drawers and add global pane search
  - **Full Hash**: `4d07e1ae1d72379361ad22f9d505f317d7ae65f8`
  - **Area**: Global Search, Pane Catalog
  - **Status**: Added `AllPanesPage.kt`, `PaneCatalog.kt`, and `requestPaneCatalog` in JNI engine and Android client.

- [x] **49. `c731026`** - Add floating panes to the unfolded workspace
  - **Full Hash**: `c731026ad99b456631ad02e960303126f54c9a6d`
  - **Area**: Floating Panes, Multi-Pane Overlay
  - **Status**: Added floating overlay placements over tiled panes, dock sheets, and minimize/dock actions.

- [x] **50. `acd8b2c`** - Support edge drops when dragging tiled panes
  - **Full Hash**: `acd8b2c4bf246c43fa96636aa712a2df14d1f2b6`
  - **Area**: Drag & Drop, Edge Snapping
  - **Status**: Implemented edge drop targets (top/bottom/left/right splits) during tile dragging.

- [x] **51. `38cc96d`** - Reduce redraws and state work for mirrored panes
  - **Full Hash**: `38cc96d9f8266e74b3017a0d421712a1499b2447`
  - **Area**: Performance, Mirror Delta Caching
  - **Status**: Implemented `pane_frames` delta caching in `updates.rs` and wired RXT8 wire protocol updates.

- [x] **52. `79ba686`** - Tighten foldable pane layout and reduce mirror overhead
  - **Full Hash**: `79ba6868df3241b71d9d97cb656ae995dfbe831e`
  - **Area**: Layout Margins, Redraw Optimization
  - **Status**: Streamlined margins, added mirror obscured flags, and minimized PTY reallocations.

- [x] **53. `7b47d85`** - Sync floating pane minimization through Rex
  - **Full Hash**: `7b47d859fa4f004aeb53c158525b445582f6f597`
  - **Area**: Minimization Protocol, State Machine
  - **Status**: Synced `minimize_floating` command and state updates across core, JNI, and Android UI.

- [x] **54. `1d2185d`** - Bound floating pane recovery and streamline restore
  - **Full Hash**: `1d2185d58d9ec76fba899988357777ee2d708fc2`
  - **Area**: Workspace Navigation, Pane Recovery
  - **Status**: Added `restoreMinimized` flag to `selectBlock`, avoiding ghost panes on session restore.

- [x] **55. `e372aac`** - Improve Fold terminal scrolling, themes, and floating pane focus
  - **Full Hash**: `e372aac24e0bcf5e7c8446b5a329d8b746ce0b61`
  - **Area**: Fold Viewport, Focus Management
  - **Status**: Fixed soft keyboard focus retention across pane switches in `MultiPaneLayout.kt`.

- [x] **56. `56887a5`** - Give the launch window the canvas the app actually draws
  - **Full Hash**: `56887a55c2f58e17db174c6530635905d2ee575a`
  - **Area**: App Launch Window, Window Background
  - **Status**: Updated launch canvas in `styles.xml` and `styles.xml (night)` to `#f3f4f9` and `#090c11`.

- [x] **57. `7182652`** - Make the built-in themes token themes like the presets
  - **Full Hash**: `7182652f4c4a520bfba50bf54e951bf0212351bb`
  - **Area**: Design Tokens, Theme Architecture
  - **Status**: Migrated built-in themes to `DefaultUiTokens` in `ThemeColors.kt` and `ThemeCatalog.kt`, verified via `ThemeColorsTest.kt`.

- [x] **58. `b6d4a45`** - Update JNI smoke test for RXT8
  - **Full Hash**: `b6d4a45070054c0b4cb933d7bca89d1b6e4e0474`
  - **Area**: JNI Tests, Protocol Verification
  - **Status**: Updated JNI smoke harness in `NativeBridge.java` to verify `RXT8` wire header.

- [x] **59. `c69128e`** - Bump Android build to 170
  - **Full Hash**: `c69128e18dfc6cb3fa8a36c1e550616b490d14b1`
  - **Area**: Versioning
  - **Status**: Bumped `versionCode = 170` in `app/build.gradle.kts`.

---

## Verification
- Rust Core (`rexling-core`): 50 passed, 3 ignored (6 suites).
- Rust JNI Bridge (`rexling-core/jni`): 107 passed, 16 ignored (2 suites).
- JNI Smoke Test (`rexling-android/scripts/test_jni.py`): PASSED (handles, worker, snapshot, Unicode, secret redaction, and cleanup).
- Android Application Unit Tests (`rexling-android`):
  - Unit tests: 324 passed, 1 skipped (`./gradlew testDebugUnitTest` SUCCESS).
  - Debug APK assembly: `BUILD SUCCESSFUL` (`./gradlew assembleDebug`).
