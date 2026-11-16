#!/usr/bin/env pwsh
# Fetches the test fixtures used by the integration tests.
#
#   * the official Morphe patch bundle (~16 MB, not committed)
#   * a small synthetic APK is committed at testdata/target.apk; nothing to do for it
#
# Run this once before `./gradlew :app:testDebugUnitTest` if you want the
# real-bundle tests to execute instead of being skipped.
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$testdata = Join-Path $root 'testdata'
New-Item -ItemType Directory -Force -Path $testdata | Out-Null

$bundle = Join-Path $testdata 'patches.mpp'
if (Test-Path $bundle) {
    Write-Host "Bundle already present: $bundle"
} else {
    $url = 'https://github.com/MorpheApp/morphe-patches/releases/download/v2.3.0/patches-2.3.0.mpp'
    Write-Host "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $bundle -UseBasicParsing
}

# Verify against the digest GitHub publishes for that release asset.
$expected = '1c37549274a2b6cf7f2e3e099d9b8cb641880b8a7dac323a95638a06bb4509a9'
$actual = (Get-FileHash $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) {
    throw "Bundle checksum mismatch: expected $expected, got $actual"
}
Write-Host "Bundle verified: $actual"

$target = Join-Path $testdata 'target.apk'
if (Test-Path $target) {
    Write-Host "Synthetic target APK present: $target"
} else {
    Write-Warning "testdata/target.apk is missing; the end-to-end test will be skipped."
}
