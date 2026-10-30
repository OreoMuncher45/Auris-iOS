#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
server=""
cleanup() { if [[ -n "$server" ]]; then kill "$server" 2>/dev/null || true; fi; rm -rf "$work"; }
trap cleanup EXIT
python3 "$repo/scripts/http-verify/fixture.py" "$work/config.json" > "$work/server.log" 2>&1 &
server=$!
for _ in {1..40}; do if [[ -f "$work/config.json" ]]; then break; fi; sleep 0.1; done
fw="$repo/AppleApp/Frameworks/AurisShared.xcframework/macos-arm64"
xcrun swiftc -parse-as-library -O -module-cache-path "$work/modules" -o "$work/verify" \
 -F "$fw" -framework AurisShared -Xlinker -rpath -Xlinker "$fw/AurisShared.framework/Versions/A" \
 "$repo/AppleApp/Sources/App/GuardedHTTP.swift" "$repo/scripts/http-verify/main.swift"
"$work/verify" "$work/config.json"
