#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$CurrentPlanPath)
$ErrorActionPreference = 'Stop'
$routingRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function CRT-Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function CRT-Bytes([string]$Path) {
    $file = [IO.Path]::GetFullPath($Path)
    $node = $file
    while ($node) {
        CRT-Require ([IO.File]::Exists($node) -or [IO.Directory]::Exists($node)) 'Missing routing input.'
        CRT-Require (([IO.File]::GetAttributes($node) -band [IO.FileAttributes]::ReparsePoint) -eq 0) 'Linked routing input is not allowed.'
        $parent = [IO.Path]::GetDirectoryName($node)
        if ($parent -ceq $node) { break }
        $node = $parent
    }
    $stream = [IO.File]::Open($file, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        # Read at most one byte beyond the bound, including when a file grows during reading.
        $buffer = [byte[]]::new(1048577)
        $length = 0
        while ($length -lt $buffer.Length) {
            $read = $stream.Read($buffer, $length, $buffer.Length - $length)
            if ($read -eq 0) { break }
            $length += $read
        }
        CRT-Require ($length -gt 0 -and $length -le 1048576) 'Routing input must contain between 1 byte and 1 MiB.'
        [Array]::Resize([ref]$buffer, $length)
        return ,$buffer
    } finally { $stream.Dispose() }
}

function CRT-Hash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function CRT-Unique([System.Text.Json.JsonElement]$Element) {
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            CRT-Require ($names.Add($property.Name)) 'Duplicate routing JSON member.'
            CRT-Unique $property.Value
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        foreach ($item in $Element.EnumerateArray()) { CRT-Unique $item }
    }
}

function CRT-Read([string]$Path, [string]$ExpectedHash = '') {
    $bytes = CRT-Bytes $Path
    $hash = CRT-Hash $bytes
    CRT-Require (-not $ExpectedHash -or $hash -ceq $ExpectedHash) 'Changed routing evidence.'
    try {
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        $options = [System.Text.Json.JsonDocumentOptions]::new()
        $options.MaxDepth = 80
        $document = [System.Text.Json.JsonDocument]::Parse($text, $options)
    } catch { throw 'Invalid bounded routing JSON.' }
    try {
        CRT-Unique $document.RootElement
        CRT-Require ($document.RootElement.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) 'Routing JSON must be an object.'
        return @{ element = $document.RootElement.Clone(); sha256 = $hash }
    } finally { $document.Dispose() }
}

function CRT-Same([System.Text.Json.JsonElement]$Left, [System.Text.Json.JsonElement]$Right) {
    if ($Left.ValueKind -ne $Right.ValueKind) { return $false }
    switch ($Left.ValueKind.ToString()) {
        'Object' {
            $a = @($Left.EnumerateObject()); $b = @($Right.EnumerateObject())
            if ($a.Count -ne $b.Count) { return $false }
            foreach ($property in $a) {
                $other = [System.Text.Json.JsonElement]::new()
                if (-not $Right.TryGetProperty($property.Name, [ref]$other) -or -not (CRT-Same $property.Value $other)) { return $false }
            }
            return $true
        }
        'Array' {
            if ($Left.GetArrayLength() -ne $Right.GetArrayLength()) { return $false }
            $b = @($Right.EnumerateArray()); $index = 0
            foreach ($item in $Left.EnumerateArray()) {
                if (-not (CRT-Same $item $b[$index])) { return $false }
                $index++
            }
            return $true
        }
        'String' { return [string]::Equals($Left.GetString(), $Right.GetString(), [StringComparison]::Ordinal) }
        default { return $Left.GetRawText() -ceq $Right.GetRawText() }
    }
}

