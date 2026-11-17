#!/usr/bin/env pwsh
# Proves the guard-checking scripts can themselves fail.
#
# Every guard in this repository exists because a check once reported success without checking.
# A guard script that cannot fail would repeat that mistake one level up, so each guard is tested
# by feeding it a deliberately broken copy of the script it inspects and asserting it rejects it.
#
# Usage: ./scripts/check-guards.ps1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("guardctl-" + [guid]::NewGuid())
$failures = 0

function Assert-Detects {
    param(
        [string]$Label,
        [string]$GuardScript,
        [string]$BrokenText,
        [string]$BrokenName
    )

    $dir = Join-Path $tmp ([guid]::NewGuid().ToString('N'))
    $scripts = Join-Path $dir 'scripts'
    New-Item -ItemType Directory -Force -Path $scripts | Out-Null

    # The broken subject and the guard, side by side, so the guard inspects the copy.
    Set-Content -Path (Join-Path $scripts $BrokenName) -Value $BrokenText -Encoding UTF8
    Copy-Item (Join-Path $PSScriptRoot $GuardScript) $scripts -Force

    # check-zero-tests and check-empty-release both locate test results relative to their parent,
    # which is this temporary directory; that is fine, they are expected to fail before reaching it.
    $exe = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $scripts $GuardScript)`""
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = $dir
    $proc = [System.Diagnostics.Process]::Start($psi)
    $null = $proc.StandardOutput.ReadToEnd()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    if ($proc.ExitCode -eq 0) {
        Write-Host "  FAIL $Label - the guard accepted a script it should reject" -ForegroundColor Red
        $script:failures++
    } else {
        Write-Host "  OK   $Label" -ForegroundColor Green
        $reason = ($err -split "`n" | Where-Object { $_ -match 'would report success|would not fail' } |
            Select-Object -First 1)
        if ($reason) { Write-Host "         $($reason.Trim())" -ForegroundColor DarkGray }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null

    Write-Host "=== guard scripts reject a broken subject ==="

    # check-zero-tests must notice a runner whose zero-test guard was removed.
    $runner = Get-Content (Join-Path $PSScriptRoot 'run-tests.ps1') -Raw
    $strippedRunner = $runner -replace '(?s)if \(\$total -eq 0\) \{.*?\n    \}\r?\n', ''
    if ($strippedRunner -match '\$total -eq 0') {
        Write-Host "  FAIL could not strip the guard from run-tests.ps1; the control is invalid" -ForegroundColor Red
        $failures++
    } else {
        Assert-Detects -Label 'check-zero-tests rejects a runner without the zero-test guard' `
            -GuardScript 'check-zero-tests.ps1' -BrokenText $strippedRunner -BrokenName 'run-tests.ps1'
    }

    # check-empty-release must notice a build script that warns instead of throwing.
    $build = Get-Content (Join-Path $PSScriptRoot 'build-release.ps1') -Raw
    $strippedBuild = $build -replace '(?s)    if \(\$missing\.Count -gt 0\) \{.*?\n    \}\r?\n', ''
    if ($strippedBuild -match 'release build produced no APK') {
        Write-Host "  FAIL could not strip the guard from build-release.ps1; the control is invalid" -ForegroundColor Red
        $failures++
    } else {
        Assert-Detects -Label 'check-empty-release rejects a build without the artifact guard' `
            -GuardScript 'check-empty-release.ps1' -BrokenText $strippedBuild -BrokenName 'build-release.ps1'
    }

    Write-Host ""
    if ($failures -gt 0) { throw "$failures guard check(s) failed" }
    Write-Host "All guard controls passed: every guard rejects its broken subject." -ForegroundColor Green
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
