#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('freeze','resume','read','verify-current')][string]$Action = 'freeze',
    [string]$SelectionPath,
    [ValidatePattern('^invoicecampaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [string]$EvidenceDirectory,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EvidenceDirectory) { $EvidenceDirectory = $root }
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$jar = Join-Path $root 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend before registering an invoice campaign.' }
$newestSource = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild before registering an invoice campaign.' }
$arguments = @($Action,[IO.Path]::GetFullPath($EvidenceDirectory),[IO.Path]::GetFullPath($StateDirectory))
if ($Action -eq 'freeze') {
    if (-not $SelectionPath) { throw 'Freeze requires SelectionPath; use resume for an existing campaign.' }
    $arguments += [IO.Path]::GetFullPath($SelectionPath)
    if ($CampaignId) { $arguments += $CampaignId }
} else {
    if (-not $CampaignId -or $SelectionPath) { throw 'Resume/read/verify-current require CampaignId without SelectionPath.' }
    $arguments += $CampaignId
}
$classPath = $jar + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java -cp $classPath io.github.nktogo.apicompatibility.runner.InvoiceCampaign @arguments
if ($LASTEXITCODE -ne 0) { throw 'Invoice campaign validation failed. Preserve existing records before inspection or recovery.' }
