# Transient invoice v2 review inventories

`InvoiceReviewInputsV2` inspects exactly four selected, already sealed v2 trials:
deterministic diagnostics, one agent, independent workers and the coordinated
team, in that order. It authenticates retained adapter histories and produces a
transient inventory of review units. It does not publish a review packet, run a
participant, load a credential or judge the answers.

The existing v1 inspector, packets and campaign records remain unchanged.
[Campaign registration](invoice-campaign-v2.md) and the
[ordered controller](invoice-campaign-v2-execution.md) are separate boundaries.
This inspector does not establish campaign membership or that criteria were
frozen before a trial. The separate [v2 campaign publisher](invoice-campaign-review-inputs-v2.md)
now establishes those bindings without semantic grading.

## Selection and invocation

Build using the README commands. A selection JSON object has `formatVersion: 2`,
`inputDirectory`, `inputIdentity`, `contractDirectory`, `contractIdentity`,
`rubricArtifact`, `rubricIdentity`, `publicTaskSha256` and `sources`.
Each identity object contains `sha256`. The public-task field is a lowercase
SHA-256 string matching the implemented v2 task. Directory and artifact
references are relative to the common review evidence root, without traversal,
absolute paths or links.

`sources` contains exactly four objects, ordered by `DETERMINISTIC`,
`SINGLE_AGENT`, `INDEPENDENT_WORKERS` and `COORDINATED_TEAM`. Each has `arm`,
`stateDirectory`, `runId` and `resultIdentity`; the last field pins the selected
adapter's `result.json` bytes. Missing arms, reused trials and extra selection
fields are rejected. Input, protected contract and rubric identities must be
retained independently of the selected result directories.

For a locally prepared selection:

```powershell
$classpath = 'backend/target/api-compatibility-backend-0.1.0-SNAPSHOT.jar' +
  [IO.Path]::PathSeparator + 'backend/target/dependency/*'
java -cp $classpath io.github.nktogo.apicompatibility.runner.InvoiceReviewInputsV2 `
  inspect TRUSTED-REVIEW-EVIDENCE TRUSTED-SELECTION.json
```

The command emits inventory metadata, artifact references and hashes, not model
response text or customer payloads. Its only action is `inspect`. A missing or
incomplete source fails without creating a state directory. Use completed stores
without concurrent writers. The inspector build is distinct from the runtime
selected by the controller; rebuilding the inspector does not replace an archived
campaign runtime or authorize new work under that frozen condition.

## Retained scope

| Arm | Adapter store | Runtime store |
| --- | --- | --- |
| Deterministic | `invoice-diagnostics-v2` | `invoice-navigation-trials` |
| Single agent | `invoice-single-agent-v2` | `invoice-v2-trials` |
| Independent workers | `invoice-independent-workers-v2` | `invoice-v2-trials` |
| Coordinated team | `invoice-team-comparisons-v2` | `invoice-team-reconciliation-v2-trials` |

The team's `invoice-team-reconciliation-v2-prefix-runs` and
`invoice-team-reconciliation-v2-fresh-runs` histories are included as well. No v1
store substitutes for a missing v2 store. All required directories are checked
before retained adapter readers are constructed.

Each row retains its complete operational trial, including failed, cancelled,
uncertain or absent outputs. Review units cover available dossiers, summaries,
findings and sections. Independent partial aggregates and individual worker
outcomes remain visible. The nested team final `decision` receives its own
summary and finding pointers. Original and fresh claims, selected questions,
possible conflicts, coordinator/final outcomes and both intermediate stage
results remain reviewable even when no final dossier exists.

Every available broker `outputText` is referenced, including rejected decisions.
A non-completed broker result also receives a whole-record `BROKER_OUTCOME`
unit, including a response without token usage. Missing response text is not
invented. Each unit binds its artifact, JSON Pointer and canonical pointer-value
digest. Unit IDs are local to a row; identify a unit with its selected trial and
unit ID. `directFinalCitation` records only exact reference labels in the final
decision. It does not infer citation relations through read pages or establish
support, completeness or correctness.

The inventory binds all selected input, contract, rubric, adapter and runtime
files, excluding regular lock files. Hashes are checked before and after retained
inspection. Runtime and adapter JSON reject duplicate keys, malformed UTF-8 and
excessive nesting. Limits are 1 MiB per source, 64 MiB total source bytes, 5,000
files/directories and 2,000 units per trial. Oversized inputs fail rather than
being truncated. These are inspection limits, not changes to participant budgets.

The output keeps `criteriaTiming: NOT_INFERRED_FROM_SELECTION`,
`qualityReview: NOT_PERFORMED` and `campaignAcceptance: NOT_ESTABLISHED`.
Source hashes and successful retained reconstruction do not provide semantic
acceptance, signed provenance or protection against an operator replacing both
evidence and independently retained identities.

## Validation

Java tests exercise actual v2 adapters and retained readers with scripted local
broker outcomes and synthetic execution captures. They check pointer/value
digests, full lineage, rejected and uncertain answers, missing namespaces,
tampering, strict parsing and repeat inspection without source changes or new
calls. These fixtures establish inventory behavior, not measured model quality.
The existing v1 tests remain part of verification. Campaign-bound publication is
implemented separately; v2 semantic review integration remains pending.
