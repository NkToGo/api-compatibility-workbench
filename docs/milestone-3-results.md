# Milestone 3 acceptance results

The declared two-case exercise meets its criteria with one explicit review block.
Customer sources has two accepted complete repairs out of three trials.
Subscription periods also has two accepted complete repairs out of three: all
three repairs pass their protected scenarios, but one investigation is excluded
because its executor-scope explanation is unsupported. All 12 missing-evidence
trials have supported abstention reasons under the recorded review method.

Milestone 3 is complete for this bounded exercise. These results permit moving
to coordination experiments; they do not certify a provider transition, establish
production reliability, approve a release or demonstrate multi-agent benefit.

## Declared conditions and full denominator

The [checklist](milestone-3-acceptance.md) requires three repetitions for each
condition on each case, at least two accepted complete repairs per sufficient-
evidence case, supported abstentions in every withheld-evidence trial and review
of every sufficient-evidence investigation. Rejected or unresolved explanations
cannot earn repair credit. Their trials remain in the denominator.

| Case / condition | Planned trials | Raw behavioral or decision result | Reviewed acceptance credit |
| --- | --- | --- | --- |
| Customer / sufficient evidence | 3 | Two complete repairs; one repair passes only 1/5 scenarios | 2/3 complete repairs |
| Subscription / sufficient evidence | 3 | Three repairs pass all 8/8 scenarios | 2/3 complete repairs; one investigation blocked |
| Customer / source unavailable | 3 | Three explicit abstentions after the encountered failure | 3/3 supported abstentions |
| Customer / execution unavailable | 3 | Three explicit abstentions after the encountered failure | 3/3 supported abstentions |
| Subscription / source unavailable | 3 | Three explicit abstentions after the encountered failure | 3/3 supported abstentions |
| Subscription / execution unavailable | 3 | Three explicit abstentions after the encountered failure | 3/3 supported abstentions |

The failed customer candidate remains `REGRESSIONS_REMAIN`: all four original
regressions remained, with only the original passing control preserved. Its
investigator's explanation is supported; its repair is not credited. No trial
in these selected suites had an unrelated technical failure or uncertain model
outcome. The runtimes retain such outcomes separately when they occur.

## Claim-level review and exclusion

The [sufficient-evidence record](evaluations/sufficient-evidence-review-2026-10-01.json)
contains all six summaries and 25 findings, with original text, event references
and individual judgments. Thirty claims are supported within the recorded scope;
one is rejected. Review is model-assisted, without independent human adjudication
or a calibrated estimate of reviewer accuracy. Hypotheses are assessed as bounded
inferences from source and synthetic observations, not proven production causes.

The rejected claim is subscription trial 2, finding 5 from
`agent-cdf576f5-3c0c-4598-a3de-a9f94400ee5d`. It says the runner executes authored
controls only and does not compile candidate source. Its cited historical README
describes the separate `Run-SubscriptionPeriodCase` control runner. The retained
verification actually binds supplied source and a candidate manifest under
`subscription-period-v1`; the protected worker compiles that source.

The statement transfers a historical runner limitation to a different evidence
pipeline. A valid citation does not support that transfer. Its conclusion that
this investigator did not verify a repair remains true: the investigation serves
only retained baseline/target observations and hides previous candidate results.
That true conclusion does not validate the false executor premise.

The entire trial is `BLOCKED_FROM_ACCEPTANCE`, receives zero accepted-repair
credit and remains one of the three planned subscription trials. Its separately
passing candidate and verification records remain unchanged. Trials 1 and 3
supply the two accepted subscription repairs. The blocked trial is excluded from
the accepted evidence set for subsequent coordination work.

This exclusion applies to the milestone evidence set. It is not a new global
runtime permission or repair-publication approval state; those remain separate
planned product capabilities. The offline acceptance checker enforces the credit
exclusion when applying the recorded review judgments.

The earlier [missing-evidence review](missing-evidence-review.md) covers all
12 summaries and 13 findings from the withheld-evidence conditions. It remains
unchanged. Together the two reviews cover 56 visible claims in the 18 selected
trials. The original evaluations' `semanticReview: PENDING` fields are historical
data; separate bound review records supply later judgments without rewriting them.

## Measurement identities and budgets

All three selected suites used `gpt-5.6-terra`. They were separately registered
and ran under different recorded builds and execution modes. These are known-case
acceptance measurements, not a matched causal experiment or a held-out benchmark.

| Suite | ID | Permitted / registered requests | Recorded input/output tokens |
| --- | --- | --- | --- |
| Customer full workflow | `evaluation-34cd92c5-a5c9-4ac1-a853-720e3b20801d` | 39 / 29 | 155,071 / 6,169 |
| Subscription full workflow | `evaluation-6bf39751-1f36-48cf-bf62-a0c5c9562725` | 39 / 28 | 149,697 / 3,561 |
| Two-case missing evidence | `evaluation-7271379f-19c0-4ce7-a479-c8f08ed2210f` | 144 / 44 | 75,815 / 3,466 |
| Selected 18 trials | Separately registered suites combined | 222 / 101 | 380,583 / 13,196 |

