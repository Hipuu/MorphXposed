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
    Write-Host "Building MorpheXposed release artifacts..."
    & ./gradlew.bat :app:assembleRelease :xposed:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }

    $out = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $targets = @(
        @{ Src = 'app/build/outputs/apk/release/app-release.apk';     Name = 'MorpheXposed.apk' },
        @{ Src = 'xposed/build/outputs/apk/release/xposed-release.apk'; Name = 'MorpheXposed-Events.apk' }
    )

    foreach ($t in $targets) {
        $src = Join-Path $root $t.Src
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $out $t.Name) -Force
            Write-Host "  $($t.Name)"
        } else {
            Write-Warning "Missing $($t.Src) - it is unsigned; sign it with apksigner first."
        }
    }

    Write-Host "`nArtifacts in $out"
    Get-ChildItem $out -Filter *.apk | ForEach-Object { "  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB) }

    # Verify each artifact before it is considered done: signed, and every manifest component
    # present in the dex. This catches the class of failure that only shows up as a crash on
    # launch, without needing a device.
    Write-Host "`nVerifying artifacts..."
    $buildTools = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'build-tools/37.0.0' } else { $null }
    if ($buildTools -and (Test-Path $buildTools)) {
        # Only the artifacts this run produced. Globbing *.apk would also verify leftovers from a
    # previous name or version and fail on them, which says nothing about this build.
    $produced = @($targets | ForEach-Object { Join-Path $out $_.Name })
    foreach ($apk in $produced | Where-Object { Test-Path $_ } | ForEach-Object { Get-Item $_ }) {
            # apksigner is a .bat on Windows and a plain script elsewhere.
            $signer = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'apksigner.bat' } else { 'apksigner' }
            $verify = & (Join-Path $buildTools $signer) verify $apk.FullName 2>&1
            if ($LASTEXITCODE -ne 0) { throw "$($apk.Name) is not correctly signed" }
            Write-Host "  signed: $($apk.Name)"

            & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk $apk.FullName -BuildTools $buildTools `
                -SkipBridgeCheck:($apk.Name -like '*Events*')
        }
    } else {
        Write-Warning "Build tools not found; skipping signature and component verification."
    }

    # Execute the generated root-mount shell. Reading it is not enough: a syntax error or a
    # guard that does not exit is invisible until a device fails to boot with the module.
    $shellCheck = Join-Path $PSScriptRoot 'verify-root-scripts.sh'
    $scriptDir = Join-Path $root 'app/build/root-scripts'
    if ((Test-Path $shellCheck) -and (Test-Path $scriptDir)) {
        Write-Host "`nExecuting the root-mount scripts..."
        $onWindows = $IsWindows -or $env:OS -eq 'Windows_NT'
        $wsl = if ($onWindows) { Get-Command wsl -ErrorAction SilentlyContinue } else { $null }
        if (-not $onWindows) {
            # Linux (CI): run the scripts directly, no WSL layer.
            & bash (Join-Path $PSScriptRoot 'verify-root-scripts.sh') `
                (Join-Path $root 'app/build/root-scripts/module.prop') `
                (Join-Path $root 'app/build/root-scripts/post-fs-data.sh') `
                (Join-Path $root 'app/build/root-scripts/service.sh')
            if ($LASTEXITCODE -ne 0) { throw "The generated root-mount scripts failed shell verification" }
        } elseif ($wsl) {
            $linuxRoot = '/mnt/' + ($root.Substring(0, 1).ToLower()) + ($root.Substring(2) -replace '\\', '/')
            $shellCmd = "cd $linuxRoot && sed -i 's/\r`$//' scripts/verify-root-scripts.sh && " +
                "bash scripts/verify-root-scripts.sh app/build/root-scripts/module.prop " +
                "app/build/root-scripts/post-fs-data.sh app/build/root-scripts/service.sh"
            & wsl -d kiri -- bash -lc $shellCmd
            if ($LASTEXITCODE -ne 0) { throw "The generated root-mount scripts failed shell verification" }
        } else {
            Write-Warning "WSL not available; skipping execution of the root-mount scripts."
        }
    }
} finally {
    Pop-Location
}
