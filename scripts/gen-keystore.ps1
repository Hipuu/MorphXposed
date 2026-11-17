#!/usr/bin/env pwsh
# Generates the release signing keystore once, if it does not already exist.
#
# The keystore is deliberately NOT committed: it is the identity of the app, and anyone holding
# it can ship updates that Android will accept as this app. CI should inject it from a secret.
param(
    [string]$OutFile = "$PSScriptRoot/../keystore/release.jks"
)

$ErrorActionPreference = 'Stop'

$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool' }

if (Test-Path $OutFile) {
    Write-Host "Keystore already exists at $OutFile"
    exit 0
}

New-Item -ItemType Directory -Force -Path (Split-Path $OutFile) | Out-Null

$password = if ($env:MORPHX_KEYSTORE_PASSWORD) { $env:MORPHX_KEYSTORE_PASSWORD } else { 'morphxposed-release' }

& $keytool -genkeypair `
    -keystore $OutFile `
    -alias morphxposed `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -storepass $password `
    -keypass $password `
    -dname "CN=MorpheXposed, OU=Release, O=MorpheXposed, L=Unknown, ST=Unknown, C=US"

if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

Write-Host "Created keystore at $OutFile"
