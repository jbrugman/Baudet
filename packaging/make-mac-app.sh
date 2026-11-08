#!/usr/bin/env bash
#
# Build a macOS .app bundle (or a .dmg) from the shaded jar using jpackage.
#
#   packaging/make-mac-app.sh [shaded-jar] [out-dir]
#
# Defaults: the shaded jar under target/, and an out-dir of target/dist. The icon
# is produced by the app itself (`java -jar ... --export-icons`), so the mark lives
# in exactly one place and this script ships no bitmap of its own.
#
# The bundle is UNSIGNED. A copy downloaded from elsewhere is blocked by Gatekeeper
# until it is codesigned and notarized -- a separate step that needs an Apple
# Developer ID and is not done here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${1:-}"
DIST="${2:-$ROOT/target/dist}"

# Discover the shaded jar (the one shade replaces, not the original-*) when none
# was passed. The glob may legitimately match nothing, so the empty result is an
# error we report ourselves rather than a shell failure.
if [ -z "$JAR" ]; then
  JAR="$(find "$ROOT/target" -maxdepth 1 -name 'mule-*.jar' ! -name 'original-*' 2>/dev/null | sort | head -n 1)"
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "No shaded jar found. Run 'mvn -B package' first, or pass the jar path." >&2
  exit 1
fi

# jpackage rejects -SNAPSHOT in --app-version; the numeric version comes from the
# pom. Grepping it here (not calling mvn) avoids re-entering Maven: this script
# runs from the mac-app profile inside the Maven build itself.
VERSION="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed -E 's@.*<version>(.*)</version>.*@\1@' | sed 's/-SNAPSHOT//')"
JAR_NAME="$(basename "$JAR")"

# All scratch lives in a temp dir removed on exit, so the build never leaves a
# half-populated target/ behind.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mule-app.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

ICONSET="$WORK/mule.iconset"
ICNS="$WORK/mule.icns"
INPUT="$WORK/input"

# 1. Let the app write its own icon set (headless), then compile the .icns with
#    the same tool Apple's SDK ships.
java -jar "$JAR" --export-icons="$ICONSET" >/dev/null
iconutil -c icns "$ICONSET" -o "$ICNS"

# 2. jpackage copies EVERYTHING under --input into the bundle's app folder, so
#    that directory must hold nothing but the runnable jar.
mkdir -p "$INPUT"
cp "$JAR" "$INPUT/$JAR_NAME"

# 3. MVP bundles the default full runtime for guaranteed correctness. Trimming it
#    with --add-modules is a documented follow-up, not an open question.
#
#    jpackage's own pre-check rejects a version whose major number is 0 (it wants
#    one to three positive integers), and Mule is a pre-alpha 0.x. macOS itself has
#    no objection to 0.1.0, so the bundle is built with a placeholder version and
#    the real one is then written back into Info.plist. The shipped .app therefore
#    reports the pom's version honestly instead of the placeholder it was built with.
rm -rf "$DIST"
mkdir -p "$DIST"
jpackage \
  --type app-image \
  --name Mule \
  --app-version 1.0.0 \
  --vendor dev.mule \
  --input "$INPUT" \
  --main-jar "$JAR_NAME" \
  --main-class dev.mule.MainKt \
  --icon "$ICNS" \
  --dest "$DIST"

PLIST="$DIST/Mule.app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$PLIST"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $VERSION" "$PLIST"
echo "Stamped bundle version $VERSION into $PLIST"

# Optionally wrap the stamped .app into a .dmg. It reuses the image just stamped,
# so the disk image carries the same honest version. The result is unsigned: a
# downloaded copy still needs codesign + notarize before Gatekeeper lets it open.
if [ "${MULE_PKG_TYPE:-app-image}" = "dmg" ]; then
  jpackage --type dmg --app-image "$DIST/Mule.app" --dest "$DIST"
fi

echo "Packaged into $DIST"
ls -la "$DIST"
