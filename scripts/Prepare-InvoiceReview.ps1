#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('prepare','read','contract')][string]$Action = 'prepare',
    [string]$SelectionPath,
    [string]$ReviewPacketId,
    [string]$ContractDirectory,
    [string]$EvidenceDirectory,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EvidenceDirectory) { $EvidenceDirectory = $root }
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
$jar = Join-Path $root 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend before preparing invoice review inputs.' }
$newestSource = Get-ChildItem -LiteralPath (Join-Path $root 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) { throw 'The backend JAR is stale. Rebuild before preparing invoice review inputs.' }
$arguments = @($Action,[IO.Path]::GetFullPath($EvidenceDirectory),[IO.Path]::GetFullPath($StateDirectory))
switch ($Action) {
    'prepare' {
        if (-not $SelectionPath -or $ContractDirectory) { throw 'Prepare requires SelectionPath and optionally ReviewPacketId.' }
        $arguments += [IO.Path]::GetFullPath($SelectionPath)
        if ($ReviewPacketId) { $arguments += $ReviewPacketId }
    }
    'read' {
        if (-not $ReviewPacketId -or $SelectionPath -or $ContractDirectory) { throw 'Read requires ReviewPacketId.' }
        $arguments += $ReviewPacketId
    }
    'contract' {
        if (-not $ContractDirectory -or $SelectionPath -or $ReviewPacketId) { throw 'Contract requires a contract directory relative to EvidenceDirectory.' }
        $arguments += $ContractDirectory
    }
}
$classPath = $jar + [IO.Path]::PathSeparator + (Join-Path $root 'backend/target/dependency/*')
& java -cp $classPath io.github.nktogo.apicompatibility.runner.InvoiceReviewInputs @arguments
if ($LASTEXITCODE -ne 0) { throw 'Invoice review input binding failed. Preserve existing records before inspection or recovery.' }
