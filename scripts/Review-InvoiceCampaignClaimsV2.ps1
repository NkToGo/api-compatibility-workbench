#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('draft','verify')][string]$ReviewAction='verify',
    [Parameter(Mandatory)][string]$ReviewPath,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewSha256,
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [Parameter(Mandatory)][ValidatePattern('^reviewpacket-v2-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPacketSha256,
    [Parameter(Mandatory)][string]$ReviewEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StateDirectory,
    [string]$RuntimeDirectory,
    [string]$InspectionRuntimeDirectory,
    [string]$ReviewRuntimeDirectory
)
$ErrorActionPreference='Stop'
# Reuse the historical read boundary, strict parser and safe path checks without publication.
. (Join-Path $PSScriptRoot 'Prepare-InvoiceCampaignReviewV2.ps1') -Action read -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -ReviewPacketId $ReviewPacketId -ExpectedPacketSha256 $ExpectedPacketSha256 -ReviewEvidenceDirectory $ReviewEvidenceDirectory -EvidenceDirectory $EvidenceDirectory -StateDirectory $StateDirectory -RuntimeDirectory $RuntimeDirectory -InspectionRuntimeDirectory $InspectionRuntimeDirectory
$ReviewPath=[IO.Path]::GetFullPath($ReviewPath)
if(-not $ReviewRuntimeDirectory) { $ReviewRuntimeDirectory=Join-Path $icr2Repository 'backend/target' }
$ReviewRuntimeDirectory=[IO.Path]::GetFullPath($ReviewRuntimeDirectory)
function ICCR2-ReadPacket { (Invoke-InvoiceCampaignReviewV2 | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80 }
function ICCR2-Within([string]$Root,[string]$Path) {
    $relative=[IO.Path]::GetRelativePath($Root,$Path).Replace('\','/')
    return $relative -eq '.' -or (-not [IO.Path]::IsPathRooted($relative) -and $relative -ne '..' -and -not $relative.StartsWith('../',[StringComparison]::Ordinal))
}
function ICCR2-Engine([string]$Mode,[string]$PacketFile,[string]$SelectedReviewSha) {
    ICR2-Require (ICR2-Same (ICR2-Runtime $ReviewRuntimeDirectory) $script:iccr2ReviewArtifacts) 'Review runtime changed during validation.'
    $classpath=(Join-Path $ReviewRuntimeDirectory 'api-compatibility-backend-0.1.0-SNAPSHOT.jar')+[IO.Path]::PathSeparator+(Join-Path $ReviewRuntimeDirectory 'dependency/*')
    $arguments=@($Mode,$icr2Root,$PacketFile,$ExpectedPacketSha256,$ReviewPath)
    if($Mode -ceq 'verify') { $arguments+=$SelectedReviewSha }
    $lines=@(& java -cp $classpath io.github.nktogo.apicompatibility.runner.InvoiceCampaignSemanticReviewV2 @arguments 2>&1)
    ICR2-Require ($LASTEXITCODE -eq 0) 'Campaign claim-review bindings failed; preserve the selected review and original evidence.'
    if($Mode -ceq 'verify') { return ICR2-Parse ($lines -join "`n") }
}
function Invoke-InvoiceCampaignClaimsV2 {
    ICR2-Require (($ReviewAction -ceq 'draft' -and -not $ReviewSha256) -or ($ReviewAction -ceq 'verify' -and [bool]$ReviewSha256)) 'Draft accepts no review digest; verify requires an independently retained ReviewSha256.'
    ICR2-Safe $ReviewPath
    ICR2-Require (-not (ICCR2-Within $EvidenceDirectory $ReviewPath) -and -not (ICCR2-Within $StateDirectory $ReviewPath)) 'Review records must remain outside original evidence and state stores.'
    ICR2-Require ($ReviewAction -cne 'draft' -or -not (Test-Path -LiteralPath $ReviewPath)) 'Review drafts are create-only; preserve existing versions.'
    $script:iccr2ReviewArtifacts=ICR2-Runtime $ReviewRuntimeDirectory
    $before=ICCR2-ReadPacket
    ICR2-Require ($before.status -ceq 'REVIEW_INPUTS_BOUND' -and $before.packetSha256 -ceq $ExpectedPacketSha256 -and $before.campaignId -ceq $CampaignId -and $before.trials -eq 12 -and $before.qualityReview -ceq 'NOT_PERFORMED') 'Campaign review inputs differ from selected identities.'
    $packet=Join-Path $StateDirectory ('invoice-campaign-v2-review-packets/'+$ReviewPacketId+'/packet.json')
    if($ReviewAction -ceq 'draft') { $null=ICCR2-Engine 'draft' $packet ''; $selectedSha=ICR2-Hash $ReviewPath }
    else { $selectedSha=$ReviewSha256 }
    ICR2-Require ((ICR2-Hash $ReviewPath) -ceq $selectedSha) 'Changed independently selected review bytes.'
    $result=ICCR2-Engine 'verify' $packet $selectedSha
    ICR2-Require ($result.status -ceq 'INVOICE_REVIEW_BINDINGS_MATCHED' -and $result.packetId -ceq $ReviewPacketId -and $result.reviewSha256 -ceq $selectedSha -and $result.campaignId -ceq $CampaignId -and $result.expectedCampaignReceiptSha256 -ceq $ExpectedReceiptSha256 -and $result.criteriaTiming -ceq 'FROZEN_BEFORE_TRIALS' -and $result.campaignAcceptance -ceq 'NOT_ESTABLISHED' -and $result.trials.Count -eq 12) 'Changed campaign claim-review result scope.'
    ICR2-Require ((ICR2-Same (ICCR2-ReadPacket) $before) -and (ICR2-Hash $ReviewPath) -ceq $selectedSha) 'Campaign inputs or supplied review changed during inspection.'
    ICR2-Require (ICR2-Same (ICR2-Runtime $ReviewRuntimeDirectory) $script:iccr2ReviewArtifacts) 'Review runtime changed before completion.'
    $boundPacket=ICR2-Json $packet 2097152
    ICR2-Require ($result.publicTaskSha256 -ceq $boundPacket.inventory.publicTaskSha256) 'Review public task binding changed.'
    $result.reviewRuntimeArtifacts=$script:iccr2ReviewArtifacts
    $result.packetSha256=$ExpectedPacketSha256
    $result.participantCallsInitiatedByThisCommand=0
    $result | ConvertTo-Json -Depth 12
}
if($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignClaimsV2 }