function CRT-Sources {
    $directory = Join-Path $routingRoot 'docs/evaluations'
    $policy = CRT-Read (Join-Path $directory 'coordination-routing-2026-10-07.json') 'e04581f542af5501213dff1210f8df89772bed7c659af3f8cbcb124c0a7eba34'
    $value = ConvertFrom-Json -InputObject $policy.element.GetRawText() -AsHashtable -Depth 80
    $report = CRT-Read (Join-Path $directory 'coordination-results-2026-10-07.json') $value.evidence.resultSha256
    $binding = CRT-Read (Join-Path $directory 'coordination-results-binding-2026-10-07.json') $value.evidence.bindingSha256
    # A pinned digest retains the declared review, not an independent semantic adjudication.
    $null = CRT-Read (Join-Path $directory 'coordination-review-2026-10-07.json') $value.evidence.reviewSha256
    $result = ConvertFrom-Json -InputObject $report.element.GetRawText() -AsHashtable -Depth 80
    $seal = ConvertFrom-Json -InputObject $binding.element.GetRawText() -AsHashtable -Depth 80
    CRT-Require ($value.formatVersion -eq 1 -and $result.formatVersion -eq 2 -and
        $result.comparisonId -ceq $value.evidence.comparisonId -and $result.reportId -ceq $value.evidence.reportId -and
        $seal.comparisonId -ceq $result.comparisonId -and $seal.reportId -ceq $result.reportId -and
        $seal.resultSha256 -ceq $report.sha256 -and $seal.assessedRecordsSha256 -ceq $result.recordsSha256 -and
        $seal.exportBinding.reviewSha256 -ceq $value.evidence.reviewSha256 -and
        (CRT-Same $policy.element.GetProperty('scopeDefinition') $report.element.GetProperty('assessment').GetProperty('definition'))) 'Routing evidence bindings do not match.'
    CRT-Require ($value.route -ceq 'DIAGNOSTICS_FIRST_REVIEW_REQUIRED' -and $value.selectedArm -ceq 'DETERMINISTIC' -and
        $result.assessment.routingDecision -ceq 'NOT_ESTABLISHED') 'Unsupported routing policy.'
    return @{ policy = $policy; value = $value; report = $result }
}

function Invoke-CoordinationRoute {
    $sources = CRT-Sources
    $current = CRT-Read $CurrentPlanPath
    $element = $current.element
    $fields = @($element.EnumerateObject())
    $version = [System.Text.Json.JsonElement]::new(); $definition = [System.Text.Json.JsonElement]::new()
    CRT-Require ($fields.Count -eq 2 -and $element.TryGetProperty('formatVersion', [ref]$version) -and
        $version.ValueKind -eq [System.Text.Json.JsonValueKind]::Number -and $version.GetRawText() -ceq '1' -and
        $element.TryGetProperty('definition', [ref]$definition) -and $definition.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) 'Current conditions require a complete formatVersion 1 comparison plan.'
    $matches = CRT-Same $definition $sources.policy.element.GetProperty('scopeDefinition')
    $arm = $null; $status = 'OUT_OF_SCOPE'; $reason = 'CHANGED_COMPARISON_CONDITIONS'
    if ($matches) { $arm = 'DETERMINISTIC'; $status = 'DIAGNOSTICS_FIRST_REVIEW_REQUIRED'; $reason = 'NO_ESTABLISHED_COORDINATION_BENEFIT_FOR_THIS_SCOPE' }
    $quality = [ordered]@{}
    foreach ($name in @('DETERMINISTIC', 'SINGLE_AGENT', 'INDEPENDENT_WORKERS', 'COORDINATED_TEAM')) {
        $metrics = $sources.report.assessment.metrics[$name]
        $quality[$name] = [ordered]@{ acceptedBundles = $metrics.reviewedAcceptedBundles; acceptedCases = $metrics.reviewedAcceptedCasesForUnambiguousSlots; unestablishedBundles = $metrics.plannedBundles - $metrics.comparableBundles }
    }
    $unsupported = 0; $unresolved = 0
    foreach ($row in $sources.report.assessment.rows) { foreach ($case in $row.cases) { $unsupported += $case.materialUnsupportedClaims; $unresolved += $case.materialUnresolvedClaims } }
    [ordered]@{
        formatVersion = 1; policyId = $sources.value.policyId; policySha256 = $sources.policy.sha256
        currentPlanSha256 = $current.sha256; status = $status; selectedArm = $arm; reason = $reason
        sourceComparisonId = $sources.value.evidence.comparisonId; sourceReportId = $sources.value.evidence.reportId
        sourceResultSha256 = $sources.value.evidence.resultSha256; sourceReviewSha256 = $sources.value.evidence.reviewSha256
        recordedComparisonConclusion = $sources.report.assessment.conclusion; recordedQuality = $quality
        materialUnsupportedClaims = $unsupported; materialUnresolvedClaims = $unresolved
        qualityWinnerEstablished = $false; maintainerReviewRequired = $true; automaticAdaptiveEscalation = $false
        executionAuthorized = $false; compatibilityApproved = $false; milestone4Acceptance = 'NOT_ESTABLISHED'
        semanticScope = $sources.value.semanticScope
        authority = 'Read-only recommendation about supplied plan metadata; not live inventory, dispatch authorization or release approval.'
    } | ConvertTo-Json -Depth 15
}

if ($MyInvocation.InvocationName -ne '.') { Invoke-CoordinationRoute }
