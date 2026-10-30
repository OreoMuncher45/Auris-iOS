#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Compile the production queue model without pulling the entire UI into this harness.
python3 - "$ROOT" "$WORK" <<'PY'
import sys
from pathlib import Path
root, work = map(Path, sys.argv[1:])
(work/'QueueEntry.swift').write_text((root/'AppleApp/Sources/PlaybackSession/QueueEntry.swift').read_text())
PY
NATIVE="$ROOT/AppleApp/Frameworks/AurisCoreFFI.xcframework/macos-arm64"
SHARED="$ROOT/AppleApp/Frameworks/AurisShared.xcframework/macos-arm64"
xcrun swiftc -module-cache-path "$WORK/modules" -parse-as-library -o "$WORK/verify" \
 -F "$NATIVE" -framework AurisCoreFFI -F "$SHARED" -framework AurisShared \
 -framework CoreAudio -framework AudioToolbox \
 -Xlinker -rpath -Xlinker "$SHARED/AurisShared.framework/Versions/A" \
 "$ROOT/AppleApp/Generated/AurisCore.swift" "$WORK/QueueEntry.swift" \
 "$ROOT/AppleApp/Sources/PlaybackSession/DownloadStore.swift" \
 "$ROOT/AppleApp/Sources/PlaybackSession/LocalArtwork.swift" \
 "$ROOT/AppleApp/Sources/PlaybackSession/PlaybackDebugLog.swift" \
 "$ROOT/AppleApp/Sources/PlaybackSession/PlaybackRegionStore.swift" \
 "$ROOT/scripts/downloads-verify/main.swift"
"$WORK/verify"
