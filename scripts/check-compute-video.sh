#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
native="$repo/AppleApp/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
xcrun swiftc -O -parse-as-library -module-cache-path "$work/modules" -o "$work/verify" \
 -F "$native" -framework NativeCoreFFI -framework CoreAudio -framework AudioToolbox \
 "$repo/AppleApp/Generated/NativeCore.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/MusicAnalysisQueue.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/AutomixOverlayCache.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/VideoSegmentIndex.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/VideoIndexCache.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/VideoRangeDelivery.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/VideoAudioDownloader.swift" \
 "$repo/scripts/compute-video-verify/main.swift"
"$work/verify"
