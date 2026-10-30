#!/usr/bin/env bash
# native-core -> AurisCoreFFI.xcframework + UniFFI-generated Swift bindings
# (spec §0/§2/§7). Kotlin bindings are emitted too, but are the plain-JVM
# flavour — placeholder only until the KMP-aware route is wired at milestone 5.
#
# The framework bundle is named AurisCoreFFI (not NativeCore) because a
# `framework module` in a modulemap must match its bundle name, and the
# generated Swift imports the FFI module `AurisCoreFFI`.
# Building native-core also builds bundled libopus; install CMake and put it on
# PATH before running this script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/native-core"
# Cursor (and some CI sandboxes) redirect CARGO_TARGET_DIR; UniFFI bindgen and
# the xcframework copy below read native-core/target/… so force the local dir.
unset CARGO_TARGET_DIR
# Match AppleApp/project.yml deployment targets (iOS 18, macOS 15) — avoids
# "was built for newer macOS version (26.5) than being linked (15.0)" warnings
# and keeps the Rust standard library's darwin thread-parking QoS aligned.
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-15.0}"
export IPHONEOS_DEPLOYMENT_TARGET=18.0
export IPHONESIMULATOR_DEPLOYMENT_TARGET=18.0

# iPhone-only release: the shipped xcframework carries the iOS device slice
# (plus the simulator slice when asked for). The macOS slice is not shipped,
# but the aarch64-apple-darwin host build is still required: UniFFI bindgen
# reads the library from the host to extract the interface metadata.
#
# NATIVE_TARGETS overrides the whole set. Use
#   NATIVE_TARGETS="aarch64-apple-darwin aarch64-apple-ios" for a device-only
# build (no simulator slice, ~1/3 less build time and disk).
read -r -a TARGETS <<< "${NATIVE_TARGETS:-aarch64-apple-darwin aarch64-apple-ios aarch64-apple-ios-sim}"

# Targets that end up inside the shipped xcframework. The macOS slice is
# excluded: this release is iPhone-only.
SHIPPED_TARGETS=()
for t in "${TARGETS[@]}"; do
  [ "$t" = "aarch64-apple-darwin" ] && continue
  SHIPPED_TARGETS+=("$t")
done


for t in "${TARGETS[@]}"; do
  cargo build --release --target "$t"
done

# UniFFI publishes no standalone bindgen CLI crate — the official route is the
# in-crate `uniffi-bindgen` bin (native-core/uniffi-bindgen.rs), which also
# guarantees the bindgen version matches the uniffi crate exactly.
BINDGEN=(cargo run --release --quiet --features uniffi/cli --bin uniffi-bindgen --)

BINDINGS="$ROOT/native-core/bindings"
rm -rf "$BINDINGS/swift" "$BINDINGS/kotlin"
mkdir -p "$BINDINGS/swift" "$BINDINGS/kotlin"

# Bindings are target-independent; generate from the host build.
HOST_LIB="target/aarch64-apple-darwin/release/libnative_core.a"
if [ ! -f "$HOST_LIB" ]; then
  HOST_LIB="target/release/libnative_core.a"
fi
"${BINDGEN[@]}" generate --library "$HOST_LIB" --language swift --out-dir "$BINDINGS/swift"
"${BINDGEN[@]}" generate --library "$HOST_LIB" --language kotlin --out-dir "$BINDINGS/kotlin" || \
  echo "warning: Kotlin binding generation failed (non-fatal at milestone 1)" >&2

# Normalize crate-name casing to NativeCore / AurisCoreFFI. Replace every
# occurrence of the FFI module name — the generated file also contains a
# case-sensitive canImport() guard, not just the import line.
SWIFT_SRC="$(ls "$BINDINGS/swift/"*.swift | head -1)"
HEADER_SRC="$(ls "$BINDINGS/swift/"*.h | head -1)"
FFI_MODULE="$(basename "$HEADER_SRC" .h)"
mv "$SWIFT_SRC" "$BINDINGS/swift/AurisCore.swift"
mv "$HEADER_SRC" "$BINDINGS/swift/AurisCoreFFI.h"
sed -i '' "s/$FFI_MODULE/AurisCoreFFI/g" "$BINDINGS/swift/AurisCore.swift"

