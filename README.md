# <img src="rexling-android/app/src/main/res/drawable-nodpi/rexlink_dinosaur.png" alt="Rexling app icon" width="40" height="40" align="absmiddle"> Rexling

Unofficial native Android client for Superlogical Rex.
Connect directly to your authenticated Rex server over your private Tailscale network.
Kotlin/Compose supplies the UI; Rust handles the Rex protocol and JNI; libghostty-vt
maintains terminal state. No custom bridge, proxy or Rex subprocess is required.

| Light theme | Dark theme |
| :---: | :---: |
| <img src="docs/images/rexlink-android.png" alt="Rexling light theme showing a Codex session, pane indicator, tabs, and terminal keyboard controls" width="340"> | <img src="docs/images/rexlink-android-dark.png" alt="Rexling dark theme showing a Codex session, pane indicator, tabs, and terminal keyboard controls" width="340"> |

```text
rexling/
├── rexling-android/       Android Studio / Gradle app, UI, rendering and input
└── rexling-core/          Rust workspace, shared protocol and terminal implementation
    ├── src/              rexling-core crate
    ├── jni/              rexling-jni crate; exports the existing librex_android ABI
    ├── probe/            original rex-probe / rex-view investigation CLI and evidence
    ├── native/           Ghostty shim, pinned source manifest and MIT license
    └── scripts/          native build helper
```

The probe keeps its original investigation implementation and evidence for protocol
experiments. The app uses the maintained `rexling-core` crate, through `rexling-jni`.
The crate alias inside JNI remains `rex_core`; Android's package `dev.shulhi.rex`
and native-library name are unchanged, preserving installed app data and updates.

## Install on Android

Rexling currently supports **64-bit ARM phones running Android 9 or newer**,
including the Galaxy S22+. You need access to an existing Rex server. Install
and connect Tailscale on your phone when the server is on a private tailnet.
Rexling uses that existing VPN connection; it does not embed another VPN.

Obtain the release APK from the project maintainer, or build it below. Open the
APK on your phone and allow installation from that file/browser app when Android
asks. Alternatively, enable USB debugging, connect and authorize your device,
then install with Android SDK platform-tools:

```sh
adb devices -l
adb -s <your-device-serial> install -r "Rexling-<version>-build<code>-<commit>-arm64-release.apk"
```

Open **Rexling**, enter your Rex server's authenticated private HTTPS URL, and
connect. Add other hosts under **⋮ → Servers**. Keep Tailscale connected and
ensure Rexling is included in its VPN routing. The app does not configure a Rex
server or change its authentication. APKs are not yet published to an app store.

## Build and install from source

```sh
git clone https://github.com/shulhi/rexling.git
cd rexling
```

