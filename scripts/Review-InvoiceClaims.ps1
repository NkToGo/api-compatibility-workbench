#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('draft','verify')][string]$Action = 'verify',
    [Parameter(Mandatory)][string]$ReviewPath,
    [string]$ReviewSha256,
    [string]$ReviewPacketId,
    [string]$PacketSha256,
    [string]$EvidenceDirectory,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EvidenceDirectory) { $EvidenceDirectory = $root }
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$jar = Join-Path $root 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend before preparing or checking invoice claim reviews.' }
$source = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($source.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild before invoice claim review.' }
$arguments = @($Action,[IO.Path]::GetFullPath($EvidenceDirectory),[IO.Path]::GetFullPath($StateDirectory))
if ($Action -eq 'draft') {
    if (-not $ReviewPacketId -or $PacketSha256 -cnotmatch '^[0-9a-f]{64}$' -or $ReviewSha256) { throw 'Draft requires ReviewPacketId and independently retained PacketSha256.' }
    $arguments += @($ReviewPacketId,$PacketSha256,[IO.Path]::GetFullPath($ReviewPath))
} else {
    if ($ReviewSha256 -cnotmatch '^[0-9a-f]{64}$' -or $ReviewPacketId -or $PacketSha256) { throw 'Verify requires independently retained ReviewSha256.' }
    $arguments += @([IO.Path]::GetFullPath($ReviewPath),$ReviewSha256)
}
$classPath = $jar + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java -cp $classPath io.github.nktogo.apicompatibility.runner.InvoiceSemanticReview @arguments
if ($LASTEXITCODE -ne 0) { throw 'Invoice claim review binding failed. Preserve the existing review and evidence.' }
