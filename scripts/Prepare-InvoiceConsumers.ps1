#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$caseRoot = Join-Path $PSScriptRoot '../cases/stripe-invoice-subscription-2025-03-31'
$registrationPath = Join-Path $caseRoot 'consumer-sources-v1/build-registration.json'
if ((Get-FileHash -LiteralPath $registrationPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
    '1f74dcacd771d6a9202186fa501e29dc97426c60452887ec42835ccf49463610') { throw 'Invoice consumer registration changed.' }
$registration = Get-Content -LiteralPath $registrationPath -Raw | ConvertFrom-Json
$cache = Join-Path $caseRoot 'target/dependencies'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
foreach ($artifact in $registration.artifacts) {
    if ($artifact.url -cnotmatch '^https://repo\.maven\.apache\.org/maven2/com/(stripe/stripe-java/(28\.4\.0|29\.0\.0)/stripe-java-(28\.4\.0|29\.0\.0)|google/code/gson/gson/2\.10\.1/gson-2\.10\.1)\.(jar|pom)$' -or
        $artifact.file -cne ([Uri]$artifact.url).Segments[-1] -or $artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Unsupported invoice dependency.' }
    $file = Join-Path $cache $artifact.file
    if (-not (Test-Path -LiteralPath $file)) {
        $pending = "$file.$([Guid]::NewGuid()).pending"
        Invoke-WebRequest -Uri $artifact.url -OutFile $pending -TimeoutSec 60 -MaximumRedirection 0
        if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256) { throw 'Invoice dependency download failed its digest check.' }
        Move-Item -LiteralPath $pending -Destination $file
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.sha256) { throw 'Cached invoice dependency changed.' }
}
Write-Output 'Six pinned invoice SDK/runtime artifacts verified. No consumer execution or provider API call performed.'
