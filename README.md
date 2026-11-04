<div align="center">

# 🏝️ Island Drop

**A Dynamic Island in your MacBook's notch — plus an AirDrop-style bridge between macOS, Android, and iPhone over local Wi-Fi.**

*Built by **[Pulidevan Prabakaran](https://www.linkedin.com/in/pulidevan)** · Founder of **[AI Essentials](https://aiessentials.in)***

[![LinkedIn — Pulidevan Prabakaran](https://img.shields.io/badge/LinkedIn-Pulidevan_Prabakaran-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/pulidevan)
[![AI Essentials](https://img.shields.io/badge/AI_Essentials-aiessentials.in-FF5A1F?style=for-the-badge&logo=sparkles&logoColor=white)](https://aiessentials.in)
[![Pulidevan.com](https://img.shields.io/badge/Founder-pulidevan.com-111111?style=for-the-badge&logo=safari&logoColor=white)](https://pulidevan.com)
[![All Links](https://img.shields.io/badge/Hub-link.aiessentials.in-00C853?style=for-the-badge&logo=linktree&logoColor=white)](https://link.aiessentials.in)
[![AI Essentials LinkedIn](https://img.shields.io/badge/Company-AI_Essentials-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white)](https://www.linkedin.com/company/aiessentials/)

---

[![macOS 14+](https://img.shields.io/badge/macOS-14%2B_(Sonoma)-000000?style=flat-square&logo=apple&logoColor=white)](#install)
[![Android 10+](https://img.shields.io/badge/Android-10%2B_(API_29)-3DDC84?style=flat-square&logo=android&logoColor=black)](#install)
[![iPhone Safari](https://img.shields.io/badge/iPhone-QR_Web_%26_AirDrop_Relay-007AFF?style=flat-square&logo=apple&logoColor=white)](#iphone--android)
[![Zero Cloud](https://img.shields.io/badge/100%25_Local-Bonjour_%2B_Direct_TCP-30D158?style=flat-square)](#how-the-protocol-works)

</div>

---

## ✨ What is Island Drop?

**Island Drop** turns your MacBook's camera notch into a tactile, spring-animated **Dynamic Island** and connects your **Mac**, **Android phone/tablet**, and **iPhone** over your local Wi-Fi network — with zero cloud uploads, zero accounts, and zero configuration.

### 1. 💻 Mac → 📱 Android / AirDrop
Drag any file (or folder) toward the top notch of your MacBook:
- **AirDrop Tile (Left):** Opens the native macOS AirDrop sheet immediately.
- **Android Tile (Right):** Streams over local Wi-Fi straight to your Android phone or tablet. Your Android device wakes up and slides up an **AirPods-style bottom card** with live progress and instant preview/open actions.

### 2. 📱 Android → 💻 Mac
- On Android, tap **Share → Send to Mac** from *any* app, or tap your Mac directly from Android's **Direct Share** top row.
- Or open the **Island Drop** app and tap **Send Files to Mac**.
- On your Mac, the Dynamic Island drops down with a chime, file icon, and **Decline / ⭐ Accept & Remember**.
- Accepted files save straight to `~/Downloads`. Shared URLs automatically open in your Mac's default browser; plain text copies to your clipboard.
- **Favorites:** Accepting a device once remembers it so future transfers from your phone/tablet arrive without prompting.

### 3. 📲 iPhone ⇄ Android (Two Seamless Paths)
Because Apple's proprietary AWDL radio isn't exposed to third-party apps, Island Drop gives you two effortless ways to bridge iPhone and Android:
- **QR Web Transfer (No Mac Needed):** Tap **Connect iPhone** on Android (or **Share → Send to iPhone** from any app) and scan the QR code with the iPhone camera. Safari opens a native-feeling transfer page where you can upload photos/files from iPhone to Android (triggering the AirPods card) or download shared files onto the iPhone.
- **Mac Notch Relay:** AirDrop a file from your iPhone to your Mac as usual — `AirDropWatcher` detects the `sharingd` arrival in `~/Downloads` and the Dynamic Island automatically offers **Send to [Android Device]** (or forwards automatically when enabled in Settings). Conversely, files received on Mac from Android get a one-click **AirDrop** button in the notch to pass them on to your iPhone.

---

## 📦 Release Artifacts

```text
dist/IslandDrop.dmg   macOS 14+ Universal Binary (Apple Silicon arm64 + Intel x86_64)
dist/IslandDrop.apk   Android 10+ (API 29+)
```

---

## 🚀 Install & Setup

### macOS
1. Open `dist/IslandDrop.dmg` and drag **Island Drop** into `/Applications`.
2. Because the app is ad-hoc signed, right-click **Island Drop.app** and choose **Open** on first launch.
3. Allow **Local Network** access when macOS prompts so Bonjour discovery can see your devices.

### Android
Install `dist/IslandDrop.apk`, open it, and tap **Allow** on each setup row:

| Permission | Purpose |
| :--- | :--- |
| **Notifications** | Shows incoming transfer alerts and background receiver status |
| **Pop up over apps** | Lets the AirPods-style transfer card slide up over any app |
| **Wake on lock screen** | Turns the screen on and presents the card over the lock screen |
| **Always ready** | Exempts the local Wi-Fi receiver from battery-saver background kills |

*The foreground receiver service (`_islanddrop._tcp`) restarts automatically after device reboot.*

---

## 🎯 Using the Dynamic Island on Mac

- **Hover** over the notch to make it subtly expand.
- **Click** the notch to open the status panel and inspect connected devices.
- **Drag any file or folder:** The island peeks out with a **Drop** indicator, then expands into the **AirDrop** and **Android** drop tiles as your cursor approaches.
- **Live Activity Progress:** While transferring, a file icon sits on the left wing of the notch and a smooth progress ring animates on the right wing.
- **Auto-Zip Folders:** Dragging a folder or `.app` bundle automatically compresses it via `ditto` before sending.

---

## 🛠️ Architecture & Project Structure

```text
island-drop/
├── mac/                                  # macOS 14+ AppKit + SwiftUI Dynamic Island
│   ├── build.sh                          # Universal (arm64 + x86_64) swiftc + lipo + DMG builder
│   ├── scripts/make_icon.swift           # Programmatic 1024px macOS squircle icon generator
│   └── Sources/IslandDrop/
│       ├── main.swift & AppDelegate.swift
│       ├── IslandController.swift        # Non-activating NSPanel + AppKit NSDraggingDestination
│       ├── IslandModel.swift             # Spring state machine, job queue, AirDrop & Phone workflows
│       ├── IslandViews.swift             # Notch SwiftUI views, DropTile, IncomingView, ResultView
│       ├── Shapes.swift                  # Animatable concave/convex NotchShape & blur transitions
│       ├── PhoneLink.swift               # Bonjour browser (_islanddrop._tcp) & async TCP sender
│       ├── MacReceiver.swift             # Bonjour listener (_islanddrop-mac._tcp) & file receiver
│       ├── AirDropWatcher.swift          # FSEvents/DispatchSource watcher for sharingd AirDrops
│       └── Settings.swift & SettingsView.swift
├── android/                              # Android 10+ Pure Kotlin App (Zero bloat, ZXing QR only)
│   └── app/src/main/java/com/puli/islanddrop/
│       ├── ReceiverService.kt            # NSD service (_islanddrop._tcp) + TCP server
│       ├── DropCardView.kt               # Custom Canvas/Spring AirPods-style slide-up card
│       ├── Presenter.kt                  # Overlay window + full-screen lockscreen wakeup
│       ├── ShareActivity.kt              # Share -> Send to Mac sheet & MacBubble progress rings
│       ├── Shortcuts.kt                  # Android Direct Share dynamic targets for nearby Macs
│       ├── WebBridge.kt & WebPage.kt     # Embedded HTTP server & mobile web UI for iPhone QR link
│       ├── QrActivity.kt                 # Share -> Send to iPhone & QR code scanner screen
│       ├── MainActivity.kt               # Radar UI, permissions checklist & quick actions
│       └── Saver.kt, Notifs.kt, Transfer.kt
└── tools/                                # Automated verification & synthetic test harness
    ├── fake_phone.py                     # Mock Android Bonjour receiver
    ├── fake_sender.py                    # Mock Android-to-Mac sender
    ├── dragtest.swift                    # Real CoreGraphics/AppKit file drag session simulator
    └── click.swift                       # Synthetic CGEvent mouse click utility
```

---

## 🔨 Build from Source

No full Xcode install is required on macOS — **Command Line Tools** are sufficient:

```bash
# 1. Build macOS universal app + dist/IslandDrop.dmg
./mac/build.sh

# 2. Build Android release APK -> dist/IslandDrop.apk
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../dist/IslandDrop.apk
```

**Debug & Testing Flags (macOS):**
- `--demo [hover|peek|drop|working|result|open|toast]` — steps through all Dynamic Island animations and states.
- `--drop phone|airdrop <path>` — triggers a file transfer directly from the CLI without dragging.

---

## 🔌 How the Protocol Works

Both directions communicate over **plain local TCP sockets** discovered via **Bonjour / mDNS** (`_islanddrop._tcp` for Android receivers, `_islanddrop-mac._tcp` for Mac receivers):

```text
Sender   → Receiver : {"v":1,"type":"files","sender":"Name","files":[{"name":"…","size":N,"mime":"…"}],"total":N}\n
Receiver → Sender   : "ACCEPT\n" | "DECLINE\n"
Sender   → Receiver : <raw file bytes back-to-back matching exact header sizes>
Receiver → Sender   : "DONE\n"

Links / Text:
Sender   → Receiver : {"v":1,"type":"link","url":"https://…","title":"","sender":"Name"}\n
Receiver → Sender   : "OK\n"
```

Nothing ever touches external servers or the public internet.

---

## 👨‍💻 Author & Branding

<div align="center">

### **Pulidevan Prabakaran**
**Founder, [AI Essentials](https://aiessentials.in)** · Chennai, India

*8,000+ founders, marketers, creators, and teams upskilled in practical AI workflows, AI Agents & Automation, AI Realism Filmmaking, and Generative AI Full-Stack Engineering.*

| Platform | Link |
| :--- | :--- |
| 🔗 **Founder LinkedIn** | [linkedin.com/in/pulidevan](https://www.linkedin.com/in/pulidevan) |
| 🌐 **Founder Digital Clone & 1:1** | [pulidevan.com](https://pulidevan.com) |
| 🚀 **AI Essentials Official Website** | [aiessentials.in](https://aiessentials.in) |
| 🏢 **AI Essentials LinkedIn** | [linkedin.com/company/aiessentials](https://www.linkedin.com/company/aiessentials/) |
| 🎬 **AI Filmmaking & Realism** | [filmaking.aiessentials.in](https://filmaking.aiessentials.in) |
| 🧠 **GenAI Consultation & Programs** | [genai.aiessentials.in](https://genai.aiessentials.in) |
| 🤝 **AI Essentials Pro Community** | [community.aiessentials.in](https://community.aiessentials.in) |
| 📸 **Instagram (@aiessntl)** | [instagram.com/aiessntl](https://www.instagram.com/aiessntl) |
| ⚡ **Operator Hub (All Links)** | [link.aiessentials.in](https://link.aiessentials.in) |
| 💻 **GitHub** | [github.com/pulidevan](https://github.com/pulidevan) |

</div>
