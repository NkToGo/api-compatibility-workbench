#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('export','read')][string]$Action = 'export',
    [Parameter(Mandatory)][string]$ReviewPath,
    [Parameter(Mandatory)][string]$RecordsPath,
    [string]$StateDirectory
)
$ErrorActionPreference='Stop'
$ccrAction=$Action
. (Join-Path $PSScriptRoot 'Verify-CoordinationSemanticReview.ps1') -ReviewPath $ReviewPath -StateDirectory $StateDirectory
$Action=$ccrAction

function CCR-Derive($Original,$Packet,$Verified) {
    RP-Require ($Original.formatVersion -eq 1 -and $Original.trials -is [Collections.IList] -and $Verified.status -ceq 'SEMANTIC_REVIEW_BINDINGS_MATCHED' -and
        $Verified.packetId -ceq $Packet.packetId -and $Verified.reviewSha256 -cmatch '^[0-9a-f]{64}$' -and
        $Verified.caseOutputs -eq $Packet.inventory.cases.Count -and $Verified.cases.Count -eq $Verified.caseOutputs) 'Missing validated review or complete export denominator.'
    $copy=ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Original -Depth 80) -AsHashtable -Depth 80
    $copy.formatVersion=2; $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $count=0
    for ($i=0;$i -lt $copy.trials.Count;$i++) {
        $trial=$copy.trials[$i]
        foreach ($output in $trial.cases) {
            $key=($i+1).ToString()+'/'+$output.caseId
            $sources=@($Packet.inventory.cases | Where-Object {$_.position -eq $i+1 -and $_.caseId -ceq $output.caseId})
            $reviews=@($Verified.cases | Where-Object {$_.position -eq $i+1 -and $_.caseId -ceq $output.caseId})
            RP-Require ($seen.Add($key) -and $sources.Count -eq 1 -and $reviews.Count -eq 1 -and $null -eq $output.review) 'Duplicate, missing or already reviewed export case.'
            $source=$sources[0]; $review=$reviews[0]
            RP-Require ($source.runId -ceq $trial.runId -and (RP-Same $source.slot $trial.slot) -and
                (RP-Same $source.inputIdentity $output.inputIdentity) -and (RP-Same $source.outputIdentity $output.outputIdentity) -and
                $source.response -ceq $output.response -and $review.arm -ceq $trial.slot.arm) 'Changed reviewed trial, slot, input or output binding.'
            RP-Require ($output.response -cin @('ANSWERED','ABSTAINED') -and $review.materialUnsupportedClaims -is [long] -and
                $review.materialUnsupportedClaims -ge 0 -and $review.materialUnsupportedClaims -le 100000 -and
                $review.materialUnresolvedClaims -is [long] -and $review.materialUnresolvedClaims -ge 0 -and $review.materialUnresolvedClaims -le 100000) 'Absent answer or invalid explicit claim counts.'
            $judgments=[ordered]@{}
            foreach ($criterion in $review.criterionJudgments) {
                RP-Require (-not $judgments.Contains($criterion.id) -and $criterion.judgment -cin @('SATISFIED','NOT_SATISFIED','UNRESOLVED')) 'Invalid exported criterion judgment.'
                $judgments[$criterion.id]=$criterion.judgment
            }
            RP-Require ($judgments.Count -gt 0) 'Missing exported criteria.'
            $output.review=[ordered]@{outputIdentity=$output.outputIdentity;reviewIdentity=@{sha256=$Verified.reviewSha256};claimAuditComplete=$true;
                materialUnsupportedClaims=$review.materialUnsupportedClaims;materialUnresolvedClaims=$review.materialUnresolvedClaims;judgments=$judgments}
            $count++
        }
    }
    RP-Require ($count -eq $Verified.caseOutputs) 'Export is missing planned cases.'
    # No operational field, old record or review judgment is rewritten by this export.
    $projection=ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $copy -Depth 80) -AsHashtable -Depth 80
    $projection.formatVersion=1
    foreach ($trial in $projection.trials) { foreach ($output in $trial.cases) { $output.review=$null } }
    RP-Require (RP-Same $projection $Original) 'Export changed original operational facts.'
    return $copy
}
function CCR-Bytes($Value) {
    return ,[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Value -Depth 80).Replace("`r`n","`n")+"`n")
}
function CCR-PublishOrMatch([string]$Path,$Value,[bool]$ReadOnly) {
    RP-Safe $Path
    $bytes=CCR-Bytes $Value
    RP-Require ($bytes.Length -le 1048576) 'Reviewed export exceeds 1 MiB.'
    if (Test-Path -LiteralPath $Path) {
        RP-Require ([Convert]::ToBase64String((RP-Bytes $Path 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Changed reviewed export bytes.'
    } else {
        RP-Require (-not $ReadOnly) 'Incomplete reviewed export; resume the same export to finalize it.'
        $pending=$Path+'.pending'; RP-Safe $pending
        if (Test-Path -LiteralPath $pending) {
            RP-Require ([Convert]::ToBase64String((RP-Bytes $pending 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Interrupted reviewed export differs; preserve it for inspection.'
        } else {
            $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try {$stream.Write($bytes);$stream.Flush($true)} finally {$stream.Dispose()}
        }
        [IO.File]::Move($pending,$Path)
    }
}
function Invoke-CoordinationReviewedExport {
    $recordsFile=[IO.Path]::GetFullPath($RecordsPath); $receiptFile=$recordsFile+'.receipt.json'
    RP-Safe $recordsFile; RP-Safe $receiptFile
    $verified=(& (Join-Path $PSScriptRoot 'Verify-CoordinationSemanticReview.ps1') -ReviewPath $ReviewPath -StateDirectory $StateDirectory | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80
    $review=RP-Json ([IO.Path]::GetFullPath($ReviewPath))
    $packetFile=RP-Path ('coordination-review-packets/'+$verified.packetId+'/packet.json')
    RP-Require ((RP-Hash ([IO.Path]::GetFullPath($ReviewPath))) -ceq $verified.reviewSha256 -and (RP-Hash $packetFile) -ceq $review.packetSha256) 'Changed export review seal.'
    $packet=RP-Json $packetFile; $campaign=$packet.inventory.campaignId
    $originalRelative='coordination-campaign-executions/'+$campaign+'/records.json'; $originalFile=RP-Path $originalRelative
    $originalSha=$packet.inventory.sourceArtifacts[$originalRelative]
    RP-Require ((RP-Hash $originalFile) -ceq $originalSha) 'Changed original campaign records.'
    $original=RP-Json $originalFile
    $records=CCR-Derive $original $packet $verified
    RP-Require ((RP-Hash $originalFile) -ceq $originalSha -and (RP-Hash $packetFile) -ceq $review.packetSha256 -and
        (RP-Hash ([IO.Path]::GetFullPath($ReviewPath))) -ceq $verified.reviewSha256) 'Review inputs changed during export.'
    if ($Action -ceq 'export') { [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($recordsFile)) }
    CCR-PublishOrMatch $recordsFile $records ($Action -ceq 'read')
    $receipt=[ordered]@{formatVersion=1;role='bound-reviewed-comparison-records';campaignId=$campaign;comparisonId=$packet.inventory.comparisonId;
        packetId=$packet.packetId;packetSha256=$review.packetSha256;reviewId=$verified.reviewId;reviewSha256=$verified.reviewSha256;
        originalRecordsSha256=$originalSha;recordsSha256=(RP-Hash $recordsFile);recordFormatVersion=2;caseOutputs=$verified.caseOutputs;
        reviewer=$review.reviewer;scope='Identity-bound supplied judgments; not authenticated semantic truth, routing or execution authorization.'}
    CCR-PublishOrMatch $receiptFile $receipt ($Action -ceq 'read')
    [ordered]@{status='REVIEWED_RECORDS_BOUND';campaignId=$campaign;comparisonId=$packet.inventory.comparisonId;caseOutputs=$verified.caseOutputs;
        reviewSha256=$verified.reviewSha256;recordsSha256=$receipt.recordsSha256;receiptSha256=(RP-Hash $receiptFile);
        newModelCalls=0;newConsumerExecutions=0} | ConvertTo-Json -Depth 10
}
if ($MyInvocation.InvocationName -ne '.') { Invoke-CoordinationReviewedExport }
