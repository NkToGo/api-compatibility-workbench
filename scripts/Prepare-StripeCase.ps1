#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$caseRoot = Join-Path $PSScriptRoot '../cases/stripe-customer-sources-2020-08-27'
& (Join-Path $PSScriptRoot 'Verify-CuratedCase.ps1') -CaseDirectory $caseRoot
$cache = Join-Path $caseRoot 'target/dependencies'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$provenance = Get-Content -LiteralPath (Join-Path $caseRoot 'provenance.json') -Raw | ConvertFrom-Json
foreach ($artifact in ($provenance.executionArtifacts | Where-Object { $_.coordinate.EndsWith(':jar') })) {
    if ($artifact.url -notmatch '^https://repo\.maven\.apache\.org/maven2/com/(stripe/stripe-java|google/code/gson/gson)/[0-9.]+/[a-z0-9.-]+\.jar$' -or
        $artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Unsupported artifact source or digest.' }
    $file = Join-Path $cache ([Uri]$artifact.url).Segments[-1]
    if (-not (Test-Path -LiteralPath $file)) {
        # A failed or interrupted download never occupies the final cache name.
        $pending = "$file.$([Guid]::NewGuid()).pending"
        Invoke-WebRequest -Uri $artifact.url -OutFile $pending -TimeoutSec 60 -MaximumRedirection 0
        if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256) {
            throw 'Downloaded artifact failed its digest check.'
        }
        Move-Item -LiteralPath $pending -Destination $file
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256) {
        throw 'Cached artifact failed its digest check.'
    }
}
Write-Output 'Historical SDK artifacts verified. Preparation makes no provider API calls.'
