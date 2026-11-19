# Invoice capture preflight

The preflight checks whether a [retained invoice source capture](consumer-source-capture.md)
and the selected migration declarations match the host's registered synthetic
invoice rollout. It uses the existing protected-case loader to verify the
local source and build registration, dependencies, fixtures and expectations.
It reports the source, configuration and fixture identities for later
protected execution.

After [preparing and building the backend](../README.md#build-and-test), run
in PowerShell 7:

```powershell
./scripts/Inspect-InvoiceCapture.ps1 -InputId INPUT_ID -CaptureId CAPTURE_ID
```

Supply the currently selected planning snapshot ID and the source capture ID
independently. Use `-EvidenceDirectory` if they are in a store other than
`.workbench/evidence/`. Both IDs must be complete SHA-256 strings. The capture
must belong to that exact planning snapshot, even if another snapshot has the
same repository revision. The command runs offline against the local host case
and retained evidence.

## Checks

- The capture and its parent are revalidated through the existing evidence store.
  Partial source, invalid declarations and role-to-POM mismatches produce blockers.
- A complete capture's ten file digests must match the host's validated build
  registration. The repository cannot supply host fixtures, expected outcomes or
  dependencies. The result carries the fixture, build and dependency identities.
- Each baseline and target phase must independently match one of the four
  registered `rollout-v1` scenarios: `legacy-agreement`, `target-agreement`,
  `request-first` or `event-first`. The match requires the SDK, request and
  account versions for both roles and the endpoint and event versions for the
  webhook. A declared native adapter version must match the fixture value if
  supplied. A successful match identifies both registered delivery inputs,
  expected evidence identities and base builds.
- A webhook version mismatch requiring a separate adapter yields
  `COMPANION_SOURCE_NOT_CAPTURED`. The host's local companion registration
  cannot replace evidence for the selected repository's companion source.
  A valid declaration outside the four vectors yields `NO_MATCHING_ROLLOUT`.
  An unmatched target does not erase a matched baseline.

`MATCHES_REGISTERED_ROLLOUT` means both phases map to the registered synthetic
inputs. `BLOCKED` gives fixed finding codes and the available declaration
issues. Restore the trusted host case and prepare its pinned dependencies if
`HOST_CASE_UNAVAILABLE_OR_INVALID` is reported. The report's
`preflightIdentity` hashes the assessment using the host's JSON serializer.
It includes the exact input and capture IDs, checks, and fixture/build bindings.
It is useful for comparing assessments and conveys no authority.

Java exits `0` for a matching rollout and `3` for a blocked assessment. An
unreadable or invalid evidence record uses exit `2` and a sanitized error.
The wrapper raises an error for nonzero status. The check publishes no evidence,
grants or execution records. The assessment always reports
`executionAuthorized: false`.

Effective runtime settings, live Stripe event provenance, worker-image
validation, authorization, and consumer behavior require their own evidence.
The preflight reads local case assets but runs neither Maven nor captured code.
The separate [protected rollout command](invoice-rollout-delivery.md) can
revalidate a matching capture and run one authorized reconciliation delivery.
