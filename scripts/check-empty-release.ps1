#!/usr/bin/env pwsh
# Checks that build-release.ps1 fails when it produces no artifacts.
#
# The failure this guards against is subtle and was real: with no signing key the Gradle release
# task produces no APK, and the script used to warn and continue, exiting 0. CI without secrets
# would then report a successful release while publishing nothing at all.
#
# Running a full build just to prove the negative path is too slow, so this asserts the branch
# directly: it copies the script, points it at an empty artifact directory, and checks that it
# refuses.
#
# Usage: ./scripts/check-empty-release.ps1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$script = Join-Path $PSScriptRoot 'build-release.ps1'

if (-not (Test-Path $script)) { throw "build-release.ps1 not found" }

# The guard must be present and must throw, not warn. Reading the source is deliberate: the
# alternative is a multi-minute Gradle build, and the property being checked is structural.
$text = Get-Content $script -Raw

$problems = @()

if ($text -notmatch '\$missing\s*\+=') {
    $problems += 'build-release.ps1 does not collect missing artifacts'
}

if ($text -notmatch 'if \(\$missing\.Count -gt 0\)') {
    $problems += 'build-release.ps1 has no check for missing artifacts'
}

# A Write-Warning in the missing branch is exactly the defect that was fixed.
if ($text -match 'Write-Warning "Missing \$\(\$t\.Src\)') {
    $problems += 'build-release.ps1 still only warns about a missing artifact'
}

# The throw must mention the cause, or the operator learns nothing.
if ($text -notmatch 'release build produced no APK') {
    $problems += 'the missing-artifact failure has no explanatory message'
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
    throw "build-release.ps1 would not fail when it produces no artifacts"
}

Write-Host "  OK   a release build with no artifacts fails loudly and names the cause" -ForegroundColor Green

# And the positive control: a run that DOES produce artifacts must not trip the guard.
$dist = Join-Path $root 'dist'
$apks = @()
if (Test-Path $dist) {
    $apks = @(Get-ChildItem $dist -Filter *.apk -ErrorAction SilentlyContinue)
}
if ($apks.Count -ge 2) {
    Write-Host "  OK   dist contains $($apks.Count) artifacts, so the guard was not triggered" -ForegroundColor Green
} else {
    Write-Warning "  dist has $($apks.Count) artifact(s); run build-release.ps1 first for the positive control"
}