The repository is hosted at [shulhi/rexling](https://github.com/shulhi/rexling).


Install Android Studio, Android SDK platform 37, Build Tools 36.0.0, NDK
30.0.16248370, Rust 1.93 or newer, Python 3.9+, and curl. Android Studio's bundled
JDK 25 was used for validation. See [BUILDING.md](BUILDING.md) for setup, host
tests, debug builds, device installation and platform details.

From this repository root:

```sh
./rexling-android/scripts/gradle.sh :app:assembleRelease
```

For a shareable APK whose filename includes the date, build number and commit,
run `./rexling-android/scripts/gradle.sh :app:shareRelease`. The APK and SHA-256
checksum are written to `rexling-android/dist/`.

To install the APK you just built:

```sh
adb devices -l
adb -s <your-device-serial> install -r \
  rexling-android/app/build/outputs/apk/release/app-release.apk
```

The build downloads checksum-verified Ghostty and Zig, compiles the Rust JNI
library for Android arm64, and builds the APK. The first build needs network
access; later builds reuse ignored local caches. No old sibling checkout is needed.

```text
rexling-android/app/build/outputs/apk/release/app-release.apk
```

Open **rexling-android/** in Android Studio, rather than the repository root.
Release builds are optimized and non-debuggable. This prototype currently uses
the development signing identity; see the build guide before publishing a release.

## Run

Keep Tailscale connected on the phone and enter your Rex server's private HTTPS
address. Add hosts under **⋮ → Servers**. The header cycles through sessions
across connected hosts; the terminal card switches panes. Both loop at the ends.
A single fixed pane indicator stays at the bottom of the terminal card. Its
active capsule transfers width as you swipe; the pane's icon and program name
appear directly above it without moving the terminal content.
Rexling saves the last session, tab and pane for each server, including the last
pane used in each tab, and restores them when reopened. Closed terminals fall
back to an existing pane.
Check **⋮ → Preferences → About Rexling** for the date-based app version, build number and
commit; **Copy build info** copies these details for bug reports.
The Sessions drawer provides compact, searchable server groups without a second
session carousel above it. Activity uses the same compact drawer styling.
Unseen finished tasks, input requests and failures light the Activity dot;
**Mark seen** clears a report without opening its terminal. Reports for the pane
you are actively viewing are acknowledged automatically, so they do not light
the eye badge. Other panes and tabs retain their unread indication. Activity uses a solid
blue bar for finished tasks, a blue bar with a moving highlight for working tasks, pulsing amber for
attention and red for failures; seen reports are muted.

The bottom dock switches tabs and opens Activity. Its tab/pane button opens a
switcher directly: tap **Tabs** or **Panes**, or swipe between them. **Panes**
shows a map of the current layout on the server, including nested splits and
their proportions. Small vector program icons identify panes in the compact map. Tapping a
tile or row selects a target inside the panel; **Open pane** navigates to it.
Floating panes have dashed outlines. **Hide floating** reveals panes underneath;
**Show floating** restores the overlays. This changes only the map, and floating
panes stay available in the list. The compact header combines section switching
and Close; **Open pane** and **New pane** sit together below the list. **New pane**
offers **Split right** and **Split below** in a compact direction menu.
The Tabs section uses the same selection and **Open tab** pattern. A “Viewing”
caption identifies the currently open tab/pane independently of the selection.
**Split right** and **Split below** act on the selected target, without first
opening it, and show a preview of the new half. These
split the selected pane on the server; the phone continues showing one pane at
a time. Use **New tab** or **New pane** in its section, or close an item with
its × button and confirmation. Panes lists the panes in the current tab.
Hold and drag a row in either section to reorder it; hold near an edge to scroll
farther. Reordering does not open or select the dragged item. Tab order syncs
through Rex. Pane sorting moves programs through the server's existing layout
positions, preserving split sizes and floating-layer bounds. The layout map and
pane swipe order follow the updated server arrangement. Move up/down actions are
also available through accessibility controls.
Tabs fit their icon and title up to a width cap, giving the active title
priority. Short titles leave room for others; inactive tabs become compact
icons when space is tight. A single tab can use all the space before the
tab/pane menu button, showing its full title when it fits.
The strip scrolls when the tabs cannot fit; the switcher
keeps their longer titles available.
When space is limited, titles nearest the active tab take priority, with more
distant tabs collapsing to icons first.
On reconnect, tabs reuse their last known titles and icons while fresh metadata
loads. The tab strip waits for the terminal and tab metadata before appearing,
keeping its space reserved while loading. New panes use a neutral loading tile
instead of temporary window IDs or an assumed shell icon. **⋮ → Toggle Dark theme / Toggle Light theme**
quickly switches between your saved light and dark presets; **Preferences →
Appearance** also offers the system setting.
Hold a tab and drag it left or right to
rearrange it; hold near an edge to scroll farther. The order syncs with other Rex
clients. Hold and release without dragging for **Move left**, **Move right** and
**Close tab**. Rename a session from its row’s
**⋮ → Rename session** in the session list; changes sync through Rex.
Long-press Ctrl for common control shortcuts, including **Ctrl+J** and **Ctrl+]**.
The arrow joystick sits immediately before Enter in the keyboard row. Hold it and slide toward
an arrow to send one step; holding for 500 ms starts a slower repeat every
160 ms. Its overlay uses a moving thumb dot and directional highlight, stays
centered on the button, and uses a compact ring when
the keyboard is hidden. Double-tap the joystick to open the arrow
popup for precise taps or held keys; a single tap on the trigger closes it.
PgUp and PgDn occupy the upper corners beside Up, with a slower held repeat.
Turn off **Preferences → Keyboard → Use joystick for arrows** to use a single
tap for the popup instead, with joystick gestures disabled.
With joystick enabled, a drag switches back to steering. Returning to the center pauses
repeat, and releasing stops immediately. The gesture stays captured so a right
slide does not trigger back or pane navigation. Enter has a dedicated key, and
swiping right on the key row reveals the chevron to the left of Esc for
F1–F12 and navigation keys. Closing the extra keys hides the chevron again.
Under **Preferences → Keyboard → Ctrl shortcuts**, choose **Hold** (the default)
or **Double tap** to open the list. When closed, single taps toggle Ctrl
immediately. Tapping Ctrl while its popup is open closes it and clears Ctrl.
Neovim panes have a draggable, low-profile circular button for saved key shortcuts. Its
position is remembered and stays inside the terminal when its size changes.
Choose **Circular** (the default) or **Grid** in the Neovim shortcut preferences.
The circular layout is a segmented wheel with up to eight commands per page.
Choose **Fan out**, **Spin**, **Expand** or **None** under **Preferences → Keyboard → Neovim
shortcuts → Opening effect**. Fan out reveals segments clockwise in a wave;
Spin rotates the wheel into place, while Expand grows it outward from the
center. None opens and closes immediately. The choice is saved, and commands
remain usable during opening. The center stays anchored for every effect.
Older Shutter selections automatically switch to Expand.
Labels follow the ring; the center previews the highlighted command. A narrow
gap separates the ring from the center, which shows Close when idle. Hold the
button, slide to a segment and release to run it once. Returning to the center
cancels the selection. Tap to leave the menu open for repeated commands.
Swipe up/down starting on the outer ring to change pages, looping in either
direction. Tap the center to go Back from a group or close the root wheel.
Drag the closed button to reposition it. Its resting position leaves room for
the wheel at screen edges, so opening it never moves the button. The wheel can
overlap the header and bottom bars while staying in the visible app area. Grid mode shows six shortcuts per page and keeps its
existing vertical paging.
There is no fixed limit on the number of saved shortcuts.
It stays open after selecting shortcuts and ignores outside taps; use Close to
dismiss it. Circular mode has optional selection haptics, enabled by default,
with a selection tick when you move into a different command. Disable **Selection
haptics** in the same Neovim shortcut preferences if desired. The arrow joystick
also ticks on direction changes, with no repeated vibration while holding an
arrow. Both follow the phone’s system touch-vibration settings.
Optional group headers such as `[Git]` put following shortcuts inside a subtly
shaded group segment (or a pill in Grid mode). Tap it to show its shortcuts; Back returns
to the main menu. Use `[]` to return to ungrouped entries in the editor. Groups
have one level, and both the main menu and groups support vertical paging.
The defaults include `:w` and `:q`, which run immediately. Edit **Preferences →
Keyboard → Neovim shortcuts** with one
shortcut per line, optionally named as `LazyGit = <Space>lg`. Shortcuts start in
Normal mode; key notation includes `<Esc>`, `<CR>`, `<Tab>`, arrow keys and
`<C-x>`. Use your actual leader key, such as `<Space>`. Bare colon commands get
Enter automatically; other key sequences are sent as configured. The preference
toggle hides the button without deleting shortcuts; an empty list also hides it.
Swipe up/down on the top-bar program icon to switch to the next/previous
session, looping across connected servers like the horizontal session strip.
The icon follows the vertical gesture and springs back without moving the header text.
Tap the input cursor row in Codex or Claude to open the keyboard, including when
the program uses terminal mouse reporting. Other application clicks keep their
normal behavior.
The selected pane streams a Ghostty
snapshot followed by incremental VT output. Opening a pane claims it once when
ready; viewing another client's pane shows a contextual takeover notice. Manual
Take/Release remain in the overflow menu. Input is paused during reconnection and
uncertain input is never replayed.

### Create a session

Choose **⋮ → New session**, or tap **New session** in the session drawer.
The dialog shows the target server; give the session a name or leave it blank
for Rex to generate one. An empty connected server also shows a create button.

### Image attachments

Updated Rex servers (API revision 11+) receive images directly. Copy an image
and use **Paste** from your keyboard or Rexling's menu, or choose **⋮ → Attach
image**. PNG, JPEG, GIF and WebP images are streamed
with progress and cancellation, up to 100 MiB and the server's own limits.
Progress appears in a compact bottom-right pill, briefly turns green on success,
then disappears automatically.

Rex stores the file on the terminal's host and inserts its path into the original
pane without pressing Enter. Switching panes during an upload does not change
its destination. Rex manages storage and removes older terminal uploads as needed.
If confirmation is lost while finishing, check the original pane before trying
again; Rexling does not automatically repeat an upload with an uncertain result.

Image attachments require a Rex server with API revision 11 or newer. Update
older servers to enable uploads; there is no separate upload service to configure.

Keep Tailscale connected. On Android 17+, allow local network access when asked.

### Experimental Codex attention

**Preferences → Experimental → Codex title attention** is off by default. When
on, Rexling treats Codex’s explicit `[!] Action Required` title (including its
animated bracket variant) as Needs you if the structured report remains Working.
It is limited to the same verified Codex process and root task. This workaround
depends on Codex’s title format; Claude and explicit Done/Error reports keep their
normal status behavior.

### Background alerts

Enable **⋮ → Preferences → Background alerts** to receive task notifications while the app
is backgrounded or the phone is locked. Allow notifications when Android asks.
A quiet ongoing notification shows monitoring status and a **Stop** action.
Finished tasks, input/approval/sign-in requests and errors alert. Running and idle
tasks do not. Existing completions are baselined when monitoring starts, so they
are not replayed as new notifications. Foreground updates are consumed without
delayed alerts. Tapping an alert opens its server, session and pane. Terminal control is
released and rendering pauses in the background; monitoring is read-only.

Monitoring stops when you remove Rexling from Recents, force-stop it, or tap Stop.
It does not start at boot or require server-side push. Network loss, Android Doze
and device battery management can delay alerts; instant delivery is not guaranteed.

See [Android usage](rexling-android/README.md),
[protocol findings](rexling-android/PROTOCOL.md), and
[validation history](rexling-android/VALIDATION.md).

## Project status

Rexling is maintained on a **best-effort basis**. Support, updates, bug fixes and
compatibility with future Rex versions are not guaranteed. Use it at your own
risk: it is provided **AS IS, without warranties or conditions of any kind**.
The warranty disclaimer and limitations of liability are set out in the
[Apache 2.0 license](LICENSE).

## History and license

Both original repositories' histories are retained without rewriting their commit
IDs. See [HISTORY.md](HISTORY.md) for provenance and history-navigation commands.

Copyright 2026 Shulhi Sapli and contributors. Project code is licensed under
[Apache License 2.0](LICENSE). Third-party code, fonts, logos and sourced assets
retain their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Rexling is an independent, unofficial client and is not an official Rex product.

### Floating panes on the phone

Enable **Preferences → Panes → Minimize floating panes** to keep floating panes in a compact icon dock at the bottom right. They are excluded from pane swipes. A single floating pane opens directly from its icon. Multiple floating panes use a stacked icon: tap it to fan their icons and titles upward, and tap it again to collapse the list. The stack is highlighted while a floating pane is open. Tap an item to open its terminal; tap the active item again or press Back to minimize it and return to the last tiled pane in that tab. Floating panes remain available in the pane drawer. This preference is off by default and does not change the server’s layout.

### Server icons

Open **Servers → ⋮ → Edit → Icon** to choose Globe, Server, Cloud, MacBook, Mac mini, Mac Studio or Desktop, then save. The icon is stored on this phone and shown beside that server in the server list, session header and drawer, and Activity groups. Existing servers default to Globe.

### Rename and arrange terminals

In the **Tabs/Panes** drawer, use a row’s **⋮** menu to rename a tab or pane. Clear its name to restore the automatic title. Custom pane names stay visible while the program changes its terminal title.

A docked pane’s menu offers **Float pane**. A floating pane’s menu offers **Dock pane**, where you choose an existing docked pane in that tab and split right or below. Rex requires a docked anchor, so Rexling keeps at least one docked pane in each tab. These actions update Rex’s layout and labels without navigating to the edited terminal.

### Program icons

Rexling ships 155 vector program tiles and recognizes executable aliases for tools such as Yazi, Docker/Podman, Kubernetes, GitHub/GitLab CLI, Cargo, Go, Homebrew, PostgreSQL/SQLite, Ollama, Gemini, OpenCode and Antigravity. Icons follow Rex’s executable metadata, including full paths and interpreter wrappers; custom tab/pane titles do not change program identity. Unknown programs keep the terminal fallback.
