# Island Drop

A Dynamic Island that lives in your MacBook's notch, plus an AirDrop-style link between your Mac and an Android phone or tablet.

**Mac → phone:** drag any file toward the notch and drop it on:
- **AirDrop**: opens the system AirDrop sheet.
- **Your Android device**: sent over Wi-Fi. The device wakes up and a card slides up, like AirPods pairing.

**Phone → Mac:** on Android, tap **Share → Send to Mac** in any app. Your Mac also shows up in the top row of the share menu. You can also tap **Send Files to Mac** in Island Drop. The island on the Mac drops down with **Decline / Accept**. Files save to `~/Downloads`, and shared links open in the browser.

```
dist/IslandDrop.dmg   macOS 14+ (Apple silicon + Intel)
dist/IslandDrop.apk   Android 10+
```

## Install

**Mac:** open the DMG and drag *Island Drop* into Applications. The app is ad-hoc signed, so the first time you open it, right-click it and choose **Open**. Allow **Local Network** access when macOS asks.

**Android:** install the APK (allow "install unknown apps"), open it, and tap every **Allow** under *Setup*:
| Setting | Why |
|---|---|
| Notifications | Accept/Decline alerts |
| Pop up over apps | The card slides up over any app |
| Wake on lock screen | Turns the screen on and shows the card on the lock screen |
| Always ready | Stops battery saver from killing the receiver |

The receiver starts again on its own after a reboot. Both devices must be on the same Wi-Fi.

## Using it

- **Hover** over the notch and it grows a little. **Click** it to open the panel.
- **Drag a file:** the island shows a "Drop" peek, then opens into two targets as you get close.
- **Sending:** a file icon on the left of the notch and a progress ring on the right, like a Live Activity.
- Folders are zipped automatically.

## Build from source

```bash
mac/build.sh                                              # → dist/IslandDrop.dmg  (Command Line Tools are enough)
cd android && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleRelease
```

Debug flags: `--demo [hover|peek|drop|working|result|open|toast]` steps through the UI states. `--drop phone|airdrop <file>` runs a drop without dragging.

## How the link works

Both directions use plain TCP on your local network, found with Bonjour/mDNS. The phone advertises `_islanddrop._tcp`; the Mac advertises `_islanddrop-mac._tcp`. The sender sends a JSON header line. The receiver shows its prompt and replies `ACCEPT` or `DECLINE`. The file bytes then stream across, and the receiver replies `DONE`. Nothing goes through the internet.
