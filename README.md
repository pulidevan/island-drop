# Island Drop

Drag-and-drop file transfer from Mac to Android/iPhone over local Wi-Fi.

- **Mac**: a notch-overlay menu bar app (`mac/`, Swift). Drag files onto the notch to send them
  to a nearby device.
- **Android**: a receiver/sender app (`android/`, Kotlin) that advertises itself over Bonjour/mDNS
  so the Mac can find it, and runs a tiny embedded HTTP server so an iPhone (no app required) can
  send/receive files through a browser page.

## Background

The original app's source was lost (deleted after being built with Claude Code). This is a
from-scratch reconstruction: the Android app was recovered with high fidelity by decompiling the
shipped `.apk` (jadx) and reading the recovered logic back into clean Kotlin; the Mac app's source
couldn't be recovered the same way (compiled Swift doesn't decompile to readable source), so it was
rewritten from the app's embedded strings, bundle metadata, and screen recordings of the original
in use.

## Protocol

Mac and Android speak a tiny custom protocol over raw TCP (no HTTP):
advertise over Bonjour/mDNS (`_islanddrop._tcp` for Android, `_islanddrop-mac._tcp` for Mac) ->
connect -> one line of JSON (`{"type":"files","sender":...,"files":[{"name","size","mime"}],"total":...}`
or `{"type":"link",...}`) terminated by `\n` -> receiver replies `ACCEPT\n`/`DECLINE\n` -> sender
streams raw file bytes back-to-back -> receiver replies `DONE\n`.

The iPhone has no native app; instead the Android app runs a minimal hand-rolled HTTP server
(`WebBridge`) on port 8765 serving a single token-gated page (`WebPage`) that can upload files via
XHR and polls for incoming ones.

## Building

### Android
```
cd android
./gradlew assembleDebug   # or `gradle assembleDebug` if you don't have the wrapper jar
```

### Mac
```
cd mac
swift build -c release
```
(See `mac/package.sh` for bundling the built binary into a proper `.app`.)
