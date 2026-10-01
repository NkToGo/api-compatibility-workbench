#requires -Version 7.0
[CmdletBinding()]
param([string]$ReportPath, [string]$StateDirectory, [switch]$RequireCriteriaMet)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ReportPath) { $ReportPath = Join-Path $root 'docs/evaluations/milestone-3-acceptance-2026-10-01.json' }
if (-not $StateDirectory) { $StateDirectory = Join-Path $root '.workbench' }
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Read-Object([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    Require (-not $file.PSIsContainer -and $file.Length -le 1048576 -and -not ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Invalid acceptance artifact.'
    return [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json -AsHashtable
}
function Artifact-Path([string]$Base, [string]$Relative) {
    Require (-not [IO.Path]::IsPathRooted($Relative)) 'Absolute artifact references are unsupported.'
    $basePath = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($Base))
    $path = [IO.Path]::GetFullPath((Join-Path $basePath $Relative))
    Require ($path.StartsWith($basePath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) 'Artifact reference escaped its store.'
    for ($cursor = $path; $cursor -cne $basePath; $cursor = [IO.Path]::GetDirectoryName($cursor)) {
        Require (-not ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'Linked acceptance artifacts are unsupported.'
    }
    return $path
}
function Match-Hash([string]$Path, [string]$Expected) {
    Require ($Expected -cmatch '^[0-9a-f]{64}$') 'Invalid acceptance artifact hash.'
    Require ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $Expected) 'Acceptance evidence changed.'
}
function Compact($Value) { return ConvertTo-Json -InputObject $Value -Depth 40 -Compress }
function Bound([string]$Relative) {
    [void]$expected.Add($Relative)
    Require ($report.storeArtifacts.ContainsKey($Relative)) 'Missing acceptance binding.'
    $path = Artifact-Path $StateDirectory $Relative
    Match-Hash $path $report.storeArtifacts[$Relative]
    return $path
}
function Read-Bound([string]$Relative) { return Read-Object (Bound $Relative) }
function Check-Claims($Row, $Agent) {
    Require ($Row.summary.text -ceq $Agent.findings.summary -and $Row.findings.Count -eq $Agent.findings.findings.Count) 'Reviewed explanation or claim coverage changed.'
    $events = @{}
    foreach ($event in $Agent.events) {
        Require ($event.eventId -cmatch '^event-[1-9][0-9]*$' -and -not $events.ContainsKey($event.eventId)) 'Duplicate or invalid review event.'
        $events[$event.eventId] = $event
        $stored = Read-Bound "agents/$($Row.agentId)/$($event.eventId).json"
        Require ((Compact $stored) -ceq (Compact $event)) 'Reviewed event differs from recorded history.'
    }
    Require ($Row.summary.evidenceEvents.Count -gt 0) 'Summary lacks evidence references.'
    foreach ($citation in $Row.summary.evidenceEvents) { Require ($events.ContainsKey($citation)) 'Unknown summary reference.' }
    for ($i = 0; $i -lt $Row.findings.Count; $i++) {
        $finding = $Row.findings[$i]; $actual = $Agent.findings.findings[$i]
        Require ($finding.index -eq ($i + 1) -and $finding.kind -ceq $actual.kind -and $finding.statement -ceq $actual.statement -and (Compact $finding.citations) -ceq (Compact $actual.citations)) 'Reviewed finding changed.'
        foreach ($citation in $finding.citations) { Require ($events.ContainsKey($citation)) 'Unknown finding reference.' }
    }
    $claims = @($Row.summary) + @($Row.findings)
    foreach ($claim in $claims) {
        Require ($claim.verdict -cin @('SUPPORTED_WITHIN_RECORDED_SCOPE','UNSUPPORTED','UNRESOLVED') -and -not [string]::IsNullOrWhiteSpace($claim.rationale)) 'Incomplete claim judgment.'
    }
    $verdict = if ($claims.verdict -ccontains 'UNSUPPORTED') { 'UNSUPPORTED' } elseif ($claims.verdict -ccontains 'UNRESOLVED') { 'UNRESOLVED' } else { 'SUPPORTED_WITHIN_RECORDED_SCOPE' }
    Require ($Row.verdict -ceq $verdict) 'Trial verdict disagrees with its claims.'
    $disposition = if ($verdict -ceq 'SUPPORTED_WITHIN_RECORDED_SCOPE') { 'ELIGIBLE_FOR_ACCEPTANCE' } else { 'BLOCKED_FROM_ACCEPTANCE' }
    Require ($Row.acceptanceDisposition -ceq $disposition) 'Unsupported or unresolved explanation cannot enter acceptance.'
    return $verdict
}
$report = Read-Object $ReportPath
Require ($report.formatVersion -eq 1 -and $report.reportId -ceq 'milestone-3-2026-10-01' -and $report.plannedTrials -eq 18) 'Invalid acceptance report identity or denominator.'
$repositoryFiles = @('docs/evaluations/sufficient-evidence-review-2026-10-01.json', 'docs/evaluations/missing-evidence-review-2026-09-30.json',
    'scripts/Verify-MissingEvidenceReview.ps1', 'scripts/Verify-Milestone3Acceptance.ps1', 'docs/compatibility-contract.md',
    'backend/src/main/java/io/github/nktogo/apicompatibility/runner/ConsumerInvestigator.java',
    'backend/src/main/java/io/github/nktogo/apicompatibility/runner/InvestigationDecision.java',
    'backend/src/main/java/io/github/nktogo/apicompatibility/runner/SubscriptionInvestigationTools.java',
    'backend/src/main/java/io/github/nktogo/apicompatibility/runner/RepairWorkerMain.java')
Require ($report.repositoryArtifacts.Count -eq $repositoryFiles.Count) 'Invalid acceptance protocol bindings.'
foreach ($file in $repositoryFiles) { Require ($report.repositoryArtifacts.ContainsKey($file)) 'Missing protocol binding.'; Match-Hash (Artifact-Path $root $file) $report.repositoryArtifacts[$file] }
$review = Read-Object (Artifact-Path $root $repositoryFiles[0])
$missingReview = Read-Object (Artifact-Path $root $repositoryFiles[1])
Require ($review.formatVersion -eq 1 -and $review.suites.Count -eq 2 -and $report.sufficientEvidence.Count -eq 2) 'Invalid sufficient-evidence coverage.'
# Validate the existing separate abstention review without changing its judgments.
$null = & (Join-Path $root 'scripts/Verify-MissingEvidenceReview.ps1') -ReviewPath (Join-Path $root $repositoryFiles[1]) -StateDirectory $StateDirectory
$expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$seenCases = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$rawRepairs = 0; $acceptedRepairs = 0; $blocked = 0; $calls = 0; [long]$inputTokens = 0; [long]$outputTokens = 0
$repairCriteria = $true
foreach ($group in $report.sufficientEvidence) {
    Require ($group.caseKind -cin @('CUSTOMER_SOURCES','SUBSCRIPTION_PERIODS') -and $seenCases.Add($group.caseKind)) 'Invalid or duplicate case.'
    $reviewGroup = @($review.suites | Where-Object caseKind -CEQ $group.caseKind)
    Require ($reviewGroup.Count -eq 1 -and $reviewGroup[0].evaluationId -ceq $group.evaluationId -and $reviewGroup[0].trials.Count -eq 3 -and $group.trials.Count -eq 3) 'Incomplete sufficient-evidence review.'
    $prefix = if ($group.caseKind -ceq 'CUSTOMER_SOURCES') { 'customer-repair-evaluations' } else { 'subscription-investigation-repair-evaluations' }
    Require ($group.evaluationId -cmatch '^evaluation-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid evaluation reference.'
    $prefix += '/' + $group.evaluationId
    $suite = Read-Bound "$prefix/suite.json"; $evaluation = Read-Bound "$prefix/result.json"
    Require ($suite.evaluationId -ceq $group.evaluationId -and $evaluation.status -ceq 'COMPLETED' -and $evaluation.trials.Count -eq 3 -and $suite.repetitions -eq 3) 'Evaluation identity or trial count changed.'
    Require ($group.model -ceq $suite.model -and $group.harnessSha256 -ceq $suite.harnessSha256 -and $group.maxRegisteredModelCalls -eq 39) 'Evaluation configuration changed.'
    Require ($group.inputTokens -eq $evaluation.inputTokens -and $group.outputTokens -eq $evaluation.outputTokens) 'Recorded evaluation usage changed.'
    $groupAccepted = 0; $groupRaw = 0; $groupCalls = 0
    for ($i = 0; $i -lt 3; $i++) {
        $row = $group.trials[$i]; $original = $evaluation.trials[$i]; $judgment = $reviewGroup[0].trials[$i]; $number = $i + 1
        Require ($row.trial -eq $number -and $original.trial -eq $number -and $judgment.trial -eq $number -and $row.agentId -ceq $original.agentId -and $judgment.agentId -ceq $row.agentId) 'Trial or reviewer identity mismatch.'
        foreach ($kind in @('agent','candidate','verification')) {
            $id = $row[$kind + 'Id']
            Require ($id -cmatch ('^' + $kind + '-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') -and $id -ceq $original[$kind + 'Id']) 'Changed stage identity.'
            $mapping = Read-Bound "$prefix/$kind-$number.json"
            Require ($mapping[$kind + 'Id'] -ceq $id) 'Changed stage mapping.'
        }
        $agent = Read-Bound "agents/$($row.agentId)/result.json"; $session = Read-Bound "agents/$($row.agentId)/session.json"
        Require ($agent.agentId -ceq $row.agentId -and $session.agentId -ceq $row.agentId -and $session.contextId -ceq $row.contextId -and $judgment.contextId -ceq $row.contextId -and $agent.status -ceq 'INVESTIGATED') 'Changed investigation binding.'
        $verdict = Check-Claims $judgment $agent
        Require ($row.semanticVerdict -ceq $verdict -and $row.acceptanceDisposition -ceq $judgment.acceptanceDisposition) 'Acceptance differs from semantic review.'
        $contextRoot = if ($group.caseKind -ceq 'CUSTOMER_SOURCES') { 'investigations' } else { 'subscription-investigations' }
        $context = Read-Bound "$contextRoot/$($row.contextId)/registration.json"
        Require ($context.contextId -ceq $row.contextId -and (Compact $session.case) -ceq (Compact $suite.contexts[$i].description)) 'Changed registered context.'
        if ($group.caseKind -ceq 'SUBSCRIPTION_PERIODS') { Require ($context.verificationId -ceq $report.retainedSubscriptionVerificationId -and $session.case.value.executionMode -ceq 'retained-only') 'Changed retained execution mode or evidence.' }
        foreach ($event in $agent.events) {
            if ($event.tool -ceq 'read_input' -and $event.result.ok) {
                $value = $event.result.value
                $relative = if ($group.caseKind -ceq 'CUSTOMER_SOURCES') { "$contextRoot/$($row.contextId)/inputs/$($value.inputId)" } else {
                    $name = @{ 'baseline-source'='baseline.txt'; provenance='README.md'; expectations='expectations.json'; fixtures='fixtures.json' }[$value.inputId]
                    "verifications/$($context.verificationId)/inputs/$name"
                }
                $path = Bound $relative; Match-Hash $path $value.sha256
                $text = [IO.File]::ReadAllText($path)
                Require ($value.totalCharacters -eq $text.Length -and $value.offset -ge 0 -and $value.nextOffset -le $text.Length -and $value.nextOffset -ge $value.offset -and $value.content -ceq $text.Substring($value.offset, $value.nextOffset - $value.offset)) 'Reviewed source page changed.'
            }
        }
        $candidateRoot = if ($group.caseKind -ceq 'CUSTOMER_SOURCES') { 'candidates' } else { 'subscription-candidates' }
        $registration = Read-Bound "$candidateRoot/$($row.candidateId)/registration.json"; $generated = Read-Bound "$candidateRoot/$($row.candidateId)/result.json"
        $candidateAgent = if ($registration.ContainsKey('investigation')) { $registration.investigation.agentId } else { $registration.agentId }
        Require ($candidateAgent -ceq $row.agentId -and $generated.status -ceq 'CANDIDATE_READY') 'Changed generation handoff.'
        $spec = Read-Bound "verifications/$($row.verificationId)/spec.json"; $verified = Read-Bound "verifications/$($row.verificationId)/result.json"
        $observations = Read-Bound "verifications/$($row.verificationId)/observations.json"
        $image = if ($group.caseKind -ceq 'CUSTOMER_SOURCES') { $suite.repairImage } else { $suite.imageId }
        Require ($group.repairImage -ceq $image -and $spec.imageId -ceq $image -and $spec.candidateManifestSha256 -ceq $report.storeArtifacts["$candidateRoot/$($row.candidateId)/result.json"]) 'Changed repair image or candidate manifest.'
        Require ($spec.candidateId -ceq $row.candidateId -and $verified.status -ceq 'COMPLETED' -and $row.outcome -ceq $original.outcome -and (Compact $row.scenarioOutcomes) -ceq (Compact $verified.evaluation.scenarios)) 'Changed repair evidence.'
        $scenarioCount = if ($group.caseKind -ceq 'CUSTOMER_SOURCES') { 5 } else { 8 }
        Require ($observations.baseline.Count -eq $scenarioCount -and @($observations.baseline | Where-Object { $_.disposition -cne 'COMPLETED' -or $_.assertionOutcome -cne 'PASSED' }).Count -eq 0) 'Passing baseline required.'
        $fullyPassing = $verified.evaluation.conclusion -ceq 'ALL_OBSERVED_REGRESSIONS_FIXED'
        if ($fullyPassing) { Require ($observations.candidate.Count -eq $scenarioCount -and @($observations.candidate | Where-Object { $_.disposition -cne 'COMPLETED' -or $_.assertionOutcome -cne 'PASSED' }).Count -eq 0) 'Candidate passing assertions required.' }
        Require ($fullyPassing -eq ($row.outcome -ceq 'OBSERVED_REGRESSIONS_FIXED')) 'Repair outcome differs from protected verification.'
        $eligible = $verdict -ceq 'SUPPORTED_WITHIN_RECORDED_SCOPE'
        Require ($row.acceptedRepair -is [bool] -and $row.acceptedRepair -eq ($fullyPassing -and $eligible)) 'Rejected explanation cannot earn repair credit.'
        if ($fullyPassing) { $groupRaw++ }; if ($row.acceptedRepair) { $groupAccepted++ }; if (-not $eligible) { $blocked++ }
        $agentCalls = @(Get-ChildItem -LiteralPath (Artifact-Path $StateDirectory "agents/$($row.agentId)") -Filter 'call-*.json')
        Require ($agentCalls.Count -le 12 -and $row.registeredModelCalls -eq (1 + $agentCalls.Count)) 'Recorded request count changed.'
        foreach ($file in $agentCalls) { [void](Bound "agents/$($row.agentId)/$($file.Name)") }
        $groupCalls += $row.registeredModelCalls
    }
    Require ($group.rawCompleteRepairs -eq $groupRaw -and $group.acceptedRepairs -eq $groupAccepted -and $group.registeredModelCalls -eq $groupCalls) 'Per-case acceptance count changed.'
    $repairCriteria = $repairCriteria -and ($groupAccepted -ge 2)
    $rawRepairs += $groupRaw; $acceptedRepairs += $groupAccepted; $calls += $groupCalls; $inputTokens += $evaluation.inputTokens; $outputTokens += $evaluation.outputTokens
}
# Bind counterevidence for the rejected executor-scope claim, independent of its verdict.
$originalSubscription = Read-Bound "verifications/$($report.retainedSubscriptionVerificationId)/spec.json"
[void](Read-Bound "verifications/$($report.retainedSubscriptionVerificationId)/result.json")
[void](Read-Bound "verifications/$($report.retainedSubscriptionVerificationId)/jobs/baseline-single-item.json")
Require ($originalSubscription.protocol -ceq 'subscription-period-v1') 'Changed counterevidence protocol.'
$missing = $report.missingEvidence; $missingPrefix = 'missing-evidence-evaluations/' + $missing.evaluationId
$missingSuite = Read-Bound "$missingPrefix/suite.json"; $missingResult = Read-Bound "$missingPrefix/result.json"
Require ($missing.evaluationId -ceq $missingReview.evaluationId -and $missingSuite.repetitions -eq 3 -and $missingSuite.maxModelCalls -eq 144 -and $missing.trials.Count -eq 12 -and $missingResult.trials.Count -eq 12) 'Invalid missing-evidence coverage.'
Require ($missing.model -ceq $missingSuite.model -and $missing.harnessSha256 -ceq $missingSuite.harnessSha256 -and $missing.inputTokens -eq $missingResult.inputTokens -and $missing.outputTokens -eq $missingResult.outputTokens) 'Missing-evidence configuration or usage changed.'
$cells = @{}; $supportedAbstentions = 0; $missingCalls = 0
for ($i = 0; $i -lt 12; $i++) {
    $row = $missing.trials[$i]; $original = $missingResult.trials[$i]; $judgment = $missingReview.trials[$i]
    Require ($row.trial -eq ($i + 1) -and $row.agentId -ceq $original.agentId -and $row.agentId -ceq $judgment.agentId -and (Compact $row.registration) -ceq (Compact $original.trial) -and $row.semanticVerdict -ceq $judgment.verdict) 'Missing-evidence trial changed.'
    $supported = $original.decisionCheck -ceq 'ABSTAINED_AFTER_WITHHELD_EVIDENCE' -and $judgment.verdict -ceq 'SUPPORTED_WITHIN_RECORDED_SCOPE'
    Require ($row.supportedAbstention -is [bool] -and $row.supportedAbstention -eq $supported -and $row.registeredModelCalls -eq $original.registeredModelCalls) 'Abstention credit or usage changed.'
    $key = $row.registration.caseKind + '/' + $row.registration.condition
    Require ($row.registration.caseKind -cin @('CUSTOMER_SOURCES','SUBSCRIPTION_PERIODS') -and $row.registration.condition -cin @('SOURCE_UNAVAILABLE','EXECUTION_UNAVAILABLE') -and $row.registration.repetition -in @(1,2,3)) 'Invalid withheld-evidence cell.'
    if (-not $cells.ContainsKey($key)) { $cells[$key] = [Collections.Generic.HashSet[int]]::new() }
    Require ($cells[$key].Add([int]$row.registration.repetition)) 'Duplicate withheld-evidence repetition.'
    if ($supported) { $supportedAbstentions++ }; $missingCalls += $row.registeredModelCalls
}
Require ($cells.Count -eq 4 -and @($cells.Values | Where-Object Count -NE 3).Count -eq 0 -and $missing.registeredModelCalls -eq $missingCalls -and $missing.maxRegisteredModelCalls -eq 144) 'Incomplete cells or request accounting.'
$calls += $missingCalls; $inputTokens += $missingResult.inputTokens; $outputTokens += $missingResult.outputTokens
Require ($report.historicalCustomerSuites.Count -eq 2) 'Historical unsuccessful suites must remain referenced.'
$historyIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($history in $report.historicalCustomerSuites) {
    Require ($history.evaluationId -cmatch '^evaluation-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and $historyIds.Add($history.evaluationId) -and $history.evaluationId -cnotin $report.sufficientEvidence.evaluationId) 'Invalid or duplicate historical evaluation.'
    $oldPrefix = 'customer-repair-evaluations/' + $history.evaluationId
    [void](Read-Bound "$oldPrefix/suite.json"); $old = Read-Bound "$oldPrefix/result.json"
    Require ($old.status -ceq 'COMPLETED' -and $history.plannedTrials -eq 3 -and (Compact $history.counts) -ceq (Compact $old.counts) -and $history.inputTokens -eq $old.inputTokens -and $history.outputTokens -eq $old.outputTokens) 'Historical results changed.'
}
Require ($expected.SetEquals([string[]]@($report.storeArtifacts.Keys))) 'Incomplete or unexpected acceptance artifact set.'
$conclusion = if (-not $repairCriteria -or $supportedAbstentions -ne 12) { 'CRITERIA_NOT_MET' } elseif ($blocked -gt 0) { 'CRITERIA_MET_WITH_EXPLICIT_BLOCK' } else { 'CRITERIA_MET' }
Require ($report.conclusion -ceq $conclusion -and $report.totals.rawCompleteRepairs -eq $rawRepairs -and $report.totals.acceptedRepairs -eq $acceptedRepairs -and $report.totals.blockedInvestigations -eq $blocked -and $report.totals.supportedAbstentions -eq $supportedAbstentions -and $report.totals.registeredModelCalls -eq $calls -and $report.totals.maxRegisteredModelCalls -eq 222 -and $report.totals.inputTokens -eq $inputTokens -and $report.totals.outputTokens -eq $outputTokens) 'Acceptance conclusion or totals changed.'
if ($RequireCriteriaMet) { Require ($conclusion -cne 'CRITERIA_NOT_MET') 'Milestone criteria are not met by the bound recorded judgments.' }
[ordered]@{ reportBindings='MATCHED'; conclusion=$conclusion; plannedTrials=18; rawCompleteRepairs=$rawRepairs; acceptedRepairs=$acceptedRepairs; blockedInvestigations=$blocked; supportedAbstentions=$supportedAbstentions; registeredModelCalls=$calls; scope='Checks record binding and applies recorded review judgments to bounded acceptance credit; does not judge semantic truth or authorize execution/publication.' } | ConvertTo-Json -Depth 5
