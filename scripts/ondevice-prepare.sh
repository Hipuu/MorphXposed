#!/usr/bin/env bash
# Prepares a connected device for the on-device patch test.
#
# The instrumented test needs two things that a plain install does not provide:
#   1. the official Morphe bundle in the app's private storage, registered as the
#      official source's cache (the app only writes this through its own downloader);
#   2. a supported app installed, so there is something to patch.
#
# Usage:  bash scripts/ondevice-prepare.sh [serial]
#
# Requires the debug build installed (its debuggable flag is what allows run-as).
set -u

SERIAL="${1:-}"
ADB="adb"
if [ -n "$SERIAL" ]; then ADB="adb -s $SERIAL"; fi

PKG=dev.morphexposed.debug
BUNDLE_LOCAL="${BUNDLE_LOCAL:-testdata/patches.mpp}"

say() { printf '\n=== %s ===\n' "$1"; }
die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

say "device"
$ADB devices -l || die "adb not available"

say "bundle file"
[ -f "$BUNDLE_LOCAL" ] || die "$BUNDLE_LOCAL not found; run scripts/fetch-testdata.ps1 or set BUNDLE_LOCAL"
ls -l "$BUNDLE_LOCAL"

say "app installed?"
$ADB shell pm list packages | grep -q "$PKG" \
  || die "$PKG is not installed. Install app/build/outputs/apk/debug/app-debug.apk first."

say "push and register the bundle"
$ADB shell "run-as $PKG mkdir -p files/bundles"
# Push to a world-readable temp location, then copy through run-as. `run-as` cannot read from
# /data/local/tmp directly on every device, so cat through the shell user is the portable path.
$ADB push "$BUNDLE_LOCAL" /data/local/tmp/patches.mpp >/dev/null || die "push failed"
$ADB shell "cat /data/local/tmp/patches.mpp | run-as $PKG sh -c 'cat > files/bundles/patches-official.mpp'" \
  || die "could not copy the bundle into app storage"
$ADB shell "rm -f /data/local/tmp/patches.mpp"

# The dex must not be writable or the loader refuses it; the app also enforces this, but doing it
# up front means a failure here is not confused with a loader bug.
$ADB shell "run-as $PKG chmod 600 files/bundles/patches-official.mpp"

say "register the source row"
SHA=$($ADB shell "run-as $PKG sha256sum files/bundles/patches-official.mpp" | cut -d' ' -f1 | tr -d '\r')
SIZE=$($ADB shell "run-as $PKG stat -c %s files/bundles/patches-official.mpp" | tr -d '\r')
NOW=$($ADB shell "date +%s" | tr -d '\r')
[ -n "$SHA" ] || die "could not hash the bundle"

# SQL is piped from a file so no shell quoting layer can mangle it.
TMP=$(mktemp)
cat > "$TMP" <<SQL
UPDATE patch_sources SET cachedFileName='patches-official.mpp', cachedSha256='$SHA',
  cachedSize=$SIZE, installedTag='patches-official', lastCheckedAt=$NOW WHERE id=1;
SQL
$ADB push "$TMP" /data/local/tmp/seed.sql >/dev/null
$ADB shell "cat /data/local/tmp/seed.sql | run-as $PKG sqlite3 databases/morphexposed.db" \
  || die "could not update patch_sources"
rm -f "$TMP"
$ADB shell "rm -f /data/local/tmp/seed.sql"

$ADB shell "run-as $PKG sqlite3 databases/morphexposed.db 'SELECT id,cachedFileName,installedTag,cachedSize FROM patch_sources;'"

say "supported app installed?"
FOUND=$($ADB shell pm list packages | grep -cE 'com.google.android.youtube|com.reddit.frontpage' || true)
if [ "${FOUND:-0}" -eq 0 ]; then
  printf 'No Morphe-supported app found. YouTube and Reddit are the usual targets.\n'
  printf 'The test will skip rather than fail, and a skip is not a pass.\n'
else
  printf 'Found %s supported package(s).\n' "$FOUND"
fi

say "ready"
printf 'Run the instrumented test with:\n'
printf '  ./gradlew :app:connectedDebugAndroidTest\n'
