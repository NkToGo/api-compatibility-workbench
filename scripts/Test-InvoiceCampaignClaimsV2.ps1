#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../backend/target/invoice-campaign-claim-v2-tests/'+[guid]::NewGuid().ToString('N'))))
$testEvidence=Join-Path $testRoot 'evidence';$testState=Join-Path $testRoot 'state';$testReviews=Join-Path $testRoot 'reviews'
foreach($dir in @($testEvidence,$testState,$testReviews)){[void][IO.Directory]::CreateDirectory($dir)}
if($IsWindows){[IO.File]::SetAttributes($testState,([IO.File]::GetAttributes($testState) -bor [IO.FileAttributes]::Hidden))}
$testCampaign='invoicecampaign-v2-'+[guid]::NewGuid().ToString();$testPacket='reviewpacket-v2-'+[guid]::NewGuid().ToString();$testPacketSha='1'*64;$testReceiptSha='2'*64
$packetDir=Join-Path $testState ('invoice-campaign-v2-review-packets/'+$testPacket);[void][IO.Directory]::CreateDirectory($packetDir)
[IO.File]::WriteAllText((Join-Path $packetDir 'packet.json'),'{"fixture":"synthetic packet-reader boundary","inventory":{"publicTaskSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}}')
[IO.File]::WriteAllText((Join-Path $testEvidence 'source.json'),'{"fixture":"original source"}')
$testReview=Join-Path $testReviews 'pending.json'
$testReviewRuntime=Join-Path $testRoot 'review-runtime'; [void][IO.Directory]::CreateDirectory((Join-Path $testReviewRuntime 'dependency'))
[IO.File]::WriteAllText((Join-Path $testReviewRuntime 'api-compatibility-backend-0.1.0-SNAPSHOT.jar'),'Synthetic review runtime identity; never executed.')
[IO.File]::WriteAllText((Join-Path $testReviewRuntime 'dependency/json.jar'),'Synthetic dependency identity; never executed.')
. (Join-Path $PSScriptRoot 'Review-InvoiceCampaignClaimsV2.ps1') -ReviewAction draft -ReviewPath $testReview -CampaignId $testCampaign -ExpectedReceiptSha256 $testReceiptSha -ReviewPacketId $testPacket -ExpectedPacketSha256 $testPacketSha -ReviewEvidenceDirectory $testRoot -EvidenceDirectory $testEvidence -StateDirectory $testState -ReviewRuntimeDirectory $testReviewRuntime
$script:checks=0;$script:reads=0;$script:engineCalls=0;$script:fault='NONE'
function ICCR2-ReadPacket {
    $script:reads++
    if($script:reads -eq 2 -and $script:fault -ceq 'REVIEW_CHANGED'){[IO.File]::AppendAllText($ReviewPath,' ')}
    return @{status='REVIEW_INPUTS_BOUND';packetSha256=$(if($script:reads -eq 2 -and $script:fault -ceq 'PACKET_CHANGED'){'f'*64}else{$testPacketSha});campaignId=$testCampaign;trials=12;qualityReview='NOT_PERFORMED'}
}
function ICCR2-Engine([string]$Mode,[string]$PacketFile,[string]$SelectedReviewSha) {
    $script:engineCalls++
    if($script:fault -ceq 'RUNTIME_CHANGED') { [IO.File]::AppendAllText((Join-Path $testReviewRuntime 'dependency/json.jar'),'changed') }
    if($Mode -ceq 'draft'){
        $bytes=[Text.Encoding]::UTF8.GetBytes('{"fixture":"synthetic pending review; not semantic judgments"}')
        $stream=[IO.File]::Open($ReviewPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try{$stream.Write($bytes)}finally{$stream.Dispose()};return
    }
    return @{publicTaskSha256=$(if($script:fault -ceq 'TASK_CHANGED'){'f'*64}else{'c'*64});status='INVOICE_REVIEW_BINDINGS_MATCHED';packetId=$testPacket;reviewSha256=$SelectedReviewSha;campaignId=$testCampaign;expectedCampaignReceiptSha256=$testReceiptSha;criteriaTiming='FROZEN_BEFORE_TRIALS';campaignAcceptance=$(if($script:fault -ceq 'ACCEPTANCE'){'ESTABLISHED'}else{'NOT_ESTABLISHED'});trials=@(1..12|ForEach-Object{@{position=$_;satisfiesDeclaredReview=$false}});pendingUnits=12;pendingCriteria=72;claimJudgments=0}
}
function Test-Pass([string]$Label,[bool]$Value){if(-not $Value){throw ('FAIL: '+$Label)};$script:checks++;Write-Output ('PASS: '+$Label)}
function Test-Reject([string]$Label,[scriptblock]$Operation){$caught=$false;try{& $Operation > $null}catch{$caught=$true};Test-Pass $Label $caught}
function Test-Invoke{$script:reads=0;(Invoke-InvoiceCampaignClaimsV2|Out-String)|ConvertFrom-Json -AsHashtable}
$sourceBefore=ICR2-Hash (Join-Path $testEvidence 'source.json');$packetBefore=ICR2-Hash (Join-Path $packetDir 'packet.json')
$draft=Test-Invoke;$digest=ICR2-Hash $testReview
Test-Pass 'draft returns pending metadata with no acceptance' ($draft.pendingUnits -eq 12 -and $draft.pendingCriteria -eq 72 -and $draft.claimJudgments -eq 0 -and $draft.campaignAcceptance -ceq 'NOT_ESTABLISHED' -and $draft.participantCallsInitiatedByThisCommand -eq 0)
Test-Pass 'draft validates the full packet before and after processing' ($script:reads -eq 2)
Test-Reject 'draft refuses an existing destination' {Test-Invoke}
$ReviewAction='verify';$ReviewSha256=$digest;$beforeEngine=$script:engineCalls;$verified=Test-Invoke
Test-Pass 'verification preserves supplied bytes and makes one engine call' ((ICR2-Hash $testReview) -ceq $digest -and $script:engineCalls -eq $beforeEngine+1 -and $verified.reviewSha256 -ceq $digest)
Test-Pass 'verification reports exact selected review-engine binaries' (ICR2-Same $verified.reviewRuntimeArtifacts (ICR2-Runtime $testReviewRuntime))
$script:fault='TASK_CHANGED';Test-Reject 'review result cannot substitute another public task' {Test-Invoke};$script:fault='NONE'
$runtimeFile=Join-Path $testReviewRuntime 'dependency/json.jar';$savedRuntime=[IO.File]::ReadAllBytes($runtimeFile);$script:fault='RUNTIME_CHANGED'
try{Test-Reject 'review runtime mutation during validation is refused' {Test-Invoke}}finally{[IO.File]::WriteAllBytes($runtimeFile,$savedRuntime);$script:fault='NONE'}
$originalRuntime=$ReviewRuntimeDirectory;$copiedRuntime=Join-Path $testRoot 'copied-review-runtime';[void][IO.Directory]::CreateDirectory((Join-Path $copiedRuntime 'dependency'))
foreach($ref in @('api-compatibility-backend-0.1.0-SNAPSHOT.jar','dependency/json.jar')){[IO.File]::Copy((Join-Path $testReviewRuntime $ref),(Join-Path $copiedRuntime $ref))}
$ReviewRuntimeDirectory=$copiedRuntime;$copied=Test-Invoke;$ReviewRuntimeDirectory=$originalRuntime
Test-Pass 'identical copied review runtime preserves bound review bytes' ($copied.reviewSha256 -ceq $digest -and (ICR2-Same $copied.reviewRuntimeArtifacts $verified.reviewRuntimeArtifacts))
$ReviewSha256='f'*64;Test-Reject 'wrong independent review digest is refused' {Test-Invoke};$ReviewSha256=$digest
$ExpectedPacketSha256='f'*64;Test-Reject 'wrong independent packet digest is refused' {Test-Invoke};$ExpectedPacketSha256=$testPacketSha
$ExpectedReceiptSha256='f'*64;Test-Reject 'wrong campaign receipt binding is refused' {Test-Invoke};$ExpectedReceiptSha256=$testReceiptSha
$script:fault='PACKET_CHANGED';Test-Reject 'packet mutation during inspection is refused' {Test-Invoke};$script:fault='NONE'
$saved=[IO.File]::ReadAllBytes($testReview);$script:fault='REVIEW_CHANGED';try{Test-Reject 'review mutation after validation is refused' {Test-Invoke}}finally{[IO.File]::WriteAllBytes($testReview,$saved);$script:fault='NONE'}
$script:fault='ACCEPTANCE';Test-Reject 'the wrapper never promotes campaign acceptance' {Test-Invoke};$script:fault='NONE'
$ReviewPath=Join-Path $testEvidence 'source.json';Test-Reject 'original evidence cannot be a review destination' {Test-Invoke}
$ReviewPath=Join-Path $testState 'review.json';Test-Reject 'original state stores cannot receive review records' {Test-Invoke}
$ReviewPath=$testReview;$link=Join-Path $testRoot 'linked-reviews'
if($IsWindows){$null=New-Item -ItemType Junction -Path $link -Target $testReviews}else{$null=New-Item -ItemType SymbolicLink -Path $link -Target $testReviews}
try{$ReviewPath=Join-Path $link 'pending.json';Test-Reject 'linked review paths are refused' {Test-Invoke}}finally{$ReviewPath=$testReview;Remove-Item -LiteralPath $link -Force}
$ReviewAction='draft';Test-Reject 'draft rejects a supplied review digest' {Test-Invoke}
Remove-Variable ReviewSha256;$ReviewSha256=$null;$ReviewAction='verify';Test-Reject 'verification requires an independent review digest' {Test-Invoke}
Test-Pass 'all original evidence packet and review bytes remain unchanged' ((ICR2-Hash (Join-Path $testEvidence 'source.json')) -ceq $sourceBefore -and (ICR2-Hash (Join-Path $packetDir 'packet.json')) -ceq $packetBefore -and (ICR2-Hash $testReview) -ceq $digest)
Write-Output ('Invoice v2 campaign claim-review controls passed: '+$script:checks+'; synthetic packet-reader/engine fixtures, no model calls or semantic judgments.')
