#!/usr/bin/env pwsh
# Verifies that every component declared in the APK manifest exists as a class in its dex.
#
# This catches the most common cause of an app that builds but crashes on launch:
# a component renamed or moved without updating the manifest. It runs without a device,
# using dexdump against the produced APK.
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$BuildTools = "$env:ANDROID_HOME/build-tools/37.0.0",
    # The events module does not declare the bridge receiver, so it is exempt from that check.
    [switch]$SkipBridgeCheck
)

$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path $Apk).Path
$aapt2 = Join-Path $BuildTools 'aapt2.exe'
$dexdump = Join-Path $BuildTools 'dexdump.exe'

foreach ($tool in @($aapt2, $dexdump)) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}

$package = (& $aapt2 dump badging $apkPath | Select-String -Pattern "^package: name='([^']+)'" |
    ForEach-Object { $_.Matches[0].Groups[1].Value })
Write-Host "Package: $package"

# Collect component class names from the manifest.
# Only elements that name a class are checked; permissions, actions, and authorities share the
# same android:name attribute but are plain strings, not classes.
$tree = & $aapt2 dump xmltree --file AndroidManifest.xml $apkPath

$componentTags = @('activity', 'activity-alias', 'service', 'receiver', 'provider', 'application')
$classes = New-Object System.Collections.Generic.List[string]
$currentTag = $null
$lastWasClassAttr = $false

foreach ($line in $tree) {
    $text = $line.ToString()

    # Element start, e.g. "E: activity (line=42)" or "E: application (line=20)"
    $tagMatch = [regex]::Match($text, '^\s*E:\s+([A-Za-z0-9_.-]+)')
    if ($tagMatch.Success) {
        $currentTag = $tagMatch.Groups[1].Value
        $lastWasClassAttr = $false
        continue
    }

    # Attribute line naming a class. The attribute id 0x01010003 is android:name; aapt2 prefixes
    # it with the full namespace URL, so the match is not anchored to the line start.
    $attrMatch = [regex]::Match($text, 'android:name\(0x01010003\)="([^"]+)"')
    if ($attrMatch.Success -and $currentTag -in $componentTags) {
        $value = $attrMatch.Groups[1].Value

        # A component name is a Java class in our package. Intent actions and permission names also
        # use android:name, so require a class-like final segment (starts uppercase, then lowercase).
        # .NET regex is used explicitly: PowerShell's -match is case-insensitive by default, which
        # would let lowercase letters satisfy [A-Z].
        $isInPackage = $value.StartsWith("$package.", [StringComparison]::Ordinal)
        $endsLikeClass = [regex]::IsMatch($value, '\.[A-Z][A-Za-z0-9_$]*$', 'None')
        if ($isInPackage -and $endsLikeClass) {
            $classes.Add($value)
        }
    }
}

$classes = $classes | Select-Object -Unique

if (-not $classes) { throw "No component classes found in manifest" }

# Unpack the dex and dump class descriptors.
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("dexcheck-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    $dexFiles = $zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }
    foreach ($entry in $dexFiles) {
        $dest = Join-Path $tmp $entry.FullName
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
    }
    $zip.Dispose()

    $descriptors = New-Object System.Collections.Generic.HashSet[string]
    foreach ($dex in Get-ChildItem $tmp -Filter *.dex) {
        & $dexdump -f $dex.FullName 2>$null |
            Select-String -Pattern "Class descriptor\s+:\s+'(L[^']+;)'" |
            ForEach-Object { [void]$descriptors.Add($_.Matches[0].Groups[1].Value) }
    }

    Write-Host "Declared classes: $($classes.Count), dex classes: $($descriptors.Count)"

    $missing = @()
    foreach ($cls in $classes) {
        $descriptor = 'L' + ($cls -replace '\.', '/') + ';'
        if (-not $descriptors.Contains($descriptor)) { $missing += $cls }
    }

    if ($missing.Count -gt 0) {
        Write-Host "MISSING classes:" -ForegroundColor Red
        $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        throw "$($missing.Count) manifest component(s) missing from the dex"
    }

    Write-Host "OK: every manifest component resolves to a class in the dex" -ForegroundColor Green

    # Guard the LSPosed bridge contract in the built manifest. The bridge was once silently
    # non-functional, so a release build must fail if the permission or action disappears.
    if (-not $SkipBridgeCheck) {
        Write-Host "Checking LSPosed bridge contract..."
        $all = $tree -join "`n"

        $declaresPermission = $all -match 'dev\.morphexposed\.permission\.BRIDGE'
        if (-not $declaresPermission) {
            throw "The bridge permission dev.morphexposed.permission.BRIDGE is not declared"
        }

        # protectionLevel is a bit field; signature == 0x2.
        $signatureGuarded = $all -match 'protectionLevel\(0x01010009\)=0x00000002'
        if (-not $signatureGuarded) {
            throw "The bridge permission is not signature-protected (expected protectionLevel=0x2)"
        }

        $hasBridgeAction = $all -match 'dev\.morphexposed\.LSPOSED_PACKAGE_EVENT'
        if (-not $hasBridgeAction) {
            throw "The receiver does not filter for dev.morphexposed.LSPOSED_PACKAGE_EVENT"
        }

        $receiverGuarded = $all -match
            'PackageChangeReceiver[\s\S]{0,2000}?permission\(0x01010006\)="dev\.morphexposed\.permission\.BRIDGE"'
        if (-not $receiverGuarded) {
            throw "PackageChangeReceiver is not guarded by the bridge permission"
        }

        Write-Host "OK: bridge permission is signature-guarded and the receiver filters its action" -ForegroundColor Green
    }

    # The events module must be openable from LSPosed Manager. LSPosed shows a "module settings"
    # entry only for a module declaring one of these actions; without them the user browses
    # LSPosed with no guidance from the module itself, which is exactly where they need to learn
    # to scope it to System Framework. Checked on every release because the strings are easy to
    # lose in a manifest refactor and nothing else would notice their absence.
    if ($Apk -like "*Events*") {
        Write-Host "Checking that LSPosed can open the module settings screen..."
        $manifestText = $tree -join "`n"
        foreach ($action in @(
            "org.lsposed.manager.action.OPEN_MODULE_SETTINGS",
            "de.robv.android.xposed.category.MODULE_SETTINGS"
        )) {
            if ($manifestText -notmatch [regex]::Escape($action)) {
                throw "The events module does not declare $action, so LSPosed cannot open its settings screen"
            }
        }
        Write-Host "OK: the module is openable from LSPosed Manager" -ForegroundColor Green
    }
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
