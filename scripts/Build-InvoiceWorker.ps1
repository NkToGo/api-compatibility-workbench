#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $root 'backend/target'
$jar = Join-Path $target 'api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend with Maven clean verify first.' }
$newestSource = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild it before preparing the invoice image.' }
& (Join-Path $PSScriptRoot 'Prepare-InvoiceConsumers.ps1')
$caseRoot = Join-Path $root 'cases/stripe-invoice-subscription-2025-03-31'
$context = Join-Path $target ('invoice-worker-context-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $context | Out-Null
foreach ($directory in @('lib', 'dependencies')) { New-Item -ItemType Directory -Path (Join-Path $context $directory) | Out-Null }
# The context contains only named build outputs and pinned dependencies, with no fixture or repository mount.
Copy-Item -LiteralPath $jar -Destination (Join-Path $context 'lib/workbench.jar')
foreach ($library in @('gson-2.14.0.jar', 'error_prone_annotations-2.48.0.jar')) {
    Copy-Item -LiteralPath (Join-Path $target "dependency/$library") -Destination (Join-Path $context "lib/$library")
}
foreach ($library in @('stripe-java-28.4.0.jar', 'stripe-java-29.0.0.jar', 'gson-2.10.1.jar')) {
    Copy-Item -LiteralPath (Join-Path $caseRoot "target/dependencies/$library") -Destination (Join-Path $context "dependencies/$library")
}
$dockerfile = (Get-Content -LiteralPath (Join-Path $root 'containers/curated-worker/Dockerfile') -Raw).Replace('COPY case/ /opt/workbench/case/', '# Fixture inputs, expected facts and ledger remain on the host.')
Set-Content -LiteralPath (Join-Path $context 'Dockerfile') -Value $dockerfile -Encoding utf8NoBOM
$imageFile = Join-Path $context 'image-id.txt'
& docker build --platform linux/amd64 --network none --iidfile $imageFile $context
if ($LASTEXITCODE -ne 0) { throw 'Invoice worker image build failed.' }
$image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Docker did not return an immutable invoice image ID.' }
Set-Content -LiteralPath (Join-Path $target 'invoice-worker-image.txt') -Value $image -Encoding utf8NoBOM
Write-Output "Invoice worker image: $image"
