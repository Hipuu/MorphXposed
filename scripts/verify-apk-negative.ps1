#!/usr/bin/env pwsh
# Proves scripts/verify-apk.ps1 can actually fail.
#
# A verification script that passes everything is worse than none: it turns an unknown into false
# confidence. This builds deliberately broken APKs and asserts that verify-apk rejects each one, so
# the checks are known to discriminate rather than merely to run.
#
# Usage: ./scripts/verify-apk-negative.ps1
[CmdletBinding()]
param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '37.0.0',
    [string]$Platform = 'android-37.0'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$verify = Join-Path $PSScriptRoot 'verify-apk.ps1'
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("apkneg-" + [guid]::NewGuid())
$failures = 0

if (-not $AndroidHome) { throw "ANDROID_HOME is not set" }
$bt = Join-Path $AndroidHome "build-tools/$BuildToolsVersion"
$aapt2 = Join-Path $bt $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'aapt2.exe' } else { 'aapt2' })
$androidJar = Join-Path $AndroidHome "platforms/$Platform/android.jar"

if (-not (Test-Path $aapt2)) { throw "aapt2 not found at $aapt2" }
if (-not (Test-Path $androidJar)) { throw "android.jar not found at $androidJar" }

function New-TestApk {
    param([string]$Name, [string]$ComponentName, [string]$Package = 'dev.ncertest')

    $dir = Join-Path $tmp $Name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$Package">
    <application>
        <activity android:name="$ComponentName" android:exported="true" />
    </application>
</manifest>
"@
    $manifestPath = Join-Path $dir 'AndroidManifest.xml'
    Set-Content -Path $manifestPath -Value $manifest -Encoding UTF8
    $out = Join-Path $dir "$Name.apk"
    & $aapt2 link -o $out -I $androidJar --manifest $manifestPath | Out-Null
    if (-not (Test-Path $out)) { throw "aapt2 did not produce $out" }
    return $out
}

function Assert-Rejected {
    param([string]$Label, [string]$Apk)
    # verify-apk resolves its -Apk argument, and the child process does not share this working
    # directory, so an absolute path is required. One control deliberately passes a path that does
    # not exist, so this must not assume the file is there.
    if (Test-Path -LiteralPath $Apk) {
        $Apk = (Resolve-Path -LiteralPath $Apk).Path
    } else {
        $Apk = [System.IO.Path]::GetFullPath($Apk)
    }

    # Use whichever PowerShell is running this script; pwsh is not present everywhere.
    # Start-Process-style capture is used instead of `&` because $ErrorActionPreference = 'Stop'
    # turns a child's stderr into a terminating error, and a rejection writes to stderr by design.
    $exe = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$verify`" -Apk `"$Apk`" -SkipBridgeCheck"
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $output = @($stdout, $stderr)
    $exitCode = $proc.ExitCode
    if ($exitCode -eq 0) {
        Write-Host "  FAIL $Label - verify-apk accepted it" -ForegroundColor Red
        $script:failures++
    } else {
        # Report why, so a rejection for the wrong reason is visible.
        $reason = ($output | Where-Object { $_ -match 'missing from the dex|No component|not found|Failed' } |
            Select-Object -First 1)
        Write-Host "  OK   $Label - rejected" -ForegroundColor Green
        if ($reason) { Write-Host "         $($reason.ToString().Trim())" -ForegroundColor DarkGray }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null

    Write-Host "=== negative controls ==="

    # A missing file must not be treated as fine.
    Assert-Rejected 'a nonexistent path' (Join-Path $tmp 'absent.apk')

    # A file that is not a zip at all.
    $junk = Join-Path $tmp 'junk.apk'
    Set-Content -Path $junk -Value 'this is not an apk' -NoNewline
    Assert-Rejected 'a file that is not an APK' $junk

    # A manifest component with no matching class in the dex. This is the case that would crash the
    # app on launch, and the reason the check exists.
    $relative = New-TestApk -Name 'relative' -ComponentName '.MissingActivity'
    Assert-Rejected 'a relative-named component missing from the dex' $relative

    $qualified = New-TestApk -Name 'qualified' -ComponentName 'dev.ncertest.AlsoMissing'
    Assert-Rejected 'a qualified-named component missing from the dex' $qualified

    Write-Host ""
    if ($failures -gt 0) {
        throw "$failures negative control(s) failed - verify-apk does not reject what it should"
    }
    Write-Host "All negative controls passed: verify-apk rejects every broken input." -ForegroundColor Green
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
