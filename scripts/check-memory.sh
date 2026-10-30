#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# Expose the real interpreter constructor to this harness without changing its
# bindings or adding test-only entry points to the app.
python3 - "$repo" "$work" <<'PY'
from pathlib import Path
import sys
repo, work = map(Path, sys.argv[1:])
source = (repo / 'AppleApp/Sources/PlaybackSession/ModuleJsEngine.swift').read_text()
assert 'private final class Engine {' in source
(work / 'ModuleJsEngine.swift').write_text(source.replace('private final class Engine {', 'final class Engine {', 1))
PY
native="$repo/AppleApp/Frameworks/AurisCoreFFI.xcframework/macos-arm64"
shared="$repo/AppleApp/Frameworks/AurisShared.xcframework/macos-arm64"
xcrun swiftc -O -disable-sandbox -parse-as-library -module-cache-path "$work/modules" -o "$work/verify" \
 -F "$native" -framework AurisCoreFFI -F "$shared" -framework AurisShared \
 -framework CoreAudio -framework AudioToolbox -framework JavaScriptCore \
 -Xlinker -rpath -Xlinker "$shared/AurisShared.framework/Versions/A" \
 "$repo/AppleApp/Generated/AurisCore.swift" "$work/ModuleJsEngine.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/LocalArtwork.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/LocalLibrary.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/LocalTrackOrder.swift" \
 "$repo/AppleApp/Sources/PlaybackSession/AutomixModelLoader.swift" \
 "$repo/scripts/memory-verify/main.swift"
MEMORY_FIXTURE="$repo/native-core/tests/fixtures/seek-tone.m4a" "$work/verify"
