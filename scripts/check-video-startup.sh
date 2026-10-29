#!/bin/bash
# A decoded-frame benchmark against a local H.264 fixture, with one fixed
# delay per HTTP request. Requires ffmpeg; does not contact YouTube or play sound.
# VIDEO_STARTUP_BASELINE_REF=<git-ref> also measures the previous loader.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
fixture_pid=""
cleanup() {
  if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
command -v ffmpeg >/dev/null || { echo 'This optional benchmark requires ffmpeg.' >&2; exit 2; }
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=1920x1080:rate=30 -t 4 \
  -c:v libx264 -threads 2 -preset veryfast -crf 28 -pix_fmt yuv420p -g 30 \
  -movflags +frag_keyframe+empty_moov+default_base_moof+global_sidx -an "$work/video.mp4"
python3 "$repo/scripts/video-startup-verify/fixture.py" "$work/video.mp4" "$work/port" > "$work/server.log" 2>&1 &
fixture_pid=$!
for _ in $(seq 1 100); do [[ -s "$work/port" ]] && break; sleep 0.05; done
[[ -s "$work/port" ]] || { cat "$work/server.log" >&2; exit 2; }
port="$(cat "$work/port")"
python3 - "$repo" "$work" <<'PY'
from pathlib import Path
import sys
repo, work = map(Path, sys.argv[1:])
source = (repo / 'AppleApp/Sources/PlaybackSession/VideoVersionAlignment.swift').read_text()
start = source.index('    struct Map:')
end = source.index('    private var maps:', start)
(work / 'Map.swift').write_text('import Foundation\nactor VideoVersionAlignment {\n' + source[start:end] + '\n}\n')
PY
common=("$repo/scripts/video-startup-verify/main.swift" "$work/Map.swift"
  "$repo/AppleApp/Sources/PlaybackSession/VideoSegmentIndex.swift"
  "$repo/AppleApp/Sources/PlaybackSession/VideoIndexCache.swift"
  "$repo/AppleApp/Sources/PlaybackSession/VideoRangeDelivery.swift"
  "$repo/AppleApp/Sources/PlaybackSession/VideoSyncPolicy.swift"
  "$repo/AppleApp/Sources/App/ResolvedVideoSide.swift")
if [[ -n "${VIDEO_STARTUP_BASELINE_REF:-}" ]]; then
  git -C "$repo" show "$VIDEO_STARTUP_BASELINE_REF:AppleApp/Sources/PlaybackSession/VideoSideController.swift" > "$work/VideoSideController.swift"
  git -C "$repo" show "$VIDEO_STARTUP_BASELINE_REF:AppleApp/Sources/PlaybackSession/ChunkedVideoLoader.swift" > "$work/ChunkedVideoLoader.swift"
  xcrun swiftc -O -disable-sandbox -parse-as-library -module-cache-path "$work/modules" -o "$work/baseline" \
    "${common[@]}" "$work/VideoSideController.swift" "$work/ChunkedVideoLoader.swift"
  "$work/baseline" "http://127.0.0.1:$port/video.mp4" baseline
fi
xcrun swiftc -O -disable-sandbox -parse-as-library -module-cache-path "$work/modules" -o "$work/current" \
  "${common[@]}" \
  "$repo/AppleApp/Sources/PlaybackSession/VideoSideController.swift" \
  "$repo/AppleApp/Sources/PlaybackSession/ChunkedVideoLoader.swift"
"$work/current" "http://127.0.0.1:$port/video.mp4" current
