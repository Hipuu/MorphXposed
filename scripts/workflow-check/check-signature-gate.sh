#!/usr/bin/env bash
# Proves the workflow's signature gate actually rejects what it must.
#
# The workflow loops over dist/*.apk and fails if apksigner rejects any of them. If that check
# could never fail - a stubbed apksigner, a wrong path, or the empty-glob trap where a for-loop
# silently iterates over a literal "dist/*.apk" - it would publish unsigned builds while reporting
# success. That is the failure this script rules out.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"

STEP="$HERE/out/release-step9-Confirm-the-APKs-are-signed.sh"

SBX=/tmp/morphexposed-siggate
rm -rf "$SBX"
mkdir -p "$SBX/dist" "$SBX/bin" "$SBX/build-tools/37.0.0"

# A stand-in apksigner: succeeds for names containing 'signed', fails otherwise.
cat > "$SBX/bin/apksigner" <<'EOF'
#!/bin/sh
case "$2" in
  *unsigned*) echo "DOES NOT VERIFY"; exit 1 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$SBX/bin/apksigner"
cp "$SBX/bin/apksigner" "$SBX/build-tools/37.0.0/apksigner"

run_gate() {
  # The workflow exports ANDROID_HOME; supply it or every case fails for the wrong reason.
  ( cd "$SBX" && ANDROID_HOME="$SBX" bash "$STEP" )
}

if [ ! -f "$STEP" ]; then
  echo "  SKIP: $(basename "$STEP") not found. Run extract.js first."
  exit 0
fi

fail=0
echo "  using: $(basename "$STEP")"

check() {
  local label="$1"; local expect="$2"
  local out
  if out=$(run_gate 2>&1); then
    if [ "$expect" = "pass" ]; then
      printf '  OK   %s\n' "$label"
    else
      printf '  FAIL %s (gate passed but should have failed)\n' "$label"
      fail=$((fail + 1))
    fi
  else
    if [ "$expect" = "fail" ]; then
      printf '  OK   %s\n' "$label"
    else
      printf '  FAIL %s (gate failed but should have passed)\n' "$label"
      printf '%s\n' "$out" | sed 's/^/       /'
      fail=$((fail + 1))
    fi
  fi
}

rm -f "$SBX"/dist/*.apk
check "empty dist is refused" fail

: > "$SBX/dist/MorpheXposed.apk"
: > "$SBX/dist/MorpheXposed-Events.apk"
check "signed artifacts are accepted" pass

: > "$SBX/dist/unsigned-extra.apk"
check "one unsigned artifact is refused" fail

rm -rf "$SBX"
echo
echo "  signature-gate failures: $fail"
[ "$fail" -eq 0 ]
