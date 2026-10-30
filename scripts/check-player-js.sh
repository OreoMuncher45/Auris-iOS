#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fw="$repo/AppleApp/Frameworks/AurisShared.xcframework/macos-arm64"
xcrun swiftc -F "$fw" -framework AurisShared -Xlinker -rpath -Xlinker "$fw/AurisShared.framework/Versions/A" -module-cache-path "$work/modules" -O -o "$work/verify" \
 "$repo/AppleApp/Sources/App/YouTubePlayerJs.swift" \
 "$repo/AppleApp/Sources/App/YouTubeChallengeSolver.swift" "$repo/scripts/player-js-verify/main.swift"
"$work/verify" "$repo/AppleApp/Resources/YouTubeSolver" "$@"
