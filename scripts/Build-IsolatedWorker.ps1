#requires -Version 7.0
[CmdletBinding()]
param([switch]$RepairWorker)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $root 'backend/target'
$jar = Join-Path $target 'api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend with Maven clean verify first.' }
$newestSource = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild it before preparing the image.' }
& (Join-Path $PSScriptRoot 'Prepare-StripeCase.ps1')
$caseRoot = Join-Path $root 'cases/stripe-customer-sources-2020-08-27'
$context = Join-Path $target ('worker-context-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $context | Out-Null
foreach ($subdirectory in @('lib', 'case/consumer', 'dependencies')) {
    New-Item -ItemType Directory -Path (Join-Path $context $subdirectory) -Force | Out-Null
}
# Only named build outputs and case files enter the context. The repository is never a Docker context.
Copy-Item -LiteralPath $jar -Destination (Join-Path $context 'lib/workbench.jar')
foreach ($library in @('gson-2.14.0.jar', 'error_prone_annotations-2.48.0.jar')) {
    Copy-Item -LiteralPath (Join-Path $target "dependency/$library") -Destination (Join-Path $context "lib/$library")
}
foreach ($file in $(if ($RepairWorker) { @() } else { @('integrity.json', 'provenance.json', 'fixtures.json', 'expectations.json', 'consumer/baseline.java.txt', 'consumer/upstream-reference.java.txt') })) {
    Copy-Item -LiteralPath (Join-Path $caseRoot $file) -Destination (Join-Path $context "case/$file")
}
foreach ($file in @('stripe-java-17.4.0.jar', 'stripe-java-20.33.0.jar', 'gson-2.8.6.jar')) {
    Copy-Item -LiteralPath (Join-Path $caseRoot "target/dependencies/$file") -Destination (Join-Path $context "dependencies/$file")
}
Copy-Item -LiteralPath (Join-Path $root 'containers/curated-worker/Dockerfile') -Destination (Join-Path $context 'Dockerfile')
if ($RepairWorker) {
    $dockerfile = Join-Path $context 'Dockerfile'
    $contents = (Get-Content -LiteralPath $dockerfile -Raw).Replace('COPY case/ /opt/workbench/case/', '# Fixture inputs and expectations remain on the host.')
    Set-Content -LiteralPath $dockerfile -Value $contents -Encoding utf8NoBOM
}
$imageFile = Join-Path $context 'image-id.txt'
& docker build --platform linux/amd64 --network none --iidfile $imageFile $context
if ($LASTEXITCODE -ne 0) { throw 'Worker image build failed.' }
$image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
if ($image -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Docker did not return an immutable image ID.' }
$imageName = if ($RepairWorker) { 'repair-worker-image.txt' } else { 'worker-image.txt' }
Set-Content -LiteralPath (Join-Path $target $imageName) -Value $image -Encoding utf8NoBOM
Write-Output "Worker image: $image"
