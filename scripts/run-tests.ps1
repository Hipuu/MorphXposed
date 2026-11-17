#!/usr/bin/env pwsh
# Runs every test suite and reports the real outcome.
#
# Two things this guards against, both of which have bitten this project:
#
#  1. **Cached results.** Gradle will report BUILD SUCCESSFUL from a previous run without
#     executing anything, which makes "0 skipped" meaningless. Tests are always re-run here.
#  2. **Silently skipped tests.** Suites that need the 16 MB Morphe bundle skip themselves when
#     it is absent. A skip is not a pass, so the count is reported prominently instead of being
#     buried.
#
# Usage:
#   ./scripts/run-tests.ps1              # app + module
#   ./scripts/run-tests.ps1 -WithBundle  # fetch the bundle first, expecting no skips
param(
    [switch]$WithBundle
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if ($WithBundle) {
        # $LASTEXITCODE is only set by native executables. fetch-testdata.ps1 ends with Test-Path
        # and Write-Host, so reading $LASTEXITCODE here returns whatever an earlier native command
        # left behind - which made this throw even though the fetch had succeeded. Use $?, which
        # reflects whether the last PowerShell command succeeded.
        & (Join-Path $PSScriptRoot 'fetch-testdata.ps1')
        if (-not $?) { throw "Could not fetch the test bundle" }
    }

    $hasBundle = Test-Path (Join-Path $root 'testdata/patches.mpp')
    Write-Host "Bundle present: $hasBundle"
    if (-not $hasBundle) {
        Write-Host "Bundle-dependent suites will skip; run with -WithBundle to execute them." `
            -ForegroundColor Yellow
    }

    # --rerun-tasks is the point: without it Gradle may replay cached results.
    # gradlew.bat does not exist off Windows.
    $gradlew = if ($IsWindows -or $env:OS -eq 'Windows_NT') { './gradlew.bat' } else { './gradlew' }
    & $gradlew :app:testDebugUnitTest :xposed:testDebugUnitTest --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Tests failed" }

    $total = 0; $failed = 0; $skipped = 0
    $dirs = @(
        'app/build/test-results/testDebugUnitTest',
        'xposed/build/test-results/testDebugUnitTest'
    )
    foreach ($d in $dirs) {
        $path = Join-Path $root $d
        if (-not (Test-Path $path)) { continue }
        foreach ($file in Get-ChildItem $path -Filter *.xml) {
            [xml]$xml = Get-Content $file.FullName
            foreach ($suite in $xml.testsuite) {
                $total += [int]$suite.tests
                $failed += [int]$suite.failures + [int]$suite.errors
                $skipped += [int]$suite.skipped
                if ([int]$suite.skipped -gt 0) {
                    Write-Host ("  skipped {0,-3} in {1}" -f $suite.skipped, ($suite.name -replace 'dev\.morphexposed\.', '')) `
                        -ForegroundColor Yellow
                }
            }
        }
    }

    Write-Host ""
    Write-Host "==================== TEST SUMMARY ===================="
    Write-Host "  total   : $total"
    Write-Host "  failures: $failed"
    Write-Host "  skipped : $skipped"

    if ($failed -gt 0) {
        throw "$failed test(s) failed"
    }
    # Zero tests is not a pass. If the result files are missing or unreadable the loop above
    # counts nothing and every check below succeeds vacuously, so the summary would read
    # "total 0, failures 0" and exit clean - the same shape as a release build that produced no
    # release and reported success.
    if ($total -eq 0) {
        throw @"
No test results were found in: $($dirs -join ', ')

Nothing ran, so nothing passed. This usually means the Gradle test task did not execute or wrote
its reports elsewhere; check that ':app:testDebugUnitTest' and ':xposed:testDebugUnitTest' are
still the right task names, and that the JUnit XML reports are enabled.
"@
    }
    if ($WithBundle -and $skipped -gt 0) {
        # With the bundle present nothing should skip; a skip here means a fixture path broke.
        throw "$skipped test(s) skipped even though the bundle is present"
    }
    if (-not $hasBundle -and $skipped -eq 0) {
        Write-Host "  note: no bundle, yet nothing skipped - check the bundle-dependent suites actually ran" `
            -ForegroundColor Yellow
    }
    # Prove the zero-test guard is still present. A guard that silently reverts to a warning is
    # the failure this whole script exists to prevent.
    $guard = Join-Path $PSScriptRoot 'check-zero-tests.ps1'
    if (Test-Path $guard) {
        & $guard | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "The zero-test guard is missing or ineffective" }
    }

    # Prove the guards themselves can fail. A guard that cannot reject anything is the same defect
    # it was written to catch, one level up.
    $guards = Join-Path $PSScriptRoot 'check-guards.ps1'
    if (Test-Path $guards) {
        & $guards | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "A guard script failed its own negative control" }
    }

    Write-Host "======================================================"
} finally {
    Pop-Location
}
