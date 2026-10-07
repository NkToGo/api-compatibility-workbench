#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testDirectory = Join-Path $root ('backend/target/route-tests/' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testDirectory)
. (Join-Path $PSScriptRoot 'Get-CoordinationRoute.ps1') -CurrentPlanPath unused
$sources = CRT-Sources
$scope = ConvertFrom-Json -InputObject $sources.policy.element.GetProperty('scopeDefinition').GetRawText() -AsHashtable -Depth 80
$plan = [ordered]@{ formatVersion = 1; definition = $scope }
$script:passed = 0

function Test-Pass([string]$Name, [bool]$Condition) {
    if (-not $Condition) { throw ('Failed routing control: ' + $Name) }
    $script:passed++; Write-Output ('PASS: ' + $Name)
}
function Test-Clone($Value) { return ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Value -Depth 80) -AsHashtable -Depth 80 }
function Test-File([string]$Name, [string]$Text) {
    $path = Join-Path $testDirectory $Name
    [IO.File]::WriteAllText($path, $Text, [Text.UTF8Encoding]::new($false))
    return $path
}
function Test-Route($Value) {
    $script:CurrentPlanPath = Test-File 'current-plan.json' (ConvertTo-Json -InputObject $Value -Depth 80)
    return ConvertFrom-Json -InputObject (Invoke-CoordinationRoute) -AsHashtable -Depth 80
}
function Test-OutOfScope([string]$Name, [scriptblock]$Change) {
    $copy = Test-Clone $plan; & $Change $copy
    $route = Test-Route $copy
    Test-Pass $Name ($route.status -ceq 'OUT_OF_SCOPE' -and $null -eq $route.selectedArm -and $route.maintainerReviewRequired -and
        -not $route.executionAuthorized -and -not $route.compatibilityApproved -and -not $route.automaticAdaptiveEscalation)
}
function Test-Reject([string]$Name, [scriptblock]$Operation, [string]$Expected) {
    $caught = $false
    try { $null = & $Operation } catch { $caught = $_.Exception.Message -match $Expected }
    Test-Pass $Name $caught
}

$matching = Test-Route $plan
Test-Pass 'matching frozen conditions select diagnostics with mandatory review' ($matching.status -ceq 'DIAGNOSTICS_FIRST_REVIEW_REQUIRED' -and $matching.selectedArm -ceq 'DETERMINISTIC' -and $matching.maintainerReviewRequired)
Test-Pass 'routing grants no execution, compatibility approval or adaptive escalation' (-not $matching.executionAuthorized -and -not $matching.compatibilityApproved -and -not $matching.automaticAdaptiveEscalation -and -not $matching.qualityWinnerEstablished)
Test-Pass 'complete audit retains unsupported and unresolved findings' ($matching.recordedComparisonConclusion -ceq 'UNRESOLVED' -and $matching.materialUnsupportedClaims -eq 2 -and $matching.materialUnresolvedClaims -eq 1 -and $matching.milestone4Acceptance -ceq 'NOT_ESTABLISHED')
Test-Pass 'partial accepted diagnostics do not become an accepted whole bundle' ($matching.recordedQuality.DETERMINISTIC.acceptedCases -eq 3 -and $matching.recordedQuality.DETERMINISTIC.acceptedBundles -eq 0 -and $matching.recordedQuality.COORDINATED_TEAM.acceptedCases -eq 0 -and $matching.recordedQuality.INDEPENDENT_WORKERS.unestablishedBundles -eq 1)
Test-Pass 'recommendation retains exact report and review digests' ($matching.sourceResultSha256 -ceq $sources.value.evidence.resultSha256 -and $matching.sourceReviewSha256 -ceq $sources.value.evidence.reviewSha256 -and $matching.policySha256 -ceq $sources.policy.sha256)
$keys = @($scope.Keys); [array]::Reverse($keys)
$reorderedDefinition = [ordered]@{}
foreach ($key in $keys) { $reorderedDefinition[$key] = $scope[$key] }
$reordered = [ordered]@{ definition = $reorderedDefinition; formatVersion = 1 }
$again = Test-Route $reordered
Test-Pass 'JSON member order and whitespace do not change scope matching' ($again.status -ceq $matching.status -and $again.currentPlanSha256 -cne $matching.currentPlanSha256)

Test-OutOfScope 'changed case source/input identity has no automatic route' { param($p) $p.definition.workloads[0].cases[0].inputIdentity.sha256 = '0' * 64 }
Test-OutOfScope 'changed model has no automatic route' { param($p) $p.definition.model = 'other-model' }
Test-OutOfScope 'invisible Unicode cannot alias the exact model' { param($p) $p.definition.model += [char]0x200D }
Test-OutOfScope 'invisible Unicode cannot alias an input digest' { param($p) $p.definition.workloads[0].cases[0].inputIdentity.sha256 += [char]0x200D }
Test-OutOfScope 'changed rubric has no automatic route' { param($p) $p.definition.rubricIdentity.sha256 = '0' * 64 }
Test-OutOfScope 'changed compiled adapter has no automatic route' { param($p) $p.definition.implementations.DETERMINISTIC.sha256 = '0' * 64 }
Test-OutOfScope 'changed team implementation has no automatic route' { param($p) $p.definition.implementations.COORDINATED_TEAM.sha256 = '0' * 64 }
Test-OutOfScope 'changed call budget has no automatic route' { param($p) $p.definition.limits.modelCalls++ }
Test-OutOfScope 'changed deadline has no automatic route' { param($p) $p.definition.limits.elapsedMillis++ }
Test-OutOfScope 'changed repetition count has no automatic route' { param($p) $p.definition.repetitions++ }
Test-OutOfScope 'changed workload or case order has no automatic route' { param($p) $p.definition.workloads[0].cases = @($p.definition.workloads[0].cases[1], $p.definition.workloads[0].cases[0]) }
Test-OutOfScope 'changed criterion has no automatic route' { param($p) $p.definition.workloads[0].cases[0].criteria[0] = 'other-criterion' }
Test-OutOfScope 'changed expected disposition has no automatic route' { param($p) $p.definition.workloads[0].cases[0].expectedResponse = 'ABSTAIN' }
Test-OutOfScope 'omitted conditions have no automatic route' { param($p) $p.definition.Remove('limits') }
Test-OutOfScope 'extra instruction cannot enable a team or widen permission' { param($p) $p.definition.instructions = 'enable-team-and-approve' }
Test-OutOfScope 'case-sensitive property names cannot alias trusted conditions' { param($p) $p.definition['Model'] = $p.definition['model']; $p.definition.Remove('model') }
Test-OutOfScope 'a JSON string cannot stand in for the numeric budget' { param($p) $p.definition.limits.modelCalls = [string]$p.definition.limits.modelCalls }