Each investigator permits at most 12 decisions, 2,048 output tokens per response,
64,000 UTF-8 input bytes per request and a 20-minute admission deadline. Each
generation permits one request and 4,096 output tokens. Broker and constrained
worker policies remain unchanged. There is no repair-feedback loop or extra
attempt for a failed trial. Registered request counts are distinct from known
dispatches, and recorded tokens are not an invoice; unknown charges remain unknown.

Customer investigators request fixed execution of five scenarios and receive a
historical-reference result. Subscription investigators read completed original
baseline/target evidence; their execution aliases never launch a worker.
Each ready repair receives separate protected verification: 15 scenario executions
per customer candidate and 24 per subscription candidate. The six candidates
therefore produced 117 new protected scenario executions, in addition to customer
investigation execution and pre-existing subscription evidence.

The structured [acceptance record](evaluations/milestone-3-acceptance-2026-10-01.json)
retains exact suite/child IDs, model, harness and repair image identities, request
counts, token totals, scenario outcomes, review dispositions and hashes of 194
critical local artifacts. It also binds both review files, the checker scripts
and relevant protocol/runtime source files. Original suite records retain complete
input and artifact inventories. See the individual
[customer](customer-repair-evaluation.md),
[subscription](subscription-investigated-repair-evaluation.md) and
[missing-evidence](missing-evidence-evaluation.md) measurements for source/image details.

## Earlier unsuccessful measurements

The customer generation evidence was improved through separately identified
corrections before selecting the final acceptance suite. Those earlier results
are retained, not silently dropped or replaced:

| Historical suite | Outcome across its three trials | Recorded requests | Input/output tokens |
| --- | --- | --- | --- |
| `evaluation-0374e04b-580e-437f-bb06-41398e77d93c` | Three repairs pass 4/5; no complete repair | 30 | 154,181 / 6,317 |
| `evaluation-e1907a9d-c38c-48d0-b009-0788595229bb` | Two repairs pass 4/5; one generation abstention | 31 | 172,536 / 6,064 |

These six additional trials belong to earlier configurations and receive no
credit in the selected 18-trial exercise. Their explanations were not part of
the sufficient-evidence acceptance review. Including these development trials,
the retained measurements contain 24 trials, 162 registered requests and
707,300 input / 25,577 output tokens. This selection history prevents interpreting
the final known-case counts as unbiased general model accuracy. Earlier
generation-only subscription measurements are separate controls and are not
substituted for fresh investigation-to-generation trials.

## Offline verification and recovery

With the original local state store and bound repository files available:

```powershell
./scripts/Verify-Milestone3Acceptance.ps1 -RequireCriteriaMet
./scripts/Test-Milestone3Acceptance.ps1
```

`-ReportPath` selects an acceptance record; `-StateDirectory` selects its store.
The checker verifies hashes, original explanation text, exact source-page slices,
event references, complete trial/claim coverage, child bindings, observed repair
outcomes, denominators and request/token accounting. It applies recorded review
judgments to credit and refuses credit for blocked investigations. Changed or
missing records, unknown references, linked artifacts and references outside the
store stop verification. A coherent contrary review can remain `CRITERIA_NOT_MET`;
`-RequireCriteriaMet` then refuses the acceptance check.

The checker does not decide semantic truth, authenticate a reviewer, authorize
execution or approve publication. Hashes assume a trusted single-host store and
do not prevent an operator from rewriting both evidence and review records.
Fifteen validation controls covered changed records, omitted/duplicated trials,
invented citations, unsupported credit, altered totals and a coherent contrary
review below the threshold. Controls used disposable copies of evidence.
The opt-in test command reproduces these controls from the retained store in an
ignored build-output directory. It makes no API calls and leaves original records
unchanged. These tests require the local evidence, so they are not part of CI's
credential-free Java suite.

Reopening all three completed suites preserved their result bytes and left the
model-call store count unchanged at 255 registrations. No new provider calls or
candidate execution were needed for this review. Existing automated evaluator
tests cover changed inputs, interrupted dispatch, cleanup and no-replay recovery.

## Remaining product work

Milestone 4 begins isolated consumer tasks, bounded parallel investigation,
coordinator follow-up and contradiction handling. It must compare coordination
with deterministic, single-agent and independent-worker configurations under
declared budgets. These results provide reviewed starting cases; they establish
no coordination benefit. The historical-document/current-tool contradiction is
a retained example of why provenance text cannot define executor capabilities.

General repository ingestion, additional provider families, live-provider
validation, multi-user authorization, review-bound publication, hosted deployment
and release operation remain later work. The product is still a local curated-case
workbench, with no hosted application or certified migration support matrix.
