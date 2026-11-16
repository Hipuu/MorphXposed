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
} finally {
    Pop-Location
}
