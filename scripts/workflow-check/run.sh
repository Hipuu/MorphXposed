#!/usr/bin/env bash
# Validates the GitHub Actions release workflow without a runner.
#
# The workflow is the one part of this project that cannot be executed locally, which makes it the
# easiest place to ship something that fails on its first tag. This closes as much of that gap as
# possible:
#
#   1. parses the YAML with a real parser and checks its structure;
#   2. extracts every inline `run:` block;
#   3. syntax-checks those blocks with bash, and proves the signature gate actually rejects an
#      empty dist and an unsigned APK.
#
# What it cannot do: confirm the actions resolve on GitHub, or that the Linux build succeeds.
# Those need a real tag.
#
# Usage:
#   bash scripts/workflow-check/run.sh            # everything, if node and bash are available
#   bash scripts/workflow-check/check-bash.sh     # bash-only half
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/release.yml"
OUT="$HERE/out"

fail=0

if ! command -v node >/dev/null 2>&1; then
  echo "node is required for the YAML checks. Run check-bash.sh for the bash half only."
  exit 2
fi

echo "=== YAML structure ==="
if node "$HERE/inspect.js" "$WORKFLOW"; then :; else fail=$((fail + 1)); fi

echo
echo "=== Extract inline run blocks ==="
if node "$HERE/extract.js" "$WORKFLOW" "$OUT"; then :; else fail=$((fail + 1)); fi

echo
if bash "$HERE/check-bash.sh"; then :; else fail=$((fail + 1)); fi

echo
echo "workflow-check failures: $fail"
[ "$fail" -eq 0 ]
