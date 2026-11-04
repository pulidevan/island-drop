#!/bin/bash
# Builds Island Drop.app and packages it into a DMG (no Xcode needed, just Command Line Tools).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
APP="build/Island Drop.app"
DIST="$ROOT/dist"
SDK="$(xcrun --show-sdk-path)"

echo "→ Compiling (arm64 + x86_64)"
mkdir -p build
for arch in arm64 x86_64; do
  swiftc -O -swift-version 5 -target $arch-apple-macos14.0 -sdk "$SDK" \
    Sources/IslandDrop/*.swift -o build/IslandDrop-$arch 2>&1 | grep -E ": error:" || true
  [ -f build/IslandDrop-$arch ] || { echo "build failed ($arch)"; exit 1; }
done
lipo -create build/IslandDrop-arm64 build/IslandDrop-x86_64 -output build/IslandDrop

echo "→ Bundling"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp build/IslandDrop "$APP/Contents/MacOS/IslandDrop"
cp Resources/Info.plist "$APP/Contents/Info.plist"

if [ ! -f build/AppIcon.icns ]; then
  echo "→ Rendering icon"
  swift scripts/make_icon.swift build/icon_1024.png
  ICONSET=build/AppIcon.iconset; rm -rf $ICONSET; mkdir $ICONSET
  for s in 16 32 128 256 512; do
    sips -z $s $s build/icon_1024.png --out $ICONSET/icon_${s}x${s}.png >/dev/null
    sips -z $((s*2)) $((s*2)) build/icon_1024.png --out $ICONSET/icon_${s}x${s}@2x.png >/dev/null
  done
  iconutil -c icns $ICONSET -o build/AppIcon.icns
fi
cp build/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

echo "→ Signing (ad-hoc)"
codesign --force --deep --options runtime --sign - "$APP"

echo "→ Creating DMG"
mkdir -p "$DIST"
STAGE=build/dmg; rm -rf $STAGE; mkdir -p $STAGE
cp -R "$APP" $STAGE/
ln -s /Applications $STAGE/Applications
rm -f "$DIST/IslandDrop.dmg"
hdiutil create -volname "Island Drop" -srcfolder $STAGE -ov -format UDZO "$DIST/IslandDrop.dmg" >/dev/null
echo "✓ $DIST/IslandDrop.dmg"
