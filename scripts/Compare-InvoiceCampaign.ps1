#requires -Version 7.0
[CmdletBinding()]
param(
    [Alias('Action')][ValidateSet('publish','read')][string]$ReportAction='publish',
    [Parameter(Mandatory)][string]$ReportDirectory,
    [Parameter(Mandatory)][string]$ReviewPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewSha256,
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [Parameter(Mandatory)][ValidatePattern('^reviewpacket-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPacketSha256,
    [Parameter(Mandatory)][string]$ReviewEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StateDirectory
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Review-InvoiceCampaignClaims.ps1') -ReviewAction verify -ReviewPath $ReviewPath -ReviewSha256 $ReviewSha256 -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -ReviewPacketId $ReviewPacketId -ExpectedPacketSha256 $ExpectedPacketSha256 -ReviewEvidenceDirectory $ReviewEvidenceDirectory -EvidenceDirectory $EvidenceDirectory -StateDirectory $StateDirectory
$ReportDirectory=[IO.Path]::GetFullPath($ReportDirectory)
function ICP-Verify { ICR-Parse ((Invoke-InvoiceCampaignClaims) | Out-String) }
function ICP-Derive($Original,$Packet,$Review,$Verified) {
    ICR-Require ($Original.formatVersion -eq 1 -and $Original.trials.Count -eq 12 -and $Packet.inventory.trials.Count -eq 12 -and
        $Review.trials.Count -eq 12 -and $Verified.trials.Count -eq 12 -and $Verified.reviewedTrials -eq 12 -and
        $Verified.pendingUnits -eq 0 -and $Verified.pendingCriteria -eq 0 -and $Verified.status -ceq 'INVOICE_REVIEW_BINDINGS_MATCHED' -and
        $Verified.reviewSha256 -ceq $ReviewSha256 -and $Verified.packetSha256 -ceq $ExpectedPacketSha256 -and
        $Verified.campaignId -ceq $CampaignId -and $Verified.packetId -ceq $ReviewPacketId -and
        $Verified.expectedCampaignReceiptSha256 -ceq $ExpectedReceiptSha256 -and $Verified.criteriaTiming -ceq 'FROZEN_BEFORE_TRIALS' -and
        $Verified.campaignAcceptance -ceq 'NOT_ESTABLISHED') 'A complete bound campaign claim review is required.'
    $copy=ICR-Parse (ConvertTo-Json -InputObject $Original -Depth 80 -Compress)
    $copy.formatVersion=2;$applied=0;$absent=0
    for($i=0;$i -lt 12;$i++) {
        $trial=$copy.trials[$i];$source=$Packet.inventory.trials[$i];$row=$Review.trials[$i];$checked=$Verified.trials[$i]
        ICR-Require ($source.position -eq $i+1 -and $row.position -eq $i+1 -and $checked.position -eq $i+1 -and
            (ICR-Same $Original.trials[$i] $source.trial) -and (ICR-Same $row.source $source.source) -and
            $row.coverageComplete -eq $true -and $checked.coverageAttestedComplete -eq $true -and
            $checked.pendingUnits -eq 0 -and $checked.pendingCriteria -eq 0 -and $checked.arm -ceq $trial.slot.arm -and
            $trial.runId -ceq $source.source.runId -and $trial.cases.Count -eq 1 -and
            (ICR-Same $trial.cases[0] $row.output) -and $null -eq $trial.cases[0].review) 'Changed original trial, ordered slot, output or review binding.'
        $output=$trial.cases[0]
        if($output.response -ceq 'NOT_PROVIDED') {
            ICR-Require ($null -eq $output.outputIdentity) 'Missing answers cannot acquire output identities.'
            $absent++;continue
        }
        ICR-Require ($output.response -cin @('ANSWERED','ABSTAINED') -and $null -ne $output.outputIdentity) 'Invalid provided output.'
        $judgments=[ordered]@{}
        foreach($criterion in $row.criteria) {
            ICR-Require (-not $judgments.Contains($criterion.criterionId) -and $criterion.judgment -cin @('SATISFIED','NOT_SATISFIED','UNRESOLVED')) 'Incomplete or duplicate criterion review.'
            $judgments[$criterion.criterionId]=$criterion.judgment
        }
        ICR-Require ($judgments.Count -eq 6) 'Changed six-criterion membership.'
        foreach($key in @('endorsedMaterialUnsupported','endorsedMaterialUnresolved')) {
            ICR-Require ($checked[$key] -is [long] -and $checked[$key] -ge 0 -and $checked[$key] -le 100000) 'Invalid explicit reviewed claim count.'
        }
        $output.review=[ordered]@{outputIdentity=$output.outputIdentity;reviewIdentity=@{sha256=$ReviewSha256};claimAuditComplete=$true;
            materialUnsupportedClaims=$checked.endorsedMaterialUnsupported;materialUnresolvedClaims=$checked.endorsedMaterialUnresolved;judgments=$judgments}
        $applied++
    }
    $projection=ICR-Parse (ConvertTo-Json -InputObject $copy -Depth 80 -Compress);$projection.formatVersion=1
    foreach($trial in $projection.trials){foreach($output in $trial.cases){$output.review=$null}}
    ICR-Require ((ICR-Same $projection $Original) -and $applied+$absent -eq 12) 'Reviewed export changed original operational facts.'
    return @{records=$copy;providedOutputs=$applied;absentOutputs=$absent}
}
function ICP-Bytes($Value) { ,[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject (ICR-Canonical $Value) -Depth 80).Replace("`r`n","`n")+"`n") }
function ICP-Publish([string]$Path,$Value) {
    ICR-Safe $Path;$bytes=ICP-Bytes $Value;ICR-Require ($bytes.Length -le 1048576) 'Invoice comparison artifact exceeds 1 MiB.'
    if(Test-Path -LiteralPath $Path) { ICR-Require ([Convert]::ToBase64String((ICR-Bytes $Path 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Changed published invoice comparison artifact.';return }
    ICR-Require ($ReportAction -ceq 'publish') 'Incomplete comparison report; resume publication with unchanged inputs.'
    $pending=$Path+'.pending';ICR-Safe $pending
    if(Test-Path -LiteralPath $pending) { ICR-Require ([Convert]::ToBase64String((ICR-Bytes $pending 1048576)) -ceq [Convert]::ToBase64String($bytes)) 'Changed interrupted invoice comparison artifact.' }
    else { $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()} }
    [IO.File]::Move($pending,$Path)
}
function ICP-Evaluate([string]$Plan,[string]$Records) {
    $jar=Join-Path $icrRepository 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
    $classpath=$jar+[IO.Path]::PathSeparator+(Join-Path $icrRepository 'backend/target/dependency/*')
    $lines=@(& java -cp $classpath io.github.nktogo.apicompatibility.runner.CoordinationComparisonReport evaluate $Plan $Records 2>&1)
    ICR-Require ($LASTEXITCODE -eq 0) 'Read-only comparison assessment failed.'
    ICR-Parse ($lines -join "`n")
}
function ICP-CheckPins($Pins) { foreach($entry in $Pins.GetEnumerator()) { ICR-Require ((ICR-Hash $entry.Key) -ceq $entry.Value) 'Invoice comparison input changed during publication.' } }
function Invoke-InvoiceCampaignComparison {
    ICR-Safe $ReportDirectory
    foreach($source in @($EvidenceDirectory,$StateDirectory,$ReviewPath)) {
        ICR-Require (-not (ICCR-Within $source $ReportDirectory) -and -not (ICCR-Within $ReportDirectory $source)) 'Comparison reports must stay separate from original evidence, state and selected review.'
    }
    $verified=ICP-Verify
    $packetFile=Join-Path $StateDirectory ('invoice-campaign-review-packets/'+$ReviewPacketId+'/packet.json')
    ICR-Require ((ICR-Hash $packetFile) -ceq $ExpectedPacketSha256 -and (ICR-Hash $ReviewPath) -ceq $ReviewSha256) 'Changed selected packet or review.'
    $packet=ICR-Json $packetFile 2097152;$review=ICR-Json $ReviewPath 2097152
    $original=Join-Path $StateDirectory ('invoice-campaign-executions/'+$CampaignId+'/records.json')
    $comparison=$packet.inventory.comparisonId
    ICR-Require ($comparison -cmatch '^comparison-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') 'Invalid frozen comparison reference.'
    $plan=Join-Path $StateDirectory ('coordination-comparisons/'+$comparison+'/plan.json')
    $pins=@{};$pins[$packetFile]=$ExpectedPacketSha256;$pins[$ReviewPath]=$ReviewSha256
    foreach($path in @($original,$plan)) {
        $ref=ICR-Relative $path;ICR-Require ($packet.inventory.sourceArtifacts.Contains($ref)) 'Comparison source is outside the validated packet.'
        $pins[$path]=$packet.inventory.sourceArtifacts[$ref]
    }
    ICP-CheckPins $pins
    $derived=ICP-Derive (ICR-Json $original 1048576) $packet $review $verified
    if($ReportAction -ceq 'publish'){[void][IO.Directory]::CreateDirectory($ReportDirectory);ICR-Safe $ReportDirectory}
    $recordsFile=Join-Path $ReportDirectory 'records.json';$assessmentFile=Join-Path $ReportDirectory 'assessment.json';$receiptFile=Join-Path $ReportDirectory 'receipt.json'
    ICP-Publish $recordsFile $derived.records
    $result=ICP-Evaluate $plan $recordsFile
    ICR-Require ($result.formatVersion -eq 2 -and $result.planSha256 -ceq $pins[$plan] -and $result.recordsSha256 -ceq (ICR-Hash $recordsFile) -and
        $result.assessment.rows.Count -eq 12 -and $result.assessment.pairs.Count -eq 6 -and $result.assessment.routingDecision -ceq 'NOT_ESTABLISHED') 'Changed comparison result scope or identities.'
    ICP-CheckPins $pins
    ICP-Publish $assessmentFile $result
    $outputPins=@{};$outputPins[$recordsFile]=$result.recordsSha256
    $outputPins[$assessmentFile]=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((ICP-Bytes $result))).ToLowerInvariant()
    ICP-CheckPins $outputPins
    $receipt=[ordered]@{formatVersion=1;role='bound-invoice-campaign-comparison';campaignId=$CampaignId;comparisonId=$comparison;
        expectedCampaignReceiptSha256=$ExpectedReceiptSha256;packetId=$ReviewPacketId;packetSha256=$ExpectedPacketSha256;
        reviewId=$verified.reviewId;reviewSha256=$ReviewSha256;originalRecordsSha256=$pins[$original];planSha256=$pins[$plan];
        recordsSha256=$outputPins[$recordsFile];assessmentSha256=$outputPins[$assessmentFile];recordFormatVersion=2;
        plannedSlots=12;providedOutputsBound=$derived.providedOutputs;absentOutputsPreserved=$derived.absentOutputs;
        inspection=$verified;reviewer=$review.reviewer;scope='Bound supplied review and descriptive recorded resources; no authenticated semantic truth, routing, execution or release approval.'}
    ICP-Publish $receiptFile $receipt
    $outputPins[$receiptFile]=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((ICP-Bytes $receipt))).ToLowerInvariant()
    ICP-CheckPins $outputPins
    ICP-CheckPins $pins
    [ordered]@{status='INVOICE_COMPARISON_BOUND';campaignId=$CampaignId;comparisonId=$comparison;plannedSlots=12;providedOutputsBound=$derived.providedOutputs;
        absentOutputsPreserved=$derived.absentOutputs;conclusion=$result.assessment.conclusion;routingDecision='NOT_ESTABLISHED';
        reviewSha256=$ReviewSha256;recordsSha256=$receipt.recordsSha256;assessmentSha256=$receipt.assessmentSha256;receiptSha256=(ICR-Hash $receiptFile);
        newModelCalls=0;newConsumerExecutions=0} | ConvertTo-Json -Depth 8
}
if($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignComparison }
