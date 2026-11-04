# Island Drop: handoff for the next agent

Read this file first; it captures the state as of 2026-10-30. The user-facing overview is in `README.md`.

## What this project is

Two apps that move files between a MacBook, an Android tablet and an iPhone, all over the local Wi-Fi:

- **Mac app** (`mac/`, Swift/SwiftUI/AppKit): a Dynamic Island in the MacBook notch. Drag a file toward the notch and drop it on **AirDrop** or the **Android device**. It also receives files *from* the Android device, with an Accept/Decline prompt in the island, saving to `~/Downloads`.
- **Android app** (`android/`, Kotlin, no AndroidX): a background receiver. When the Mac sends, the screen wakes and an AirPods-style card slides up. It can also send: **Share → Send to Mac**, **Share → Send to iPhone**, plus buttons in the app.
- **iPhone**: no app. Real AirDrop can't be built into a third-party app (it needs Apple's AWDL Wi-Fi link), so there are two workarounds:
  1. **QR web transfer:** the Android app runs an HTTP server; the iPhone scans its QR code and gets a Safari page to upload and download.
  2. **Mac relay:** an iPhone AirDrops to the Mac → the island offers "Send to tablet". Files from the tablet get an **AirDrop** button on the Mac.

Outputs: `dist/IslandDrop.dmg` (macOS 14+, universal) and `dist/IslandDrop.apk` (Android 10+, debug-signed).

## The user

