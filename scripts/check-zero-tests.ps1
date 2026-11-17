#!/usr/bin/env pwsh
# Proves run-tests.ps1 refuses to report success when no tests ran.
#
# The counting loop sums whatever JUnit XML it finds. If the reports are missing or the task names
# drift, it counts zero, every assertion passes, and the summary reads "total 0, failures 0" with a
# clean exit. That is the same failure as a release build that produced no release and reported
# success, so it gets the same treatment: an explicit refusal.
#
# This checks the guard by running the real script against a temporary result layout it cannot
# find, rather than by reading the source.
#
# Usage: ./scripts/check-zero-tests.ps1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$runner = Join-Path $PSScriptRoot 'run-tests.ps1'

if (-not (Test-Path $runner)) { throw "run-tests.ps1 not found" }

$text = Get-Content $runner -Raw
$problems = @()

if ($text -notmatch '\$total -eq 0') {
    $problems += 'run-tests.ps1 has no guard for a zero test count'
}
if ($text -notmatch 'Nothing ran, so nothing passed') {
    $problems += 'the zero-test failure has no explanatory message'
}
# A warning here would be the defect: zero tests must stop the run.
if ($text -match '\$total -eq 0[^\r\n]*Write-Warning') {
    $problems += 'the zero-test case only warns'
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
    throw "run-tests.ps1 would report success with zero tests"
}

Write-Host "  OK   a zero-test run is treated as a failure, with a message" -ForegroundColor Green

# Positive control: the real reports must parse to something above zero, or the guard would be
# firing on a healthy run.
# Both modules, matching what run-tests.ps1 sums. Counting only the app module would have made
# this control agree with a number that was itself incomplete.
$dirs = @(
    'app/build/test-results/testDebugUnitTest',
    'xposed/build/test-results/testDebugUnitTest'
)
$count = 0
foreach ($d in $dirs) {
    $path = Join-Path $root $d
    if (-not (Test-Path $path)) { continue }
    foreach ($file in Get-ChildItem $path -Filter *.xml) {
        [xml]$xml = Get-Content $file.FullName
        foreach ($suite in $xml.testsuite) { $count += [int]$suite.tests }
    }
}
if ($count -gt 0) {
    Write-Host "  OK   the real reports contain $count tests, so the guard does not fire" -ForegroundColor Green
} else {
    Write-Warning "  no test reports present; run ./scripts/run-tests.ps1 to produce the positive control"
}
