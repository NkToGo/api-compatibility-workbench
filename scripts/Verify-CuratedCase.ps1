#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $CaseDirectory = (Join-Path $PSScriptRoot '../cases/stripe-customer-sources-2020-08-27')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$caseRoot = (Resolve-Path -LiteralPath $CaseDirectory).Path
$index = Get-Content -LiteralPath (Join-Path $caseRoot 'integrity.json') -Raw | ConvertFrom-Json
if ($index.formatVersion -ne 1 -or $index.files.Count -eq 0) {
    throw 'Unsupported or empty integrity index.'
}
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $index.files) {
    # The curated format permits ordinary relative paths only.
    if ($entry.path -notmatch '^[a-zA-Z0-9_-]+(?:[./][a-zA-Z0-9_-]+)*$' -or
        $entry.path.Contains('..') -or -not $seen.Add($entry.path) -or
        $entry.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'Invalid or duplicate integrity entry.'
    }
    $path = Join-Path $caseRoot $entry.path
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing case input: $($entry.path)"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $entry.sha256) { throw "Case input digest mismatch: $($entry.path)" }
}
foreach ($required in @('expectations.json', 'fixtures.json', 'provenance.json',
                        'consumer/baseline.java.txt', 'consumer/upstream-reference.java.txt')) {
    if (-not $seen.Contains($required)) { throw "Unpinned required input: $required" }
}
$expectations = Get-Content -LiteralPath (Join-Path $caseRoot 'expectations.json') -Raw | ConvertFrom-Json
$fixtures = Get-Content -LiteralPath (Join-Path $caseRoot 'fixtures.json') -Raw | ConvertFrom-Json
if ($expectations.formatVersion -ne 1 -or $fixtures.formatVersion -ne 1 -or
    $expectations.caseId -cne $fixtures.caseId -or $fixtures.origin -cne 'authored-synthetic') {
    throw 'Mismatched case identity, format or fixture origin.'
}
$expectedIds = @($expectations.scenarios.id)
$fixtureIds = @($fixtures.scenarios.id)
if ($expectedIds.Count -eq 0 -or @($expectedIds | Sort-Object -Unique).Count -ne $expectedIds.Count -or
    @($fixtureIds | Sort-Object -Unique).Count -ne $fixtureIds.Count -or
    (Compare-Object $expectedIds $fixtureIds -CaseSensitive)) {
    throw 'Scenario coverage must be nonempty, unique and identical.'
}
foreach ($scenario in $fixtures.scenarios) {
    foreach ($side in @('baseline', 'target')) {
        $customer = $scenario.$side.customerResponse
        if ($customer.object -cne 'customer' -or $customer.id -cne 'cus_curated') {
            throw "Invalid customer fixture: $($scenario.id)/$side"
        }
        if ($customer.PSObject.Properties.Name -contains 'sources' -and
            ($null -eq $customer.sources -or $customer.sources.object -cne 'list' -or
             $customer.sources.has_more -ne $false)) {
            throw "Invalid single-page source collection: $($scenario.id)/$side"
        }
    }
}
Write-Output "Case inputs verified: $($index.files.Count) pinned files, $($expectedIds.Count) scenarios. No consumer execution performed."