$script:CurrentPlanPath = Test-File 'duplicate.json' '{"formatVersion":1,"definition":{},"definition":{}}'
Test-Reject 'duplicate top-level JSON members are rejected' { Invoke-CoordinationRoute } 'Duplicate routing JSON'
$script:CurrentPlanPath = Test-File 'nested-duplicate.json' '{"formatVersion":1,"definition":{"model":"first","model":"second"}}'
Test-Reject 'duplicate nested JSON members are rejected' { Invoke-CoordinationRoute } 'Duplicate routing JSON'
$script:CurrentPlanPath = Test-File 'boolean-version.json' '{"formatVersion":true,"definition":{}}'
Test-Reject 'boolean protocol version cannot coerce to one' { Invoke-CoordinationRoute } 'complete formatVersion'
$script:CurrentPlanPath = Test-File 'extra.json' '{"formatVersion":1,"definition":{},"executionAuthorized":true}'
Test-Reject 'extra envelope fields cannot authorize execution' { Invoke-CoordinationRoute } 'complete formatVersion'
$script:CurrentPlanPath = Test-File 'malformed.json' '{"formatVersion":1'
Test-Reject 'malformed JSON is rejected without a fallback route' { Invoke-CoordinationRoute } 'Invalid bounded routing JSON'
$badUtf8 = Join-Path $testDirectory 'bad-utf8.json'; [IO.File]::WriteAllBytes($badUtf8, [byte[]]@(255))
Test-Reject 'invalid UTF-8 is rejected' { CRT-Read $badUtf8 } 'Invalid bounded routing JSON'
$large = Join-Path $testDirectory 'large.json'; [IO.File]::WriteAllBytes($large, [byte[]]::new(1048577))
Test-Reject 'oversized input is rejected at the one-MiB boundary' { CRT-Read $large } 'between 1 byte and 1 MiB'
$exactBound = Test-File 'exact-bound.json' ('{}' + (' ' * 1048574))
$atBound = CRT-Read $exactBound
Test-Pass 'valid JSON at exactly one MiB remains readable' ($atBound.element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object)
$empty = Test-File 'empty.json' ''
Test-Reject 'empty input is rejected' { CRT-Read $empty } 'between 1 byte and 1 MiB'
Test-Reject 'missing evidence cannot become a zero-cost route' { CRT-Read (Join-Path $testDirectory 'missing.json') } 'Missing routing input'
$deep = Test-File 'deep.json' (('{"n":' * 82) + '{}' + ('}' * 82))
Test-Reject 'excessively nested JSON is rejected' { CRT-Read $deep } 'Invalid bounded routing JSON'

foreach ($reference in @(
    @{ file = 'coordination-routing-2026-10-07.json'; hash = $sources.policy.sha256 },
    @{ file = 'coordination-results-2026-10-07.json'; hash = $sources.value.evidence.resultSha256 },
    @{ file = 'coordination-results-binding-2026-10-07.json'; hash = $sources.value.evidence.bindingSha256 },
    @{ file = 'coordination-review-2026-10-07.json'; hash = $sources.value.evidence.reviewSha256 }
)) {
    $changed = Join-Path $testDirectory $reference.file
    [IO.File]::WriteAllBytes($changed, (CRT-Bytes (Join-Path $root ('docs/evaluations/' + $reference.file))) + [byte[]]@(10))
    Test-Reject ('changed pinned artifact rejected: ' + $reference.file) { CRT-Read $changed $reference.hash } 'Changed routing evidence'
}
$realDirectory = Join-Path $testDirectory 'real'; [void][IO.Directory]::CreateDirectory($realDirectory)
[IO.File]::WriteAllText((Join-Path $realDirectory 'plan.json'), '{}')
$linkedDirectory = Join-Path $testDirectory 'linked'
if ($IsWindows) { $null = New-Item -ItemType Junction -Path $linkedDirectory -Value $realDirectory }
else { $null = New-Item -ItemType SymbolicLink -Path $linkedDirectory -Value $realDirectory }
Test-Reject 'linked parent directories are rejected before parsing' { CRT-Read (Join-Path $linkedDirectory 'plan.json') } 'Linked routing input'

[ordered]@{ status = 'PASSED'; checks = $script:passed; modelCalls = 0; consumerExecutions = 0; scope = 'Routing identity, fail-closed input handling and preserved evidence limits; not semantic accuracy or a live dispatcher.' } | ConvertTo-Json