- Writes short, informal messages, often with typos. Interpret generously, and ask only when it's truly ambiguous.
- Wants Apple-quality UI and animation, and things that "just work" with no setup steps ("no JSON please").
- **Watches the screen and clicks prompts during tests.** Several "bugs" turned out to be the user clicking Accept/Decline/Open while an automated test ran. Check stack traces before assuming a bug.
- Devices: MacBook Pro with notch, "Pulidevan’s MacBook Pro", Mac IP 192.168.29.108. It also has an external LG display, which is the *main* screen. Android device: **Lenovo Legion Tab** (mDNS host `Android_W45XKG9L.local`, IP 192.168.29.119). Also has an iPhone.
- **Scrapped on purpose (don't bring back unless asked):** the Google Drive upload/share-link feature. It was fully removed from both apps, and old settings keys are wiped on launch in `Settings.swift`. A Drive API was turned on in the user's Google Cloud project `aparna-site-277`; it's unused now.

## Environment gotchas (important)

- **The project path ends with a space:** `/Users/puli/Documents/Island drop` (the folder name is `dynamic island` with a space after it). Always quote paths.
- **No Xcode,** only Command Line Tools. SwiftPM's manifest step fails to link, so **don't use `swift build`**. `mac/build.sh` calls `swiftc` directly for arm64 and x86_64, runs `lipo`, builds the .app bundle by hand, signs ad-hoc (`codesign -s -`), and makes the DMG with `hdiutil`. `Package.swift` exists but isn't used.
- Swift language mode 5 (`-swift-version 5`). Warnings about captured vars in Network callbacks are known and harmless; they run on serial queues.
- Java: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`. Homebrew's `gradle` is 9.x, which **can't** configure this AGP 8.11 project; always use `./gradlew` (Gradle 8.14.3). Android SDK at `~/Library/Android/sdk` (platform 36, build-tools 35/36). No emulator or system images are installed, and the tablet isn't on adb.
- `timeout` isn't on macOS; use a Python timer or a loop.
- **When killing the app, use `pkill -x IslandDrop`.** `pkill -f "Island Drop.app/..."` also kills any background shell whose command line contains that text (this killed a watcher loop once).
- `screencapture` coordinates: the built-in notch screen sits *left of* the main display. The island area is captured with `screencapture -x -R -1076,132,800,200`. The notch is 166×29 pt and its centre is x=-676 in global CG coordinates.

## Build, install, ship

```bash
# Mac → dist/IslandDrop.dmg and mac/build/Island Drop.app
"/Users/puli/Documents/Island drop/mac/build.sh"
pkill -x IslandDrop; rm -rf "/Applications/Island Drop.app"
cp -R "/Users/puli/Documents/Island drop/mac/build/Island Drop.app" /Applications/ && open "/Applications/Island Drop.app"

# Android → dist/IslandDrop.apk
cd "/Users/puli/Documents/Island drop/android" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease --no-daemon -q
cp app/build/outputs/apk/release/app-release.apk ../dist/IslandDrop.apk

# Push a new APK to the tablet through the island itself (no adb needed):
pkill -x IslandDrop; open -a "/Applications/Island Drop.app" --args --drop phone "/Users/puli/Documents/Island drop/dist/IslandDrop.apk"
# The user then installs it from Files → Downloads → Island Drop (the app's own "Open" can't install APKs).
# Check the tablet runs the newest build: nc -z -G 3 192.168.29.119 8765   (the QR web server exists only in the newest APK)
```

The installed copy in `/Applications` is the latest build. The tablet was confirmed running the latest APK at the end of the session.

## Mac app: `mac/Sources/IslandDrop/`

| File | Role |
|---|---|
| `main.swift`, `AppDelegate.swift` | Accessory app (no Dock icon) with a menu bar icon. Starts `IslandController`, `PhoneLink`, `MacReceiver`, `AirDropWatcher`. Debug flags: `--demo [hover|peek|drop|working|result|open|toast]` and `--drop phone|airdrop <file>`. |
| `IslandController.swift` | Borderless non-activating `NSPanel` (760×330) above the menu bar, top-centre of the notch screen. Polls the mouse at 30 Hz plus event monitors, and toggles `ignoresMouseEvents`. Detects system-wide file drags via `NSPasteboard(name: .drag).changeCount`. **Drops are handled by `IslandDropView` (AppKit `NSDraggingDestination`)**, which maps the cursor to the left half (AirDrop) or right half (phone). SwiftUI `.onDrop` did NOT work in this panel; don't go back to it. The panel stays interactive for the whole drag. |
| `IslandModel.swift` | State machine: `idle, hover, dragPeek, working, dropTargets, open, result, message, incoming, forward`. Sizes and corner radii per state. Spring animations in `Motion`. A serial job queue (`enqueue`). Sending to the phone, AirDrop via `NSSharingService`, the incoming prompt (`askIncoming`/`respondIncoming`), the AirDrop relay (`airDropArrived`/`respondForward`/`airDropReceived`), toasts, auto-collapse, folder zipping (`ditto`). `resultSettled` ignores result-button clicks unless a fresh mouse-down happened after the result appeared. |
| `IslandViews.swift` | SwiftUI for every state, built from black `NotchShape` with blur-fade-scale transitions. Contains `DropTile`, `ResultView`, `HomeView`, `ToastView`, `IncomingView` (also used for the AirDrop forward offer), `PillButton`, `ProgressRing`. |
| `Shapes.swift` | `NotchShape` (animatable concave top / continuous bottom corners), the blur transition, the AirDrop system icon. |
| `PhoneLink.swift` | Bonjour browser for `_islanddrop._tcp` (Android receivers) plus `sendFiles`. `LineConnection` wraps `NWConnection`: async send, line reads, raw chunks. |
| `MacReceiver.swift` | `NWListener` advertising `_islanddrop-mac._tcp` with the computer name. Receives files from Android into `~/Downloads` (unique names) and links (opens http(s), copies text). |
| `AirDropWatcher.swift` | Watches `~/Downloads`. After a 1.5 s settle, any new file whose `com.apple.quarantine` contains `sharingd` counts as an AirDrop arrival → forward offer. **Unconfirmed against a real iPhone AirDrop**; the user was asked to try one. |
| `Settings.swift`, `SettingsView.swift` | `AppSettings`: preferredPhone, expandOnDrag, autoAcceptFromPhone, autoForwardAirDrop, **favorites**. Settings window: phone picker, favorites list, launch at login. |

**Favorites:** accepting an incoming transfer once (button "⭐ Accept & Remember") adds the sender's *name* to `favorites`, and later transfers from that name skip the prompt. Matching is by name only; it's unauthenticated and spoofable on the LAN. The user knows, and stricter pairing was offered as a later option.

## Android app: `android/app/src/main/java/com/puli/islanddrop/`

| File | Role |
|---|---|
| `ReceiverService.kt` | `connectedDevice` foreground service, `START_STICKY`, starts at boot. TCP server advertised via NSD as `_islanddrop._tcp` (device name). Receives from the Mac. Also starts `WebBridge` and a `MacFinder`, which publishes Direct Share shortcuts. |
| `Transfer.kt` | Shared transfer model + listeners, `Prefs` (auto_accept), `deviceName()`. |
| `Presenter.kt` | How a transfer appears: overlay window (needs "display over other apps") when unlocked; otherwise a full-screen-intent notification → `PopupActivity` over the lock screen. Wakes the screen. |
| `DropCardView.kt` | AirPods-style bottom card (spring slide-up, swipe to dismiss, file-type icon, progress, done check, thumbnails). Also `SpringInterpolator`, `FileIconView`, `ProgressLine`, `CloseButton`. |
| `ShareActivity.kt` | **Share → Send to Mac**: `Outgoing` parsing, `MacFinder` (NSD discovery of `_islanddrop-mac._tcp`), `MacSender`, and the AirDrop-like sheet with `MacBubble` progress rings. A Direct Share shortcut ID (`mac:<name>`) sends to that Mac straight away. |
| `Shortcuts.kt`, `res/xml/shortcuts.xml` | Direct Share targets, so each Mac appears in the top row of the share menu. |
| `WebBridge.kt`, `WebPage.kt` | iPhone link: HTTP server on port 8765 (falls back to random). Every request needs `?k=<token>` from the QR code. Endpoints: `/` page, `/files` JSON outbox, `/file?id=` download, `POST /upload?name=` raw body → Downloads, which also shows the card. The outbox is copied into `filesDir/outbox`. |
| `QrActivity.kt` | **Share → Send to iPhone** target and the "Connect iPhone" screen: a QR code (zxing core 3.5.3, the only dependency) plus the outbox status. |
| `MainActivity.kt` | Status + radar animation, permission rows (notifications, overlay, full-screen intent, battery), auto-accept-from-Mac switch, buttons: Send Files to Mac, Connect iPhone, Preview Pop-up, Open Received Files. |
| `Saver.kt`, `Notifs.kt` | MediaStore saving to `Downloads/Island Drop`; notification channels, actions, and boot receiver. |

## Wire protocol (both directions, plain TCP)

```
sender → receiver : one JSON line {"v":1,"type":"files","sender":NAME,"files":[{name,size,mime}],"total":N}
receiver → sender : "ACCEPT\n" | "DECLINE\n"
sender → receiver : raw bytes of every file back to back (exact sizes from the header)
receiver → sender : "DONE\n"
Link/text: {"v":1,"type":"link","url":TEXT,"title":"","sender":NAME} → "OK\n"
```
The Mac → Android listener is `_islanddrop._tcp`; the Android → Mac listener is `_islanddrop-mac._tcp`.

## Test tools (`tools/`)

- `fake_phone.py <outdir>`: pretends to be the Android receiver (advertises via `dns-sd`, accepts, saves). Use it with `--drop phone`.
- `fake_sender.py <file>`: pretends to be the tablet sending to the Mac (sender name "Test Device"). If you use it, remove "Test Device" from favorites afterwards (`defaults write com.puli.islanddrop favorites -array` while the app is stopped).
- `dragtest.swift <file> <x> <y> [holdSec]` (compile with `swiftc -O`): performs a **real** file drag session onto screen coordinates. The drop tiles are at y≈205: AirDrop x≈-760, phone x≈-590.
- `click.swift <x> <y>`: synthetic click (for example on the island's Accept button).
- To see the iPhone page without a phone, serve `WebPage` HTML from a small mock and screenshot it with headless Chrome at width ≥500 (Chrome's minimum window width).

## Verified vs not

**Verified on this Mac:**
- Mac → Android transfer (byte-identical, against a fake receiver and against the real tablet).
- Android → Mac receive, Accept & Remember, and auto-accept for favorites.
- Real drag-and-drop onto tiles.
- The AirDrop forward offer (with a simulated quarantine mark).
- The iPhone page rendering and its JS syntax.
- Sending the APK to the real tablet.

**Not verified:**
- The tablet-side UI on the device: pop-up card, Send to Mac sheet, QR screen, Direct Share row, lock-screen wake.
- A real iPhone using the QR page.
- A real AirDrop arrival triggering the relay.
- The share menu on Lenovo's ZUI. The user couldn't find the app earlier because the tablet ran an old APK; it's updated now.

## Possible next steps (not requested yet)

- Real pairing: a per-install device ID plus a shared key, instead of trusting by name.
- A favorites/trust list on Android too (it currently only has a global "auto-accept from my Mac").
- Code-sign/notarize the Mac app (needs an Apple Developer ID); add `REQUEST_INSTALL_PACKAGES` so received APKs can install from the card.
