#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
fw="$repo/AppleApp/Frameworks/AurisShared.xcframework/macos-arm64"
xcrun swiftc -O -module-cache-path /tmp/auris-swift-module-cache -o "$scratch/verify" \
  -F "$fw" -framework AurisShared \
  -Xlinker -rpath -Xlinker "$fw/AurisShared.framework/Versions/A" \
  "$repo/AppleApp/Sources/PlaybackSession/SourceSubstitution.swift" "$repo/scripts/source-bridge-verify/main.swift"
"$scratch/verify"
