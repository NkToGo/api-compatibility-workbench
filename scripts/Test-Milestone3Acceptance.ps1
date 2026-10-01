#requires -Version 7.0
[CmdletBinding()]
param([string]$StateDirectory)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $StateDirectory) {$StateDirectory=Join-Path $root '.workbench'}
$clone=Join-Path $root ('backend/target/m3-checks/' + [guid]::NewGuid().ToString('N'))
$store=Join-Path $clone '.workbench'
$reportRelative='docs/evaluations/milestone-3-acceptance-2026-10-01.json'
$reviewRelative='docs/evaluations/sufficient-evidence-review-2026-10-01.json'
$originalReport=Get-Content -LiteralPath (Join-Path $root $reportRelative) -Raw | ConvertFrom-Json -AsHashtable
$oldReview=Get-Content -LiteralPath (Join-Path $root 'docs/evaluations/missing-evidence-review-2026-09-30.json') -Raw | ConvertFrom-Json -AsHashtable
function Copy-Artifact([string]$base,[string]$destination,[string]$relative) {
    $target=Join-Path $destination $relative
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
    [IO.File]::Copy((Join-Path $base $relative),$target,$true)
}
foreach ($relative in $originalReport.repositoryArtifacts.Keys) {Copy-Artifact $root $clone $relative}
Copy-Artifact $root $clone $reportRelative
foreach ($relative in (@($originalReport.storeArtifacts.Keys)+@($oldReview.storeArtifacts.Keys) | Sort-Object -Unique)) {Copy-Artifact $StateDirectory $store $relative}
$checker=Join-Path $clone 'scripts/Verify-Milestone3Acceptance.ps1'
$reportPath=Join-Path $clone $reportRelative
$reviewPath=Join-Path $clone $reviewRelative
$reportBytes=[IO.File]::ReadAllBytes($reportPath);$reviewBytes=[IO.File]::ReadAllBytes($reviewPath)
function Reset { [IO.File]::WriteAllBytes($reportPath,$reportBytes);[IO.File]::WriteAllBytes($reviewPath,$reviewBytes) }
function Write-Test($report,$review) {
    [IO.File]::WriteAllText($reviewPath,($review | ConvertTo-Json -Depth 50)+"`n")
    $report.repositoryArtifacts[$reviewRelative]=(Get-FileHash $reviewPath).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json -Depth 50)+"`n")
}
$passed=0
$baseline = (& $checker -StateDirectory $store -RequireCriteriaMet | Out-String) | ConvertFrom-Json
if ($baseline.conclusion -ne 'CRITERIA_MET_WITH_EXPLICIT_BLOCK' -or $baseline.acceptedRepairs -ne 4) {throw 'Baseline acceptance failed'}
$passed++;'PASS: complete evidence with blocked investigation'
$cases=@(
    @{name='missing review trial';change={param($r,$v) $v.suites[0].trials=@($v.suites[0].trials[0..1])}},
    @{name='changed explanation';change={param($r,$v) $v.suites[0].trials[0].summary.text += ' invented'}},
    @{name='unknown review citation';change={param($r,$v) $v.suites[0].trials[0].summary.evidenceEvents += 'event-999'}},
    @{name='unsupported verdict made eligible';change={param($r,$v) $v.suites[1].trials[1].acceptanceDisposition='ELIGIBLE_FOR_ACCEPTANCE'}},
    @{name='blocked trial given repair credit';change={param($r,$v) $r.sufficientEvidence[1].trials[1].acceptedRepair=$true}},
    @{name='failed repair given credit';change={param($r,$v) $r.sufficientEvidence[0].trials[2].acceptedRepair=$true}},
    @{name='failed trial removed from denominator';change={param($r,$v) $r.sufficientEvidence[0].trials=@($r.sufficientEvidence[0].trials[0..1])}},
    @{name='duplicate withheld repetition';change={param($r,$v) $r.missingEvidence.trials[1].registration=$r.missingEvidence.trials[0].registration}},
    @{name='changed acceptance totals';change={param($r,$v) $r.totals.acceptedRepairs=5}},
    @{name='incorrect conclusion';change={param($r,$v) $r.conclusion='CRITERIA_MET'}},
    @{name='duplicate historical suite';change={param($r,$v) $r.historicalCustomerSuites[1]=$r.historicalCustomerSuites[0]}},
    @{name='unexpected escaped artifact reference';change={param($r,$v) $r.storeArtifacts['../../outside.json']='0'*64}}
)
foreach ($case in $cases) {
    Reset
    $r=[IO.File]::ReadAllText($reportPath) | ConvertFrom-Json -AsHashtable;$v=[IO.File]::ReadAllText($reviewPath) | ConvertFrom-Json -AsHashtable
    & $case.change $r $v;Write-Test $r $v
    $rejected=$false
    try {$null=& $checker -StateDirectory $store -RequireCriteriaMet} catch {$rejected=$true}
    if (-not $rejected) {throw ('Missed negative control: '+$case.name)}
    $passed++;'PASS: '+$case.name
}
Reset
$victim=Join-Path $store ('agents/' + $originalReport.sufficientEvidence[0].trials[0].agentId + '/event-1.json')
$saved=[IO.File]::ReadAllBytes($victim)
try {
    [IO.File]::AppendAllText($victim,' ')
    $rejected=$false;try {$null=& $checker -StateDirectory $store -RequireCriteriaMet} catch {$rejected=$true}
    if (-not $rejected) {throw 'Changed source event was not rejected'}
    $passed++;'PASS: changed recorded source event'
} finally {[IO.File]::WriteAllBytes($victim,$saved)}
Reset
$r=[IO.File]::ReadAllText($reportPath) | ConvertFrom-Json -AsHashtable;$v=[IO.File]::ReadAllText($reviewPath) | ConvertFrom-Json -AsHashtable
$v.suites[1].trials[0].findings[0].verdict='UNRESOLVED';$v.suites[1].trials[0].findings[0].rationale='Alternative test judgment leaves this hypothesis unresolved.'
$v.suites[1].trials[0].verdict='UNRESOLVED';$v.suites[1].trials[0].acceptanceDisposition='BLOCKED_FROM_ACCEPTANCE'
$r.sufficientEvidence[1].trials[0].semanticVerdict='UNRESOLVED';$r.sufficientEvidence[1].trials[0].acceptanceDisposition='BLOCKED_FROM_ACCEPTANCE';$r.sufficientEvidence[1].trials[0].acceptedRepair=$false
$r.sufficientEvidence[1].acceptedRepairs=1;$r.totals.acceptedRepairs=3;$r.totals.blockedInvestigations=2;$r.conclusion='CRITERIA_NOT_MET'
Write-Test $r $v
$result=(& $checker -StateDirectory $store | Out-String) | ConvertFrom-Json
if ($result.conclusion -ne 'CRITERIA_NOT_MET') {throw 'Contrary review was not preserved'}
$rejected=$false;try {$null=& $checker -StateDirectory $store -RequireCriteriaMet} catch {$rejected=$true}
if (-not $rejected) {throw 'Insufficient accepted repairs were credited'}
$passed++;'PASS: coherent contrary review remains not met and acceptance gate refuses it'
Reset
[ordered]@{SmokeControlsPassed=$passed;OriginalRecordsModified=$false} | ConvertTo-Json -Compress
