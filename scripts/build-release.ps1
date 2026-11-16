#!/usr/bin/env pwsh
# Builds signed release APKs for both artifacts.
#
# Usage:
#   ./scripts/build-release.ps1
#
# Requires:
#   * JDK 21+ on JAVA_HOME
#   * Android SDK on ANDROID_HOME (platform 37, build-tools 37.0.0)
#   * GITHUB_ACTOR + GITHUB_TOKEN with read:packages, for morphe-patcher
#   * a keystore at keystore/release.jks (see gen-keystore.ps1)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    Write-Host "Building MorphXposed release artifacts…"
    & ./gradlew.bat :app:assembleRelease :xposed:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }

    $out = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $targets = @(
        @{ Src = 'app/build/outputs/apk/release/app-release.apk';     Name = 'MorphXposed.apk' },
        @{ Src = 'xposed/build/outputs/apk/release/xposed-release.apk'; Name = 'MorphXposed-Events.apk' }
    )

    foreach ($t in $targets) {
        $src = Join-Path $root $t.Src
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $out $t.Name) -Force
            Write-Host "  $($t.Name)"
        } else {
            Write-Warning "Missing $($t.Src) — it is unsigned; sign it with apksigner first."
        }
    }

    Write-Host "`nArtifacts in $out"
    Get-ChildItem $out -Filter *.apk | ForEach-Object { "  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB) }

    # Verify each artifact before it is considered done: signed, and every manifest component
    # present in the dex. This catches the class of failure that only shows up as a crash on
    # launch, without needing a device.
    Write-Host "`nVerifying artifacts…"
    $buildTools = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'build-tools/37.0.0' } else { $null }
    if ($buildTools -and (Test-Path $buildTools)) {
        foreach ($apk in Get-ChildItem $out -Filter *.apk) {
            $verify = & (Join-Path $buildTools 'apksigner.bat') verify $apk.FullName 2>&1
            if ($LASTEXITCODE -ne 0) { throw "$($apk.Name) is not correctly signed" }
            Write-Host "  signed: $($apk.Name)"

            & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk $apk.FullName -BuildTools $buildTools
        }
    } else {
        Write-Warning "Build tools not found; skipping signature and component verification."
    }
} finally {
    Pop-Location
}
