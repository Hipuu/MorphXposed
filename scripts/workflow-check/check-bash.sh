#!/usr/bin/env bash
# Bash-side half of the workflow check: syntax-checks the extracted run blocks and exercises the
# signature gate. Split from run.sh because the YAML parsing needs node, which the WSL side here
# does not have.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"

fail=0

echo "=== Inline run blocks parse under bash -n and bash -e -n ==="
shopt -s nullglob
blocks=("$OUT"/step*.sh)
shopt -u nullglob
if [ "${#blocks[@]}" -eq 0 ]; then
  echo "  SKIP: no extracted blocks (run extract.js first)"
else
  for f in "${blocks[@]}"; do
    if bash -n "$f" 2>/tmp/wserr && bash -en "$f" 2>>/tmp/wserr; then
      printf '  OK   %s\n' "$(basename "$f")"
    else
      printf '  FAIL %s\n' "$(basename "$f")"
      head -3 /tmp/wserr | sed 's/^/       /'
      fail=$((fail + 1))
    fi
  done
fi

echo
echo "=== Signature gate rejects empty and unsigned input ==="
if bash "$HERE/check-signature-gate.sh"; then :; else fail=$((fail + 1)); fi

echo
echo "bash-side failures: $fail"
[ "$fail" -eq 0 ]
