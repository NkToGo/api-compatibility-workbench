#requires -Version 7.0
[CmdletBinding()]
param([string]$ReviewPath, [string]$StateDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ReviewPath) { $ReviewPath = Join-Path $root 'docs/evaluations/missing-evidence-review-2026-09-30.json' }
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }

function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Read-Object([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    Require (-not $file.PSIsContainer -and $file.Length -le 1048576 -and -not ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Invalid review artifact.'
    return [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json -AsHashtable
}
function Artifact-Path([string]$Base, [string]$Relative) {
    Require (-not [IO.Path]::IsPathRooted($Relative)) 'Absolute artifact references are unsupported.'
    $basePath = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($Base))
    $path = [IO.Path]::GetFullPath((Join-Path $basePath $Relative))
    $basePrefix = if ($basePath.EndsWith([IO.Path]::DirectorySeparatorChar)) { $basePath } else { $basePath + [IO.Path]::DirectorySeparatorChar }
    Require ($path.StartsWith($basePrefix, [StringComparison]::Ordinal)) 'Artifact reference escaped its store.'
    for ($cursor = $path; $cursor -cne $basePath; $cursor = [IO.Path]::GetDirectoryName($cursor)) {
        Require (-not ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked review artifacts are unsupported.'
    }
    return $path
}
function Match-Hash([string]$Path, [string]$Expected) {
    Require ($Expected -cmatch '^[0-9a-f]{64}$') 'Invalid review artifact hash.'
    Require ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $Expected) 'Review evidence changed.'
}
function Compact($Value) { return ConvertTo-Json -InputObject $Value -Depth 20 -Compress }

$review = Read-Object $ReviewPath
Require ($review.formatVersion -eq 1 -and $review.evaluationId -cmatch '^evaluation-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid review identity.'
$prefix = 'missing-evidence-evaluations/' + $review.evaluationId
$expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in @('suite.json', 'result.json')) {
    $relative = "$prefix/$name"
    [void]$expected.Add($relative)
    Require ($review.storeArtifacts.ContainsKey($relative)) 'Missing evaluation binding.'
    Match-Hash (Artifact-Path $StateDirectory $relative) $review.storeArtifacts[$relative]
}
$suite = Read-Object (Artifact-Path $StateDirectory "$prefix/suite.json")
$evaluation = Read-Object (Artifact-Path $StateDirectory "$prefix/result.json")
Require ($suite.evaluationId -ceq $review.evaluationId -and $evaluation.evaluationId -ceq $review.evaluationId -and $evaluation.status -ceq 'COMPLETED') 'Review evaluation mismatch.'
Require ($review.trials.Count -eq $evaluation.trials.Count -and $review.trials.Count -eq $suite.trials.Count) 'Review trial coverage changed.'
$seen = [Collections.Generic.HashSet[int]]::new()
$verdicts = @('SUPPORTED_WITHIN_RECORDED_SCOPE', 'UNSUPPORTED', 'UNRESOLVED')
$counts = @{}
foreach ($row in $review.trials) {
    $number = [int]$row.trial
    Require ($row.trial -eq $number -and $number -ge 1 -and $number -le $review.trials.Count -and $seen.Add($number)) 'Invalid or duplicate reviewed trial.'
    $original = $evaluation.trials[$number - 1]
    $registered = $suite.trials[$number - 1]
    Require ($row.agentId -ceq $original.agentId -and $row.agentId -cmatch '^agent-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Reviewed agent mismatch.'
    Require ((Compact $original.trial) -ceq (Compact $row.registration) -and $row.registration.contextId -ceq $registered.contextId) 'Reviewed trial registration changed.'
    $trialPrefix = "$prefix/trial-$number"
    $agentPrefix = "$trialPrefix/agents/$($row.agentId)"
    $mapping = Read-Object (Artifact-Path $StateDirectory "$trialPrefix/agent.json")
    $agent = Read-Object (Artifact-Path $StateDirectory "$agentPrefix/result.json")
    $session = Read-Object (Artifact-Path $StateDirectory "$agentPrefix/session.json")
    Require ($mapping.agentId -ceq $row.agentId -and $agent.agentId -ceq $row.agentId -and $session.agentId -ceq $row.agentId -and $session.contextId -ceq $row.registration.contextId) 'Reviewed child binding changed.'
    Require ($agent.status -ceq 'ABSTAINED' -and $original.actualStatus -ceq 'ABSTAINED') 'Review is limited to recorded abstentions.'
    Require ($row.summary.text -ceq $agent.findings.summary -and $row.findings.Count -eq $agent.findings.findings.Count) 'Reviewed explanation changed.'
    foreach ($relative in @("$trialPrefix/agent.json", "$agentPrefix/result.json", "$agentPrefix/session.json")) { [void]$expected.Add($relative) }
    $events = @{}
    foreach ($event in $agent.events) {
        Require ($event.eventId -cmatch '^event-[1-9][0-9]*$' -and -not $events.ContainsKey($event.eventId)) 'Invalid reviewed event.'
        $events[$event.eventId] = $event
        $relative = "$agentPrefix/$($event.eventId).json"
        [void]$expected.Add($relative)
        Require ((Compact (Read-Object (Artifact-Path $StateDirectory $relative))) -ceq (Compact $event)) 'Review event differs from recorded outcome.'
    }
    Require ($row.summary.evidenceEvents.Count -ge 1) 'Summary review requires an observed evidence reference.'
    foreach ($citation in $row.summary.evidenceEvents) { Require ($events.ContainsKey($citation)) 'Summary review cites an unknown event.' }
    for ($i = 0; $i -lt $row.findings.Count; $i++) {
        $finding = $row.findings[$i]
        $actual = $agent.findings.findings[$i]
        Require ($finding.index -eq ($i + 1) -and $finding.kind -ceq $actual.kind -and $finding.statement -ceq $actual.statement -and (Compact $finding.citations) -ceq (Compact $actual.citations)) 'Reviewed finding changed.'
        foreach ($citation in $finding.citations) { Require ($events.ContainsKey($citation)) 'Finding review cites an unknown event.' }
    }
    $claims = @($row.summary) + @($row.findings)
    foreach ($claim in $claims) { Require ($claim.verdict -cin $verdicts -and -not [string]::IsNullOrWhiteSpace($claim.rationale)) 'Incomplete claim judgment.' }
    $verdict = if ($claims.verdict -ccontains 'UNSUPPORTED') { 'UNSUPPORTED' } elseif ($claims.verdict -ccontains 'UNRESOLVED') { 'UNRESOLVED' } else { 'SUPPORTED_WITHIN_RECORDED_SCOPE' }
    Require ($row.verdict -ceq $verdict) 'Trial verdict disagrees with its claim judgments.'
    $counts[$verdict] = 1 + $counts[$verdict]
}
Require ($expected.SetEquals([string[]]@($review.storeArtifacts.Keys))) 'Incomplete or unexpected review artifact set.'
foreach ($relative in $expected) { Match-Hash (Artifact-Path $StateDirectory $relative) $review.storeArtifacts[$relative] }
$repositoryFiles = @('backend/src/main/java/io/github/nktogo/apicompatibility/runner/InvestigationDecision.java', 'backend/src/main/java/io/github/nktogo/apicompatibility/runner/ConsumerInvestigator.java', 'docs/compatibility-contract.md')
Require ($review.repositoryArtifacts.Count -eq $repositoryFiles.Count) 'Invalid review protocol bindings.'
foreach ($relative in $repositoryFiles) {
    Require ($review.repositoryArtifacts.ContainsKey($relative)) 'Missing review protocol binding.'
    Match-Hash (Artifact-Path $root $relative) $review.repositoryArtifacts[$relative]
}
[ordered]@{ reviewBindings = 'MATCHED'; evaluationId = $review.evaluationId; reviewedTrials = $review.trials.Count; recordedVerdicts = $counts; scope = 'Checks record identity and complete claim coverage; does not judge semantic support or authorize execution.' } | ConvertTo-Json -Depth 5
