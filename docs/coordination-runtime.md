# Shared inputs and comparison trial runtime

The trusted host can capture one input-only bundle for the two curated cases and
register individually bounded comparison trials against it. This is the common
boundary for the planned deterministic, single-agent, independent-worker and
coordinated-team adapters. Those adapters and the fresh measurement campaign
remain unimplemented; this runtime alone establishes no coordination benefit.

## Capture the same available evidence

After the README build, select an existing customer investigation with a completed
recorded request and an existing subscription investigation with completed retained
evidence. Capture reads `describe_case`, paged `read_input` and `read_run` only.
It never calls `run_case`, starts a container or makes a model request.

```powershell
./scripts/Capture-CoordinationInputs.ps1 `
  -CustomerStateDirectory CUSTOMER-STATE `
  -CustomerContextId investigation-EXISTING-UUID `
  -CustomerRequestId EXISTING-REQUEST `
  -SubscriptionStateDirectory SUBSCRIPTION-STATE `
  -SubscriptionContextId subscription-investigation-EXISTING-UUID
./scripts/Capture-CoordinationInputs.ps1 -Action read -InputBundleId inputs-EXISTING-UUID
```

The two source directories may differ. A task's input state is its `state/`
directory, not the outer task directory. Capture verifies the allowlisted aliases,
page hashes, complete source reconstruction and stable description/execution reads.
Customer inputs include the historical reference source and its recorded outcomes;
they do not imply that reference is correct. Subscription inputs expose baseline
source, application policy, fixtures, expectations and baseline/target observations.
Prior candidate source/results, agent answers, grading reviews, credentials and
source-store permissions are not imported.

Bundles live in ignored `.workbench/coordination-inputs/inputs-UUID/`. Each case
retains raw input artifacts plus `description` and `execution` summaries, with a
manifest, content-derived case identities and final receipt. Every read checks the
captured inventory and hashes; edits to the original source cannot change the copy.
Each artifact is bounded to 1 MiB, with capture accepting at most 262,144 characters
per text input. Missing receipts and linked files/directories are rejected.

This is a shared **retained-evidence** boundary. Subsequent comparison adapters
must use its same available evidence; they cannot secretly run extra tests or
expose prior accepted answers to one arm. This does not remove the separate
product's existing container tools. It limits the initial comparison's evidence
retrieval and records zero new case executions. Broader execution-bearing campaigns
need a separately declared, equally available execution policy.

## Register a trial before participant work

`CoordinationRuntime.register(bundleId, definition, slot, policy)` makes no model
call. The definition must declare `SUPPLIED_RECORDS`, the actual captured identities
for both cases, and a valid workload/repetition/arm slot. Empty synthetic examples
from the offline evaluator cannot authorize runtime work.

A policy freezes the declared adapter, prompt, tool and context-management digest
references, one or two concurrent calls, a read-operation ceiling, final-output
selection identifier and fixed participants with case/protocol scopes. Participant
sets use sorted encoding. `implementationIdentity(policy)` covers every policy
field through versioned length-prefixed encoding; the selected arm's definition
identity must equal it. Changing a policy requires a different comparison binding.
References are declarations, not authentication of the referenced code or prompts;
the future adapters must bind their actual artifacts and payload construction.

Trial state lives in `.workbench/coordination-trials/trial-UUID/`. The specification
records registration time, input receipt and exact definition/policy. A separate
hash-bound host grant admits that specification. `start` publishes the whole-bundle
start and deadline once. It runs before the first model or input-read operation;
later stages and restarts cannot renew its clock. Common preparation/capture occurs
before this measured interval. Campaign-wide ordering remains later work.

## One budget across every stage

Every adapter model operation must use `call` with a registered participant,
stable operation ID and exact request. Model, protocol, UTF-8 input bytes and
requested output tokens are checked before broker registration. The common call
ceiling is a trial-wide reservation count, including coordinator and follow-up
participants; it is not the existing investigator's 12-decision loop. This runtime
can admit all 60 reservations when that is the declared ceiling. Future algorithms
still need loops/context management capable of using that budget.

The host reserves durably before broker registration and binds the broker mapping
to the request/reservation hashes. An exact retry reuses the same mapping and
completed broker result. Changed requests are refused. An unmapped reservation
remains uncertain and occupies its slot; registration is never replayed or replaced
automatically. Partial ledger publication stops admission. Broker artifacts from
possible orphan registration remain available in the trace inventory.

Call lanes limit simultaneous operations across host threads/processes. Saturation
returns `ACTIVE` without reserving another slot. The existing credential broker
keeps the credential outside participant records. Its optional parent-admission
callback checks trial revocation, deadline and captured identities before credential
resolution and during transport. The call grant also expires no later than the
whole-trial deadline. Existing broker users retain their original behavior.
Stops are cooperative; this does not promise instantaneous process termination or
prove that a request already accepted remotely was cancelled.

`read` restricts case and input alias to the registered participant and bundle.
Stable operation IDs deduplicate retained reads; changed arguments cannot reuse an
ID. Tool counts represent unique registered read operations, not physical filesystem
reads or replay invocations. Content is untrusted data. This boundary has no arbitrary
path, shell, external provider action or grant-management model tool.

## Preserve operational uncertainty

`snapshot` reports reserved/mapped/completed/known-not-dispatched/unresolved call
inventory, known token totals, registered reads, zero new case executions and
elapsed wall time. It retains exact artifact hashes, including possible orphan
broker files. Unknown remote outcomes do not become free runs or abstentions.
Reported output-limit violations stop the stage and remain explicit resource issues
without deleting completed usage. A snapshot during an atomic publication may need
to be retried; it is not a completed trial receipt.

`finish` requires model lanes to be idle and reconciles mapped unfinished broker
journals without dispatching or renewing a grant. A journal not marked started can
close as known not dispatched; a started request with no result remains unknown.
Unmapped registration is not guessed into a mapping. It retains the chosen operational disposition
and publishes a result with a completion receipt. Unknown usage remains unresolved.
Repeat finalization preserves the original result. `retained` verifies the receipt,
input binding and retained trace artifacts; changed evidence is rejected.
No new participant work is admitted after completion. Semantic review and conversion
to reviewed comparison outcomes remain separate adapter/campaign work.

All participants run within one trusted host account. These checks do not isolate
malicious host processes or authenticate against an operator rewriting the entire
store. Direct legacy entry points are not silently governed by this new runtime;
the comparison adapters must exclusively use this boundary.

## Validation

Focused tests cover paged Unicode capture, rejected inventories/changed inputs,
complete use of the 60-call ceiling, shared-stage reservations, exact retries,
orphan registration, uncertain usage, model/read scope, per-request limits,
permanent revocation, whole-trial expiry, concurrency, retained trace integrity and
no-dispatch finalization recovery, plus reading a completed trace in a new JVM.
Loopback broker tests verify parent denial
before credential access and cancellation after the test server receives a request.
They use synthetic responses and no external API credentials.

A recorded offline capture on 2026-10-04 produced bundle
`inputs-6e4dfed9-be1a-479d-aa15-b40073041353`, with receipt SHA-256
`ca2c977ab5bbe56b2620e53e66f9a58dd58dce0ad65716ef14ae1109bbb42a3a`.
A new-JVM read preserved its summary and all 16 retained file hashes. Source
evidence files were unchanged, and no model request was added. These are copies
of existing synthetic case observations, not a fresh trial or comparison result.
