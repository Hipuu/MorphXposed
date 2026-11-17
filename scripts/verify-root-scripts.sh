#!/usr/bin/env bash
# Executes the shell that MorpheXposed writes into /data/adb/modules.
#
# The scripts cannot be mounted on this machine, but they can be *parsed and run* with the
# Android commands stubbed out. That catches the failures that matter and cannot be seen by
# reading: a syntax error, an unquoted substitution that splits a path, a missing variable, or a
# guard that does not actually exit.
#
# Usage: bash verify-root-scripts.sh <module.prop> <post-fs-data.sh> <service.sh>
set -u

MODULE_PROP="$1"
POST_FS_DATA="$2"
SERVICE="$3"

fail=0
pass=0
ok()   { printf '  OK   %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  FAIL %s\n' "$1"; fail=$((fail+1)); }

echo "=== syntax ==="
for f in "$POST_FS_DATA" "$SERVICE"; do
    if bash -n "$f" 2>/dev/null; then ok "bash -n $(basename "$f")"; else bad "bash -n $(basename "$f")"; fi
    # Android runs these with /system/bin/sh, which is closer to POSIX sh.
    if sh -n "$f" 2>/dev/null; then ok "sh -n $(basename "$f")"; else bad "sh -n $(basename "$f")"; fi
done

echo "=== module.prop ==="
for key in id name version versionCode author description; do
    if grep -q "^$key=" "$MODULE_PROP"; then ok "has $key"; else bad "missing $key"; fi
done
# A value must never contain a newline, or it can inject another key.
if [ "$(grep -c '^version=' "$MODULE_PROP")" -eq 1 ]; then ok "single version line"; else bad "version line duplicated"; fi

echo "=== service.sh behaviour ==="
# Stub the Android commands on PATH and run the script for real.
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT

cat > "$STUB/getprop" <<'EOF'
#!/bin/sh
echo 1
EOF

cat > "$STUB/pm" <<'EOF'
#!/bin/sh
echo "package:/data/app/com.example.target/base.apk"
EOF

# The stock version the script will compare against.
# The heredoc must be quoted so the variable is expanded when dumpsys *runs*, not now.
cat > "$STUB/dumpsys" <<'EOF'
#!/bin/sh
echo "Packages:"
echo "  versionName=$STUB_VERSION"
EOF

cat > "$STUB/mount" <<EOF
#!/bin/sh
echo "MOUNT \$@" >> "$STUB/mount.log"
EOF

cat > "$STUB/umount" <<'EOF'
#!/bin/sh
exit 0
EOF

cat > "$STUB/am" <<'EOF'
#!/bin/sh
exit 0
EOF

cat > "$STUB/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF

chmod +x "$STUB"/*

# Give the script a module directory containing an APK, so a mount is actually attempted.
MODDIR=$(mktemp -d)
trap 'rm -rf "$STUB" "$MODDIR"' EXIT
: > "$MODDIR/com.example.target.apk"
cp "$SERVICE" "$MODDIR/service.sh"

run_service() {
    : > "$STUB/mount.log"
    STUB_VERSION="$1" PATH="$STUB:$PATH" MODDIR="$MODDIR" sh "$MODDIR/service.sh" 2>/dev/null
    echo $?
}

# 1. Matching version must mount.
rc=$(run_service "1.0.0")
if [ "$rc" = "0" ] && grep -q "MOUNT" "$STUB/mount.log" 2>/dev/null; then
    ok "mounts when the version matches"
else
    bad "did not mount on a matching version (rc=$rc)"
fi

# 2. A mismatched version must NOT mount: a patched DEX over a different resource table crashes.
rc=$(run_service "9.9.9")
if [ "$rc" = "0" ] && ! grep -q "MOUNT" "$STUB/mount.log" 2>/dev/null; then
    ok "refuses to mount when the version changed"
else
    bad "mounted despite a version mismatch (rc=$rc)"
fi

echo "=== post-fs-data.sh behaviour ==="
cp "$POST_FS_DATA" "$MODDIR/post-fs-data.sh"
: > "$STUB/mount.log"
PATH="$STUB:$PATH" sh "$MODDIR/post-fs-data.sh" 2>/dev/null
if grep -q "MOUNT" "$STUB/mount.log" 2>/dev/null; then
    ok "post-fs-data mounts the module's APK"
else
    bad "post-fs-data did not mount"
fi

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
