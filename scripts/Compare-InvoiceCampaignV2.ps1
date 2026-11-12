#requires -Version 7.0
[CmdletBinding()]
param(
    [Alias('Action')][ValidateSet('publish','read')][string]$ReportAction='publish',
    [Parameter(Mandatory)][string]$ReportDirectory,
    [Parameter(Mandatory)][string]$ReviewPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewSha256,
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [Parameter(Mandatory)][ValidatePattern('^reviewpacket-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPacketSha256,
    [Parameter(Mandatory)][string]$ReviewEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StateDirectory,
    [string]$RuntimeDirectory,
    [string]$InspectionRuntimeDirectory,
    [string]$ReviewRuntimeDirectory,
    [string]$ComparisonRuntimeDirectory
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Review-InvoiceCampaignClaimsV2.ps1') -ReviewAction verify -ReviewPath $ReviewPath -ReviewSha256 $ReviewSha256 -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -ReviewPacketId $ReviewPacketId -ExpectedPacketSha256 $ExpectedPacketSha256 -ReviewEvidenceDirectory $ReviewEvidenceDirectory -EvidenceDirectory $EvidenceDirectory -StateDirectory $StateDirectory -RuntimeDirectory $RuntimeDirectory -InspectionRuntimeDirectory $InspectionRuntimeDirectory -ReviewRuntimeDirectory $ReviewRuntimeDirectory
$ReportDirectory=[IO.Path]::GetFullPath($ReportDirectory)
if(-not $ComparisonRuntimeDirectory) { $ComparisonRuntimeDirectory=$ReviewRuntimeDirectory }
$ComparisonRuntimeDirectory=[IO.Path]::GetFullPath($ComparisonRuntimeDirectory)
function ICP2-Verify { ICR2-Parse ((Invoke-InvoiceCampaignClaimsV2) | Out-String) }
function ICP2-Derive($Original,$Packet,$Review,$Verified) {
    ICR2-Require ($Original.formatVersion -eq 1 -and $Packet.formatVersion -eq 2 -and $Review.formatVersion -eq 2 -and $Review.reviewId -cmatch '^semanticreview-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -and $Verified.reviewId -ceq $Review.reviewId -and
        $Verified.publicTaskSha256 -ceq $Packet.inventory.publicTaskSha256 -and $Review.publicTaskSha256 -ceq $Packet.inventory.publicTaskSha256 -and $Review.campaignId -ceq $CampaignId -and $Review.expectedCampaignReceiptSha256 -ceq $ExpectedReceiptSha256 -and $Original.trials.Count -eq 12 -and $Packet.inventory.trials.Count -eq 12 -and
        $Review.trials.Count -eq 12 -and $Verified.trials.Count -eq 12 -and $Verified.reviewedTrials -eq 12 -and
        $Verified.pendingUnits -eq 0 -and $Verified.pendingCriteria -eq 0 -and $Verified.status -ceq 'INVOICE_REVIEW_BINDINGS_MATCHED' -and
        $Verified.reviewSha256 -ceq $ReviewSha256 -and $Verified.packetSha256 -ceq $ExpectedPacketSha256 -and
        $Verified.campaignId -ceq $CampaignId -and $Verified.packetId -ceq $ReviewPacketId -and
        $Verified.expectedCampaignReceiptSha256 -ceq $ExpectedReceiptSha256 -and $Verified.criteriaTiming -ceq 'FROZEN_BEFORE_TRIALS' -and
        $Verified.campaignAcceptance -ceq 'NOT_ESTABLISHED') 'A complete bound campaign claim review is required.'
    $copy=ICR2-Parse (ConvertTo-Json -InputObject $Original -Depth 80 -Compress)
    $copy.formatVersion=2;$applied=0;$absent=0
    for($i=0;$i -lt 12;$i++) {
        $trial=$copy.trials[$i];$source=$Packet.inventory.trials[$i];$row=$Review.trials[$i];$checked=$Verified.trials[$i]
        ICR2-Require ($source.position -eq $i+1 -and $row.position -eq $i+1 -and $checked.position -eq $i+1 -and
            (ICR2-Same $Original.trials[$i] $source.trial) -and (ICR2-Same $row.source $source.source) -and
            $row.coverageComplete -eq $true -and $checked.coverageAttestedComplete -eq $true -and
            $checked.pendingUnits -eq 0 -and $checked.pendingCriteria -eq 0 -and $checked.arm -ceq $trial.slot.arm -and
            $trial.runId -ceq $source.source.runId -and $trial.cases.Count -eq 1 -and
            (ICR2-Same $trial.cases[0] $row.output) -and $null -eq $trial.cases[0].review) 'Changed original trial, ordered slot, output or review binding.'
        $output=$trial.cases[0]
        if($output.response -ceq 'NOT_PROVIDED') {
            ICR2-Require ($null -eq $output.outputIdentity) 'Missing answers cannot acquire output identities.'
            $absent++;continue
        }
        ICR2-Require ($output.response -cin @('ANSWERED','ABSTAINED') -and $null -ne $output.outputIdentity) 'Invalid provided output.'
        $judgments=[ordered]@{}
        foreach($criterion in $row.criteria) {
            ICR2-Require (-not $judgments.Contains($criterion.criterionId) -and $criterion.judgment -cin @('SATISFIED','NOT_SATISFIED','UNRESOLVED')) 'Incomplete or duplicate criterion review.'
            $judgments[$criterion.criterionId]=$criterion.judgment
        }
        ICR2-Require ($judgments.Count -eq 6) 'Changed six-criterion membership.'
        foreach($key in @('endorsedMaterialUnsupported','endorsedMaterialUnresolved')) {
            ICR2-Require ($checked[$key] -is [long] -and $checked[$key] -ge 0 -and $checked[$key] -le 100000) 'Invalid explicit reviewed claim count.'
        }
        $output.review=[ordered]@{outputIdentity=$output.outputIdentity;reviewIdentity=@{sha256=$ReviewSha256};claimAuditComplete=$true;
            materialUnsupportedClaims=$checked.endorsedMaterialUnsupported;materialUnresolvedClaims=$checked.endorsedMaterialUnresolved;judgments=$judgments}
        $applied++
    }
    $projection=ICR2-Parse (ConvertTo-Json -InputObject $copy -Depth 80 -Compress);$projection.formatVersion=1
    foreach($trial in $projection.trials){foreach($output in $trial.cases){$output.review=$null}}
    ICR2-Require ((ICR2-Same $projection $Original) -and $applied+$absent -eq 12) 'Reviewed export changed original operational facts.'
    return @{records=$copy;providedOutputs=$applied;absentOutputs=$absent}
}
function ICP2-Bytes($Value) { ,[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject (ICR2-Canonical $Value) -Depth 80).Replace("`r`n","`n")+"`n") }
function ICP2-Publish([string]$Path,$Value) {
    ICR2-Safe $Path;$bytes=ICP2-Bytes $Value;ICR2-Require ($bytes.Length -le 1048576) 'Invoice comparison artifact exceeds 1 MiB.'
    if(Test-Path -LiteralPath $Path) { ICR2-Require (-not (Test-Path -LiteralPath ($Path+'.pending'))) 'Published comparison has a pending sibling.'; ICR2-Require ([Convert]::ToBase64String((ICR2-Bytes $Path 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Changed published invoice comparison artifact.';return }
    ICR2-Require ($ReportAction -ceq 'publish') 'Incomplete comparison report; resume publication with unchanged inputs.'
    $pending=$Path+'.pending';ICR2-Safe $pending
    if(Test-Path -LiteralPath $pending) { ICR2-Require ([Convert]::ToBase64String((ICR2-Bytes $pending 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Changed interrupted invoice comparison artifact.' }
    else { $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()} }
    [IO.File]::Move($pending,$Path)
}
function ICP2-Evaluate([string]$Plan,[string]$Records) {
    ICR2-Require (ICR2-Same (ICR2-Runtime $ComparisonRuntimeDirectory) $script:icp2RuntimeArtifacts) 'Comparison runtime changed before evaluation.'
    $classpath=(Join-Path $ComparisonRuntimeDirectory 'api-compatibility-backend-0.1.0-SNAPSHOT.jar')+[IO.Path]::PathSeparator+(Join-Path $ComparisonRuntimeDirectory 'dependency/*')
    $lines=@(& java -cp $classpath io.github.nktogo.apicompatibility.runner.CoordinationComparisonReport evaluate $Plan $Records 2>&1)
    ICR2-Require ($LASTEXITCODE -eq 0) 'Read-only comparison assessment failed.'
    ICR2-Parse ($lines -join "`n")
}
function ICP2-CheckPins($Pins) { foreach($entry in $Pins.GetEnumerator()) { ICR2-Require ((ICR2-Hash $entry.Key) -ceq $entry.Value) 'Invoice comparison input changed during publication.' } }
function Invoke-InvoiceCampaignComparisonV2 {
    ICR2-Safe $ReportDirectory
    foreach($source in @($EvidenceDirectory,$StateDirectory,$ReviewPath)) {
        ICR2-Require (-not (ICCR2-Within $source $ReportDirectory) -and -not (ICCR2-Within $ReportDirectory $source)) 'Comparison reports must stay separate from original evidence, state and selected review.'
    }
    $script:icp2RuntimeArtifacts=ICR2-Runtime $ComparisonRuntimeDirectory
    $existingReceipt=Join-Path $ReportDirectory 'receipt.json'
    $retainedReceipt=$(if(Test-Path -LiteralPath $existingReceipt) { $existingReceipt } elseif(Test-Path -LiteralPath ($existingReceipt+'.pending')) { $existingReceipt+'.pending' })
    if($retainedReceipt) {
        $prior=ICR2-Json $retainedReceipt 1048576
        ICR2-Require ($prior.formatVersion -eq 2 -and $prior.role -ceq 'bound-invoice-v2-campaign-comparison' -and $prior.campaignId -ceq $CampaignId -and (ICR2-Same $prior.comparisonRuntimeArtifacts $script:icp2RuntimeArtifacts)) 'Changed selected comparison runtime or v2 report binding.'
    }
    $verified=ICP2-Verify
    $packetFile=Join-Path $StateDirectory ('invoice-campaign-v2-review-packets/'+$ReviewPacketId+'/packet.json')
    ICR2-Require ((ICR2-Hash $packetFile) -ceq $ExpectedPacketSha256 -and (ICR2-Hash $ReviewPath) -ceq $ReviewSha256) 'Changed selected packet or review.'
    $packet=ICR2-Json $packetFile 2097152;$review=ICR2-Json $ReviewPath 2097152
    $original=Join-Path $StateDirectory ('invoice-campaign-v2-executions/'+$CampaignId+'/records.json')
    $comparison=$packet.inventory.comparisonId
    ICR2-Require ($comparison -cmatch '^comparison-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid frozen comparison reference.'
    $plan=Join-Path $StateDirectory ('coordination-comparisons/'+$comparison+'/plan.json')
    $pins=@{};$pins[$packetFile]=$ExpectedPacketSha256;$pins[$ReviewPath]=$ReviewSha256
    foreach($path in @($original,$plan)) {
        $ref=ICR2-Relative $path;ICR2-Require ($packet.inventory.sourceArtifacts.Contains($ref)) 'Comparison source is outside the validated packet.'
        $pins[$path]=$packet.inventory.sourceArtifacts[$ref]
    }
    ICP2-CheckPins $pins
    $derived=ICP2-Derive (ICR2-Json $original 1048576) $packet $review $verified
    if($ReportAction -ceq 'publish'){[void][IO.Directory]::CreateDirectory($ReportDirectory);ICR2-Safe $ReportDirectory}
    ICR2-Require (Test-Path -LiteralPath $ReportDirectory -PathType Container) 'Missing v2 comparison report.'
    ICR2-Safe (Join-Path $ReportDirectory 'owner.lock')
    $lease=[IO.File]::Open((Join-Path $ReportDirectory 'owner.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
    $allowed=@('owner.lock','records.json','assessment.json','receipt.json','records.json.pending','assessment.json.pending','receipt.json.pending')
    foreach($item in (Get-ChildItem -LiteralPath $ReportDirectory -Force)) { ICR2-Safe $item.FullName; ICR2-Require (-not $item.PSIsContainer -and $item.Name -cin $allowed) 'Unexpected v2 comparison artifact.' }
    $recordsFile=Join-Path $ReportDirectory 'records.json';$assessmentFile=Join-Path $ReportDirectory 'assessment.json';$receiptFile=Join-Path $ReportDirectory 'receipt.json'
    ICP2-Publish $recordsFile $derived.records
    $result=ICP2-Evaluate $plan $recordsFile
    ICR2-Require ($result.formatVersion -eq 2 -and $result.planSha256 -ceq $pins[$plan] -and $result.recordsSha256 -ceq (ICR2-Hash $recordsFile) -and
        $result.assessment.rows.Count -eq 12 -and $result.assessment.pairs.Count -eq 6 -and $result.assessment.routingDecision -ceq 'NOT_ESTABLISHED') 'Changed comparison result scope or identities.'
    ICP2-CheckPins $pins
    ICP2-Publish $assessmentFile $result
    $outputPins=@{};$outputPins[$recordsFile]=$result.recordsSha256
    $outputPins[$assessmentFile]=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((ICP2-Bytes $result))).ToLowerInvariant()
    ICP2-CheckPins $outputPins
    $receipt=[ordered]@{formatVersion=2;role='bound-invoice-v2-campaign-comparison';campaignId=$CampaignId;comparisonId=$comparison;
        expectedCampaignReceiptSha256=$ExpectedReceiptSha256;packetId=$ReviewPacketId;packetSha256=$ExpectedPacketSha256;
        reviewId=$verified.reviewId;reviewSha256=$ReviewSha256;originalRecordsSha256=$pins[$original];planSha256=$pins[$plan];
        recordsSha256=$outputPins[$recordsFile];assessmentSha256=$outputPins[$assessmentFile];recordFormatVersion=2;
        plannedSlots=12;providedOutputsBound=$derived.providedOutputs;absentOutputsPreserved=$derived.absentOutputs;
        publicTaskSha256=$packet.inventory.publicTaskSha256;comparisonRuntimeArtifacts=$script:icp2RuntimeArtifacts;inspection=$verified;reviewer=$review.reviewer;scope='Bound supplied review and descriptive recorded resources; no authenticated semantic truth, routing, execution or release approval.'}
    ICR2-Require (ICR2-Same (ICR2-Runtime $ComparisonRuntimeDirectory) $script:icp2RuntimeArtifacts) 'Comparison runtime changed before receipt publication.'
    ICP2-Publish $receiptFile $receipt
    $outputPins[$receiptFile]=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((ICP2-Bytes $receipt))).ToLowerInvariant()
    ICP2-CheckPins $outputPins
    ICP2-CheckPins $pins
    ICR2-Require (ICR2-Same (ICR2-Runtime $ComparisonRuntimeDirectory) $script:icp2RuntimeArtifacts) 'Comparison runtime changed during publication.'
    [ordered]@{status='INVOICE_V2_COMPARISON_BOUND';campaignId=$CampaignId;comparisonId=$comparison;plannedSlots=12;providedOutputsBound=$derived.providedOutputs;
        absentOutputsPreserved=$derived.absentOutputs;conclusion=$result.assessment.conclusion;routingDecision='NOT_ESTABLISHED';
        reviewSha256=$ReviewSha256;recordsSha256=$receipt.recordsSha256;assessmentSha256=$receipt.assessmentSha256;receiptSha256=(ICR2-Hash $receiptFile);
        newModelCalls=0;newConsumerExecutions=0} | ConvertTo-Json -Depth 8
    } finally { $lease.Dispose() }
}
if($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignComparisonV2 }