MODULE_MAP='framework module AurisCoreFFI {
    umbrella header "AurisCoreFFI.h"
    export *
    module * { export * }
}'

STAGE="$ROOT/native-core/build-xcframework"
rm -rf "$STAGE"
CREATE_ARGS=()

platform_dir_for() { case "$1" in aarch64-apple-darwin) echo macos ;; aarch64-apple-ios) echo ios ;; *) echo ios-simulator ;; esac; }
min_os_for()       { case "$1" in aarch64-apple-darwin) echo 15.0 ;; *) echo 18.0 ;; esac; }
platform_name_for(){ case "$1" in aarch64-apple-darwin) echo MacOSX ;; aarch64-apple-ios) echo iPhoneOS ;; *) echo iPhoneSimulator ;; esac; }

for t in "${SHIPPED_TARGETS[@]}"; do
  FW="$STAGE/$(platform_dir_for "$t")/AurisCoreFFI.framework"

  if [ "$t" = "aarch64-apple-darwin" ]; then
    # macOS frameworks need the versioned layout for module resolution.
    CONTENT="$FW/Versions/A"
    mkdir -p "$CONTENT/Headers" "$CONTENT/Modules" "$CONTENT/Resources"
    cp "target/$t/release/libnative_core.a" "$CONTENT/AurisCoreFFI"
    cp "$BINDINGS/swift/AurisCoreFFI.h" "$CONTENT/Headers/AurisCoreFFI.h"
    printf '%s\n' "$MODULE_MAP" > "$CONTENT/Modules/module.modulemap"
    ln -s A "$FW/Versions/Current"
    ln -s Versions/Current/Headers "$FW/Headers"
    ln -s Versions/Current/Modules "$FW/Modules"
    ln -s Versions/Current/Resources "$FW/Resources"
    ln -s Versions/Current/AurisCoreFFI "$FW/AurisCoreFFI"
    PLIST="$CONTENT/Resources/Info.plist"
  else
    # iOS frameworks are flat.
    mkdir -p "$FW/Headers" "$FW/Modules"
    cp "target/$t/release/libnative_core.a" "$FW/AurisCoreFFI"
    cp "$BINDINGS/swift/AurisCoreFFI.h" "$FW/Headers/AurisCoreFFI.h"
    printf '%s\n' "$MODULE_MAP" > "$FW/Modules/module.modulemap"
    PLIST="$FW/Info.plist"
  fi

  /usr/libexec/PlistBuddy \
    -c "Add :CFBundleDevelopmentRegion string en" \
    -c "Add :CFBundleExecutable string AurisCoreFFI" \
    -c "Add :CFBundleIdentifier string app.auris.Auris.native-core-ffi" \
    -c "Add :CFBundleInfoDictionaryVersion string 6.0" \
    -c "Add :CFBundleName string AurisCoreFFI" \
    -c "Add :CFBundlePackageType string FMWK" \
    -c "Add :CFBundleShortVersionString string 0.1.0" \
    -c "Add :CFBundleVersion string 1" \
    -c "Add :CFBundleSupportedPlatforms array" \
    -c "Add :CFBundleSupportedPlatforms:0 string $(platform_name_for "$t")" \
    -c "Add :MinimumOSVersion string $(min_os_for "$t")" \
    "$PLIST"
  CREATE_ARGS+=(-framework "$FW")
done

mkdir -p "$ROOT/AppleApp/Frameworks"
rm -rf "$ROOT/AppleApp/Frameworks/AurisCoreFFI.xcframework"
xcodebuild -create-xcframework "${CREATE_ARGS[@]}" \
  -output "$ROOT/AppleApp/Frameworks/AurisCoreFFI.xcframework"

# Generated Swift bindings join the app target's sources (see project.yml).
mkdir -p "$ROOT/AppleApp/Generated"
cp "$BINDINGS/swift/AurisCore.swift" "$ROOT/AppleApp/Generated/AurisCore.swift"

echo "OK: AppleApp/Frameworks/AurisCoreFFI.xcframework + AppleApp/Generated/AurisCore.swift"
