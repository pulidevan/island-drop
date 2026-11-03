#!/bin/bash
# Builds a universal (arm64 + x86_64) .app and a .dmg using plain swiftc + lipo — SwiftPM's own
# manifest step fails to link on a Command-Line-Tools-only install, so `swift build` is not used
# (Package.swift is kept for IDE indexing only). Ad-hoc signs with `codesign -s -`.
set -euo pipefail
cd "$(dirname "$0")"

SRC=(Sources/IslandDrop/*.swift)
BUILD=build
APP="$BUILD/Island Drop.app"
DIST=../dist

echo "→ Compiling arm64…"
mkdir -p "$BUILD"
swiftc -O -swift-version 5 -target arm64-apple-macosx14.0 -sdk "$(xcrun --show-sdk-path)" "${SRC[@]}" -o "$BUILD/IslandDrop-arm64"

echo "→ Compiling x86_64…"
swiftc -O -swift-version 5 -target x86_64-apple-macosx14.0 -sdk "$(xcrun --show-sdk-path)" "${SRC[@]}" -o "$BUILD/IslandDrop-x86_64"

echo "→ Combining into a universal binary…"
lipo -create -output "$BUILD/IslandDrop" "$BUILD/IslandDrop-arm64" "$BUILD/IslandDrop-x86_64"

echo "→ Assembling the .app bundle…"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BUILD/IslandDrop" "$APP/Contents/MacOS/IslandDrop"
cp Resources/Info.plist "$APP/Contents/Info.plist"
if [ -f Resources/AppIcon.icns ]; then
  cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
fi

# Bake the Google OAuth client into the app so users just click "Connect Google", if provided.
if [ -f google_client.json ]; then
  CID=$(python3 -c 'import json;d=json.load(open("google_client.json"));d=d.get("installed") or d.get("web");print(d["client_id"])')
  CSEC=$(python3 -c 'import json;d=json.load(open("google_client.json"));d=d.get("installed") or d.get("web");print(d["client_secret"])')
  /usr/libexec/PlistBuddy -c "Add :GoogleClientID string $CID" -c "Add :GoogleClientSecret string $CSEC" "$APP/Contents/Info.plist" 2>/dev/null || true
  echo "→ Google client built in"
fi

echo "→ Ad-hoc signing…"
codesign --force --deep -s - "$APP"

echo "→ Making the DMG…"
mkdir -p "$DIST"
rm -f "$DIST/IslandDrop.dmg"
hdiutil create -volname "Island Drop" -srcfolder "$APP" -ov -format UDZO "$DIST/IslandDrop.dmg" >/dev/null

echo "✓ Built: $APP"
echo "✓ Built: $DIST/IslandDrop.dmg"
