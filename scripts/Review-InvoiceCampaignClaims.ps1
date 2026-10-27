#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('draft','verify')][string]$ReviewAction='verify',
    [Parameter(Mandatory)][string]$ReviewPath,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewSha256,
    [Parameter(Mandatory)][ValidatePattern('^invoicecampaign-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$CampaignId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedReceiptSha256,
    [Parameter(Mandatory)][ValidatePattern('^reviewpacket-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')][string]$ReviewPacketId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedPacketSha256,
    [Parameter(Mandatory)][string]$ReviewEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StateDirectory
)
$ErrorActionPreference='Stop'
# Reuse the historical read boundary, strict parser and safe path checks without publication.
. (Join-Path $PSScriptRoot 'Prepare-InvoiceCampaignReview.ps1') -Action read -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -ReviewPacketId $ReviewPacketId -ExpectedPacketSha256 $ExpectedPacketSha256 -ReviewEvidenceDirectory $ReviewEvidenceDirectory -EvidenceDirectory $EvidenceDirectory -StateDirectory $StateDirectory
$ReviewPath=[IO.Path]::GetFullPath($ReviewPath)
function ICCR-ReadPacket { (Invoke-InvoiceCampaignReview | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80 }
function ICCR-Within([string]$Root,[string]$Path) {
    $relative=[IO.Path]::GetRelativePath($Root,$Path).Replace('\','/')
    return $relative -eq '.' -or (-not [IO.Path]::IsPathRooted($relative) -and $relative -ne '..' -and -not $relative.StartsWith('../',[StringComparison]::Ordinal))
}
function ICCR-Engine([string]$Mode,[string]$PacketFile,[string]$SelectedReviewSha) {
    $jar=Join-Path $icrRepository 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar'
    $newest=Get-ChildItem -LiteralPath (Join-Path $icrRepository 'backend/src/main') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    ICR-Require ($newest.LastWriteTimeUtc -le (Get-Item -LiteralPath $jar).LastWriteTimeUtc) 'Backend JAR is stale; rebuild before campaign claim review.'
    $classpath=$jar+[IO.Path]::PathSeparator+(Join-Path $icrRepository 'backend/target/dependency/*')
    $arguments=@($Mode,$icrRoot,$PacketFile,$ExpectedPacketSha256,$ReviewPath)
    if($Mode -ceq 'verify') { $arguments+=$SelectedReviewSha }
    $lines=@(& java -cp $classpath io.github.nktogo.apicompatibility.runner.InvoiceCampaignSemanticReview @arguments 2>&1)
    ICR-Require ($LASTEXITCODE -eq 0) 'Campaign claim-review bindings failed; preserve the selected review and original evidence.'
    if($Mode -ceq 'verify') { return ICR-Parse ($lines -join "`n") }
}
function Invoke-InvoiceCampaignClaims {
    ICR-Require (($ReviewAction -ceq 'draft' -and -not $ReviewSha256) -or ($ReviewAction -ceq 'verify' -and [bool]$ReviewSha256)) 'Draft accepts no review digest; verify requires an independently retained ReviewSha256.'
    ICR-Safe $ReviewPath
    ICR-Require (-not (ICCR-Within $EvidenceDirectory $ReviewPath) -and -not (ICCR-Within $StateDirectory $ReviewPath)) 'Review records must remain outside original evidence and state stores.'
    ICR-Require ($ReviewAction -cne 'draft' -or -not (Test-Path -LiteralPath $ReviewPath)) 'Review drafts are create-only; preserve existing versions.'
    $before=ICCR-ReadPacket
    ICR-Require ($before.status -ceq 'REVIEW_INPUTS_BOUND' -and $before.packetSha256 -ceq $ExpectedPacketSha256 -and $before.campaignId -ceq $CampaignId -and $before.trials -eq 12 -and $before.qualityReview -ceq 'NOT_PERFORMED') 'Campaign review inputs differ from selected identities.'
    $packet=Join-Path $StateDirectory ('invoice-campaign-review-packets/'+$ReviewPacketId+'/packet.json')
    if($ReviewAction -ceq 'draft') { $null=ICCR-Engine 'draft' $packet ''; $selectedSha=ICR-Hash $ReviewPath }
    else { $selectedSha=$ReviewSha256 }
    ICR-Require ((ICR-Hash $ReviewPath) -ceq $selectedSha) 'Changed independently selected review bytes.'
    $result=ICCR-Engine 'verify' $packet $selectedSha
    ICR-Require ($result.status -ceq 'INVOICE_REVIEW_BINDINGS_MATCHED' -and $result.packetId -ceq $ReviewPacketId -and $result.reviewSha256 -ceq $selectedSha -and $result.campaignId -ceq $CampaignId -and $result.expectedCampaignReceiptSha256 -ceq $ExpectedReceiptSha256 -and $result.criteriaTiming -ceq 'FROZEN_BEFORE_TRIALS' -and $result.campaignAcceptance -ceq 'NOT_ESTABLISHED' -and $result.trials.Count -eq 12) 'Changed campaign claim-review result scope.'
    ICR-Require ((ICR-Same (ICCR-ReadPacket) $before) -and (ICR-Hash $ReviewPath) -ceq $selectedSha) 'Campaign inputs or supplied review changed during inspection.'
    $result.packetSha256=$ExpectedPacketSha256
    $result.participantCallsInitiatedByThisCommand=0
    $result | ConvertTo-Json -Depth 12
}
if($MyInvocation.InvocationName -ne '.') { Invoke-InvoiceCampaignClaims }
