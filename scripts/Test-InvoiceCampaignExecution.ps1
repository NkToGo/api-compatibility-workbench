#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskTestRoot=Join-Path $taskRoot ('backend/target/invoice-campaign-execution-tests/'+[guid]::NewGuid().ToString('N'))
$taskState=Join-Path $taskTestRoot '.workbench'; [void][IO.Directory]::CreateDirectory($taskState)
if ($IsWindows) { [IO.File]::SetAttributes($taskState,([IO.File]::GetAttributes($taskState) -bor [IO.FileAttributes]::Hidden)) }
function Test-Write([string]$Path,$Value) { [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 80).Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false)) }
function Test-Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Test-Fixture {
    $directory=Join-Path $taskTestRoot 'inputs'; [void][IO.Directory]::CreateDirectory($directory)
    [IO.File]::WriteAllText((Join-Path $directory '0001.txt'),'Synthetic controller fixture; no measured findings.',[Text.UTF8Encoding]::new($false))
    Test-Write (Join-Path $directory 'manifest.json') @{formatVersion=1;caseId='stripe-invoice-subscription-2025-03-31';policy='invoice-comparison-observed-inputs-v1';authority='captured-synthetic-inputs-and-observations';sourceCaptureIdentity=('a'*64);sourcePlanIdentity=('b'*64);sourceExecutionCoverageSatisfied=$false;disclosureImplementationIdentity=('c'*64);artifacts=@(@{alias='task';file='0001.txt';sha256=(Test-Hash (Join-Path $directory '0001.txt'))})}
    $identity=Test-Hash (Join-Path $directory 'manifest.json'); Test-Write (Join-Path $directory 'receipt.json') @{manifestSha256=$identity}
    $contract=Join-Path $taskTestRoot 'contract'; [void][IO.Directory]::CreateDirectory($contract)
    foreach ($name in @('README.md','business-oracle.md','provenance.json')) { [IO.File]::WriteAllText((Join-Path $contract $name),'Synthetic contract metadata; not measured findings.',[Text.UTF8Encoding]::new($false)) }
    [IO.File]::Copy((Join-Path $taskRoot 'docs/evaluations/invoice-review-rubric-v1.json'),(Join-Path $taskTestRoot 'rubric.json'))
    $contractOutput=(& (Join-Path $PSScriptRoot 'Prepare-InvoiceReview.ps1') -Action contract -EvidenceDirectory $taskTestRoot -StateDirectory $taskState -ContractDirectory contract | Out-String)
    $contractIdentity=[regex]::Match($contractOutput,'[0-9a-f]{64}').Value
    if (-not $contractIdentity) { throw 'Missing synthetic contract identity.' }
    Test-Write (Join-Path $taskTestRoot 'selection.json') @{formatVersion=1;inputDirectory='inputs';inputIdentity=@{sha256=$identity};contractDirectory='contract';contractIdentity=@{sha256=$contractIdentity};rubricArtifact='rubric.json';rubricIdentity=@{sha256=(Test-Hash (Join-Path $taskTestRoot 'rubric.json'))}}
    return $identity
}
$taskBundle=Test-Fixture
function Test-NewCampaign {
    $r=(& (Join-Path $PSScriptRoot 'Invoice-Campaign.ps1') -SelectionPath (Join-Path $taskTestRoot 'selection.json') -EvidenceDirectory $taskTestRoot -StateDirectory $taskState | Out-String) | ConvertFrom-Json -AsHashtable
    $script:CampaignId=$r.campaignId; $script:ExpectedReceiptSha256=$r.receiptSha256; $script:Action='run'; $script:MaximumTrials=1; $script:taskFault='NONE'
}
Test-NewCampaign
. (Join-Path $PSScriptRoot 'Run-InvoiceCampaign.ps1') -CampaignId $CampaignId -ExpectedReceiptSha256 $ExpectedReceiptSha256 -EvidenceDirectory $taskTestRoot -StateDirectory $taskState -MaximumTrials 1
$taskRealAdapter=(Get-Command IE-Adapter).ScriptBlock
$taskRegistrations=0; $taskRuns=0; $taskPassed=0; $taskFault='NONE'
function Test-Key([string]$Text) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant() }
function Test-Complete([string]$Arm,[string]$Id) {
    $runtime=Join-Path $taskState ('invoice-comparison-trials/'+$Id); $adapter=Join-Path $taskState ($ieAdapters[$Arm].store+'/'+$Id); $spec=IE-Json (Join-Path $runtime 'spec.json')
    $startPath=Join-Path $runtime 'started.json'
    if (-not (Test-Path -LiteralPath $startPath)) { $at=[DateTimeOffset]::UtcNow; Test-Write $startPath @{runId=$Id;specSha256=(Test-Hash (Join-Path $runtime 'spec.json'));startedAt=$at.ToString('O');deadline=$at.AddMilliseconds($spec.definition.limits.elapsedMillis).ToString('O')} }
    if ($script:taskFault -ceq 'AFTER_START') { $script:taskFault='NONE'; throw 'Synthetic interruption after start.' }
    $hashes=[ordered]@{}; foreach ($name in @('spec.json','grant.json','started.json')) { $hashes[$name]=Test-Hash (Join-Path $runtime $name) }
    $usage=[ordered]@{}; foreach ($f in @('reservedCalls','registeredCalls','completedResponses','notDispatchedSlots','unresolvedUsageSlots','inputTokens','outputTokens','toolCalls','caseExecutions')) { $usage[$f]=[long]0 }
    foreach ($p in $spec.policy.participants) {
        $read='reads/'+(Test-Key ($p.id+'read'))+'.json'; Test-Write (Join-Path $runtime $read) @{participant=$p.id}; $hashes[$read]=Test-Hash (Join-Path $runtime $read); $usage.toolCalls++
        if ($Arm -ceq 'DETERMINISTIC') { continue }
        for ($i=0;$i -lt $(if ($Arm -ceq 'SINGLE_AGENT') {2} else {1});$i++) {
            $reservation='calls/'+(Test-Key ($p.id+[string]$i))+'.json'; $call='model-'+[guid]::NewGuid().ToString(); $broker=Join-Path $runtime ('broker/'+$call); [void][IO.Directory]::CreateDirectory($broker)
            Test-Write (Join-Path $runtime $reservation) @{participant=$p.id;operationId=('synthetic-'+$i)}; $hashes[$reservation]=Test-Hash (Join-Path $runtime $reservation)
            $mapping=$reservation.Substring(0,$reservation.Length-5)+'-mapping.json'; Test-Write (Join-Path $runtime $mapping) @{callId=$call}; $hashes[$mapping]=Test-Hash (Join-Path $runtime $mapping)
            $response=if ($i -eq 1) { @{callId=$call;status='UNKNOWN_REMOTE_OUTCOME';remoteOutcome='UNKNOWN'} } else { @{callId=$call;status='COMPLETED';inputTokens=10;outputTokens=2} }
            Test-Write (Join-Path $broker 'result.json') $response; $hashes['broker/'+$call+'/result.json']=Test-Hash (Join-Path $broker 'result.json')
            $usage.reservedCalls++; $usage.registeredCalls++
            if ($i -eq 1) { $usage.unresolvedUsageSlots++ } else { $usage.completedResponses++; $usage.inputTokens+=10; $usage.outputTokens+=2 }
        }
    }
    $start=IE-Json $startPath; $finished=[DateTimeOffset]::UtcNow; $status=if ($Arm -ceq 'SINGLE_AGENT') {'UNKNOWN_REMOTE_OUTCOME'} else {'COMPLETED'}
    $trace=@{formatVersion=1;runId=$Id;slot=$spec.slot;usage=$usage;status=$status;elapsedMillis=[long]($finished-(IE-Time $start.startedAt)).TotalMilliseconds;finishedAt=$finished.ToString('O');artifactSha256=$hashes;resourceIssues=@()}
    Test-Write (Join-Path $runtime 'result.json') $trace; Test-Write (Join-Path $runtime 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $runtime 'result.json'))}
    $cases=@(); foreach ($c in $spec.definition.workloads[0].cases) { $cases+=@{caseId=$c.caseId;inputIdentity=$c.inputIdentity;response=$(if ($Arm -ceq 'DETERMINISTIC') {'NOT_PROVIDED'} else {'ABSTAINED'});outputIdentity=$(if ($Arm -ceq 'DETERMINISTIC') {$null} else {@{sha256=('1'*64)}});review=$null} }
    $trial=@{slot=$spec.slot;runId=$Id;definitionIdentity=$ieDefinitionIdentity;implementationIdentity=$spec.definition.implementations[$Arm];model=$(if ($Arm -ceq 'DETERMINISTIC') {$null} else {$spec.definition.model});status=$status;traceIdentity=@{sha256=(Test-Hash (Join-Path $runtime 'result.json'))};usage=$usage;elapsedMillis=$trace.elapsedMillis;cases=$cases}
    Test-Write (Join-Path $adapter 'result.json') @{formatVersion=1;runId=$Id;trial=$trial;qualityReview='NOT_PERFORMED'}; Test-Write (Join-Path $adapter 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $adapter 'result.json'))}
}
function IE-Adapter([string]$Arm,[string[]]$Arguments) {
    # Real compiled policy and registration commands; only participant execution
    # and historical reads are simulated. No run/check command reaches Java.
    if ($Arguments[0] -cin @('policy','register')) {
        $result=& $script:taskRealAdapter $Arm $Arguments
        if ($Arguments[0] -ceq 'register') { $script:taskRegistrations++; if ($script:taskFault -ceq 'AFTER_REGISTER') { $script:taskFault='NONE'; throw 'Synthetic lost registration response.' } }
        return $result
    }
    $runtime=Join-Path $taskState ('invoice-comparison-trials/'+$Arguments[4]); $adapter=Join-Path $taskState ($ieAdapters[$Arm].store+'/'+$Arguments[4])
    if ($Arguments[0] -ceq 'run') {
        $script:taskRuns++; Test-Complete $Arm $Arguments[4]
        if ($script:taskFault -ceq 'AFTER_RUN') { $script:taskFault='NONE'; throw 'Synthetic lost completion response.' }; return 'Synthetic terminal result.'
    }
    if ($Arguments[0] -cne 'read') { throw 'Unexpected test command.' }
    foreach ($dir in @($runtime,$adapter)) { IE-Require ((IE-Json (Join-Path $dir 'receipt.json')).resultSha256 -ceq (Test-Hash (Join-Path $dir 'result.json'))) 'Changed synthetic terminal receipt.' }
    $trace=IE-Json (Join-Path $runtime 'result.json'); foreach ($entry in $trace.artifactSha256.GetEnumerator()) { IE-Require ($entry.Value -ceq (Test-Hash (Join-Path $runtime $entry.Key))) 'Changed synthetic ledger artifact.' }
    return 'Synthetic retained validation.'
}
function Test-Run { return (Invoke-InvoiceCampaignExecution | Out-String) | ConvertFrom-Json -AsHashtable -Depth 80 }
function Test-Pass([string]$Name,[bool]$Condition) { if (-not $Condition) { throw ('Failed control: '+$Name) }; $script:taskPassed++; Write-Output ('PASS: '+$Name) }
function Test-Reject([string]$Name,[scriptblock]$Operation,[string]$Message='.') {
    $caught=$false; try { $null=& $Operation } catch { $caught=$_.Exception.Message -match $Message }
    Test-Pass $Name $caught
}
function Test-Inventory { $r=[ordered]@{}; foreach ($file in (Get-ChildItem -LiteralPath $taskState -Recurse -File -Force | Sort-Object FullName)) { if ($file.Name -notlike '*.lock') { $r[[IO.Path]::GetRelativePath($taskState,$file.FullName)]=Test-Hash $file.FullName } }; return ConvertTo-Json -InputObject $r -Depth 10 -Compress }
$oldKey=[Environment]::GetEnvironmentVariable('OPENAI_API_KEY','Process'); [Environment]::SetEnvironmentVariable('OPENAI_API_KEY',$null,'Process')
try {
    $first=Test-Run; Test-Pass 'bounded first slot, pending denominator and zero model permission for diagnostics' ($first.recordedTrials -eq 1 -and $first.unrecordedTrials -eq 11 -and $first.status -ceq 'PENDING' -and $first.usage.reservedCalls -eq 0)
    $firstCampaign=$CampaignId; $firstOutcome=IE-Hash (Join-Path $ieExecution 'slots/01-outcome.json')
    $MaximumTrials=12; $full=Test-Run
    $records=IE-Json (Join-Path $ieExecution 'records.json')
    Test-Pass 'all twelve ordered registrations and twelve outcomes retained' ($full.recordedTrials -eq 12 -and $records.trials.Count -eq 12 -and @($records.trials.cases).Count -eq 12 -and @($records.trials.runId | Sort-Object -Unique).Count -eq 12 -and (IE-Hash (Join-Path $ieExecution 'slots/01-outcome.json')) -ceq $firstOutcome)
    Test-Pass 'abstentions, absent answers and unknown usage are not dropped' (@($records.trials | Where-Object {$_.status -ceq 'UNKNOWN_REMOTE_OUTCOME'}).Count -eq 3 -and $full.usage.unresolvedUsageSlots -eq 3 -and @($records.trials.cases | Where-Object {$_.response -ceq 'NOT_PROVIDED'}).Count -eq 3 -and $full.qualityReview -ceq 'NOT_PERFORMED')
    $before=Test-Inventory; $runCount=$taskRuns; $registerCount=$taskRegistrations; $Action='read'; $read=Test-Run; $Action='run'; $replay=Test-Run
    Test-Pass 'credential-free inspection and completed replay preserve every non-lock byte' ($read.status -ceq 'FINISHED' -and $replay.status -ceq 'FINISHED' -and (Test-Inventory) -ceq $before -and $taskRuns -eq $runCount -and $taskRegistrations -eq $registerCount)
    $expectedReceipt=$ExpectedReceiptSha256; $ExpectedReceiptSha256='0'*64; $Action='read'
    try { Test-Reject 'independent campaign receipt mismatch stops inspection' {Test-Run} 'independently expected' } finally { $ExpectedReceiptSha256=$expectedReceipt }
    $registrationPath=Join-Path $ieExecution 'registration.json'; $savedRegistration=[IO.File]::ReadAllBytes($registrationPath)
    [IO.File]::WriteAllText($registrationPath,'{"formatVersion":1,"formatVersion":1}')
    Test-Reject 'duplicate controller JSON fields are rejected' {Test-Run} 'Duplicate'
    [IO.File]::WriteAllBytes($registrationPath,[byte[]]@(255)); Test-Reject 'invalid controller UTF-8 is rejected' {Test-Run}
    [IO.File]::WriteAllBytes($registrationPath,$savedRegistration)
    $expectedWrapper=$ieRegistration.wrapperSha256.SINGLE_AGENT; $ieRegistration.wrapperSha256.SINGLE_AGENT='0'*64
    try { Test-Reject 'changed participant wrapper blocks new admission' {IE-Policies} 'wrapper changed' } finally { $ieRegistration.wrapperSha256.SINGLE_AGENT=$expectedWrapper }
    $expectedWrapper=$ieRegistration.registrationWrapperSha256; $ieRegistration.registrationWrapperSha256='0'*64
    try { Test-Reject 'changed registration wrapper blocks new admission' {IE-Policies} 'registration wrapper changed' } finally { $ieRegistration.registrationWrapperSha256=$expectedWrapper }
    $mapped=IE-Json (Join-Path $ieExecution 'slots/02-mapping.json'); $adapter=Join-Path $taskState ('invoice-single-agent/'+$mapped.runId)
    $savedResult=[IO.File]::ReadAllBytes((Join-Path $adapter 'result.json')); $savedReceipt=[IO.File]::ReadAllBytes((Join-Path $adapter 'receipt.json'))
    $wrong=IE-Json (Join-Path $adapter 'result.json'); $wrong.trial.definitionIdentity.sha256='0'*64; Test-Write (Join-Path $adapter 'result.json') $wrong; Test-Write (Join-Path $adapter 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $adapter 'result.json'))}
    Test-Reject 'resealed foreign campaign definition cannot become an outcome' {Test-Run} 'campaign definition'
    [IO.File]::WriteAllBytes((Join-Path $adapter 'result.json'),$savedResult); [IO.File]::WriteAllBytes((Join-Path $adapter 'receipt.json'),$savedReceipt)
    $slotOutcome=Join-Path $ieExecution 'slots/01-outcome.json'; $saved=[IO.File]::ReadAllBytes($slotOutcome); [IO.File]::AppendAllText($slotOutcome,' ')
    $Action='read'; Test-Reject 'changed sealed slot bytes' {Test-Run} 'slot receipt'; [IO.File]::WriteAllBytes($slotOutcome,$saved)
    $receipt=Join-Path $ieExecution 'receipt.json'; $saved=[IO.File]::ReadAllBytes($receipt); Remove-Item -LiteralPath $receipt
    Test-Reject 'missing final receipt is not complete inspection' {Test-Run} 'seal is incomplete'; $Action='run'; $null=Test-Run
    Test-Pass 'missing final receipt recovers without replacing trials' (([Convert]::ToBase64String([IO.File]::ReadAllBytes($receipt))) -ceq ([Convert]::ToBase64String($saved)) -and $taskRuns -eq $runCount -and $taskRegistrations -eq $registerCount)
    $slotReceipt=Join-Path $ieExecution 'slots/01-receipt.json'; $saved=[IO.File]::ReadAllBytes($slotReceipt); Remove-Item -LiteralPath $slotReceipt
    $Action='read'; Test-Reject 'missing slot receipt cannot appear sealed' {Test-Run}; $Action='run'; $null=Test-Run
    Test-Pass 'missing slot receipt recovers without participant replay' (([Convert]::ToBase64String([IO.File]::ReadAllBytes($slotReceipt))) -ceq ([Convert]::ToBase64String($saved)) -and $taskRuns -eq $runCount)
    $mapped=IE-Json (Join-Path $ieExecution 'slots/02-mapping.json'); $runtime=Join-Path $taskState ('invoice-comparison-trials/'+$mapped.runId); $adapter=Join-Path $taskState ('invoice-single-agent/'+$mapped.runId); $savedFiles=@{}
    foreach ($dir in @($runtime,$adapter)) { foreach ($name in @('result.json','receipt.json')) { $path=Join-Path $dir $name; $savedFiles[$path]=[IO.File]::ReadAllBytes($path) } }
    $trace=IE-Json (Join-Path $runtime 'result.json'); $trace.usage.inputTokens++; Test-Write (Join-Path $runtime 'result.json') $trace; Test-Write (Join-Path $runtime 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $runtime 'result.json'))}
    $result=IE-Json (Join-Path $adapter 'result.json'); $result.trial.usage.inputTokens++; $result.trial.traceIdentity.sha256=Test-Hash (Join-Path $runtime 'result.json'); Test-Write (Join-Path $adapter 'result.json') $result; Test-Write (Join-Path $adapter 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $adapter 'result.json'))}
    $Action='read'; Test-Reject 'resealed aggregate cannot omit or invent participant usage' {Test-Run} 'Participant accounting'; foreach ($entry in $savedFiles.GetEnumerator()) { [IO.File]::WriteAllBytes($entry.Key,$entry.Value) }
    $trace=IE-Json (Join-Path $runtime 'result.json'); $trace.usage.toolCalls=101; Test-Write (Join-Path $runtime 'result.json') $trace; Test-Write (Join-Path $runtime 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $runtime 'result.json'))}
    $result=IE-Json (Join-Path $adapter 'result.json'); $result.trial.usage.toolCalls=101; $result.trial.traceIdentity.sha256=Test-Hash (Join-Path $runtime 'result.json'); Test-Write (Join-Path $adapter 'result.json') $result; Test-Write (Join-Path $adapter 'receipt.json') @{resultSha256=(Test-Hash (Join-Path $adapter 'result.json'))}
    Test-Reject 'resealed over-budget read totals cannot become measured evidence' {Test-Run} 'limits'; foreach ($entry in $savedFiles.GetEnumerator()) { [IO.File]::WriteAllBytes($entry.Key,$entry.Value) }
    Test-NewCampaign; $taskFault='AFTER_REGISTER'; Test-Reject 'lost registration response interrupts before dispatch' {Test-Run}; $orphan=@(IE-Candidates $ieOrder.slots[0]); $registrations=$taskRegistrations; $null=Test-Run
    Test-Pass 'one complete orphan registration reused without retry selection' ($taskRegistrations -eq $registrations -and (IE-Json (Join-Path $ieExecution 'slots/01-mapping.json')).runId -ceq $orphan[0].id)
    Test-NewCampaign; $taskFault='AFTER_RUN'; Test-Reject 'lost completion response retains mapped result' {Test-Run}; $runs=$taskRuns; $null=Test-Run
    Test-Pass 'completed result recovered without participant replay' ($taskRuns -eq $runs)
    Test-NewCampaign; $taskFault='AFTER_START'; Test-Reject 'interruption after clock start retains mapping' {Test-Run}; $mapping=IE-Json (Join-Path $ieExecution 'slots/01-mapping.json'); $start=Join-Path $taskState ('invoice-comparison-trials/'+$mapping.runId+'/started.json'); $startHash=Test-Hash $start
    $Action='read'; $partial=Test-Run; Test-Pass 'mapped unfinished resources remain explicitly outside sealed totals' ($partial.incompleteSlots.Count -eq 1 -and $partial.usageScope -ceq 'sealed-terminal-trials-only' -and $partial.unrecordedTrials -eq 12)
    $Action='run'; $null=Test-Run; Test-Pass 'resumption preserves the original start and deadline' ((Test-Hash $start) -ceq $startHash -and (IE-Json (Join-Path $ieExecution 'slots/01-mapping.json')).runId -ceq $mapping.runId)
    $registrationPath=Join-Path $ieExecution 'registration.json'; $saved=[IO.File]::ReadAllBytes($registrationPath); $registrationReceipt=Join-Path $ieExecution 'registration-receipt.json'; $savedReceipt=[IO.File]::ReadAllBytes($registrationReceipt); $value=IE-Json $registrationPath; $value.driverSha256='0'*64; Test-Write $registrationPath $value; Test-Write $registrationReceipt @{registrationSha256=(Test-Hash $registrationPath)}
    $registrations=$taskRegistrations; Test-Reject 'resealed registration changes invalidate earlier slot intents' {Test-Run} 'Changed slot intent'; Test-Pass 'changed registration admits no replacement trial' ($taskRegistrations -eq $registrations); [IO.File]::WriteAllBytes($registrationPath,$saved); [IO.File]::WriteAllBytes($registrationReceipt,$savedReceipt)
    $originalDriver=$ieDriver; $changedDriver=Join-Path $taskTestRoot 'changed-driver.ps1'; [IO.File]::WriteAllText($changedDriver,([IO.File]::ReadAllText($originalDriver)+' ')); $ieDriver=$changedDriver
    try { Test-Reject 'changed driver bytes refuse unfinished dispatch' {Test-Run} 'driver changed'; Test-Pass 'changed driver creates no extra registration' ($taskRegistrations -eq $registrations) } finally { $ieDriver=$originalDriver }
    $extra=Join-Path $ieExecution 'slots/02-mapping.json.pending'; [IO.File]::WriteAllText($extra,'incomplete publication'); Test-Reject 'pending publication blocks admission' {Test-Run} 'incomplete slot'; Remove-Item -LiteralPath $extra
    $extra=Join-Path $ieExecution '.hidden'; [IO.File]::WriteAllText($extra,'unexpected'); Test-Reject 'hidden execution artifact rejected' {Test-Run} 'Unexpected or incomplete'; Remove-Item -LiteralPath $extra -Force
    $extra=Join-Path $ieExecution 'slots/03-intent.json'; [IO.File]::Copy((Join-Path $ieExecution 'slots/01-intent.json'),$extra); $Action='read'; Test-Reject 'future slot cannot bypass an unfinished predecessor' {Test-Run} 'later slot'; Remove-Item -LiteralPath $extra
    $lease=[IO.File]::Open((Join-Path $ieExecution 'owner.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try { Test-Reject 'concurrent controller owner refused' {Test-Run} } finally { $lease.Dispose() }
    Test-NewCampaign; $taskFault='AFTER_REGISTER'; Test-Reject 'ambiguous-recovery fixture starts with a lost response' {Test-Run}
    $slot=$ieOrder.slots[0]; $null=& $taskRealAdapter $slot.arm @('register',$taskState,$ieInputDirectory,$taskBundle,(Join-Path $ieCampaign 'plan.json'),$slot.workloadId,[string]$slot.repetition)
    $runs=$taskRuns; Test-Reject 'two orphan registrations are refused without choosing a retry' {Test-Run} 'Ambiguous slot'; Test-Pass 'ambiguous registration never dispatches a participant' ($taskRuns -eq $runs)
    Test-NewCampaign; $taskFault='AFTER_REGISTER'; Test-Reject 'incomplete-adapter fixture retains a registration intent' {Test-Run}
    $candidate=@(IE-Candidates $ieOrder.slots[0]); $path=Join-Path $taskState ('invoice-diagnostics/'+$candidate[0].id+'/registration.json'); $saved=[IO.File]::ReadAllBytes($path); Remove-Item -LiteralPath $path
    $registrations=$taskRegistrations; Test-Reject 'incomplete orphan adapter is refused instead of replaced' {Test-Run}; Test-Pass 'incomplete orphan grants no replacement trial' ($taskRegistrations -eq $registrations); [IO.File]::WriteAllBytes($path,$saved)
    Test-NewCampaign; $linkPath=Join-Path $taskState ('invoice-campaign-executions/'+$CampaignId); $linkTarget=Join-Path $taskTestRoot 'link-target'; [void][IO.Directory]::CreateDirectory($linkTarget); $linked=$false
    try { $null=New-Item -ItemType SymbolicLink -Path $linkPath -Target $linkTarget -ErrorAction Stop; $linked=$true } catch { if ($IsWindows) { try { $null=New-Item -ItemType Junction -Path $linkPath -Target $linkTarget -ErrorAction Stop; $linked=$true } catch {} } }
    if ($linked) { try { Test-Reject 'linked execution store rejected before publication' {Test-Run} 'Linked campaign'; Test-Pass 'linked target remains untouched' (@(Get-ChildItem -LiteralPath $linkTarget -Force).Count -eq 0) } finally { IE-Require ([IO.Path]::GetFullPath($linkPath).StartsWith($taskTestRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Unsafe test link cleanup.'; Remove-Item -LiteralPath $linkPath -Force } }
    else { Write-Output 'SKIP: linked execution control; host denied link creation.' }
    Write-Output ('Campaign execution checks passed: '+$taskPassed+'. Real Java policy/registration; simulated completion. No API/provider or consumer execution.')
} finally { [Environment]::SetEnvironmentVariable('OPENAI_API_KEY',$oldKey,'Process') }
