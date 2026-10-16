# Runtime invoice admission controls

`InvoiceBoundaryAdmissionCoverage` exercises all 24 registered rejection controls
and two healthy admissions using actual host authentication and isolated SDK
parsing. It does not dispatch business consumers, construct their oracles, replay
ledger positions or establish complete boundary execution coverage.

## Captured inputs and actual authority

The driver captures the complete [invoice registry](invoice-renewal-registry.md),
the unchanged `boundary-v1` corpus and the registered consumer dependencies. It
selects the 24 pre-dispatch controls plus `admit-webhook` and
`admit-reconciliation`. The remaining eight execution controls are explicitly
listed as pending in every plan and report.

Historical [authored conditions](invoice-renewal-boundary.md) remain unchanged.
Their signature/adapter flags and signed-source claims are discarded by the new
driver. The source, input and evidence identities, including intentionally missing
or stale tuple members, remain captured independently from verifier outcomes.

Each webhook uses a random, ephemeral synthetic signing key. The signature verifier
checks the actual raw bytes through its normal HMAC and timestamp path. A bad
signature flips one digest byte; an unchecked signature supplies no receipt; a
rebound signature authenticates different bytes. Invalid, absent or rebound
authentication prevents parser dispatch. No key or signature header is persisted.
These checks authenticate synthetic fixture construction, not Stripe delivery.

Eligible controls invoke the existing isolated, pinned SDK parser. Missing and
unsupported adapter selections retain real preparation refusals; an unchecked
adapter supplies no receipt. All typed receipts are passed to
`inspectVerifiedWebhook`, so payload claims cannot grant authority. Reconciliation
uses its explicit request version and the host-selected role.

Two differences from the authored expectations are deliberate and visible:

- `adapter-rejected` originally declared a rejected adapter for valid bytes. The
  runtime derives a synthetic event with an object where the SDK expects numeric
  `amount_due`, authenticates those exact bytes, and observes the actual SDK
  refusal. It rebinds the derived input/source/evidence tuple and records both
  original and runtime input identities. The original fixture is not modified.
- Missing/unknown event versions and a rejected event type also cause actual
  parser preparation refusal. A deliberately wrong adapter selection cannot
  produce the authored fixture's purported verified receipt: it produces a real
  `ADAPTER_REJECTED` result. Runtime expectations retain these additional/different
  issues instead of copying historical authored verdicts.

The fixed runtime expectation table is independent from the gate under test.
It is captured by the implementation identity. It requires exact admission and
issue-list matches; the two healthy controls must actually be admitted, and the
healthy webhook requires both typed verified receipts. The complete run invokes
11 actual parser containers, including the schema-refusal control. Other controls
stop at authentication, preparation or explicit omission.

## Outcomes and limits

`VERIFIED` means the runtime admission matched that control's expectation. A
correctly blocked control is verified as a rejection, never as compatible consumer
behavior. `FAILED` retains a different decision. `NOT_EVALUATED` retains host or
parser infrastructure failure instead of counting it as an expected rejection.
Cancellation preserves completed receipts and decisions; subsequent controls stay
`NOT_ATTEMPTED`. Infrastructure errors stop further checks with the full inventory
still present.

`admissionCoverageSatisfied` requires all 26 controls to be verified. Even then,
`boundaryControlsVerified`, `boundaryPlacementsVerified`, business-consumer
dispatches and ledger receipts remain zero, and `fullCaseCoverageSatisfied` is
always false. These two healthy controls establish admission, not business
execution. The eight execution controls and all 102 boundary placements still
need runtime coverage; joint comparison and campaign acceptance remain open.

## Running and retaining evidence

After the README preparation and build commands:

```powershell
./scripts/Build-InvoiceWorker.ps1
$invoiceImage = (Get-Content -LiteralPath backend/target/invoice-worker-image.txt -Raw).Trim()
./scripts/Run-InvoiceBoundaryAdmissionCoverage.ps1 -ImageId $invoiceImage
```

The wrapper creates a new directory under ignored
`backend/target/invoice-boundary-admission-runs/`. Its 28 create-only files contain
`plan.json`, `001.json` through `026.json`, and `report.json`. Keep each attempt
together on trusted local storage; this is not an authenticated interchange or a
JSON-to-permission import. An existing attempt cannot be overwritten. An abrupt
termination can leave partial records without a final report; use a new attempt
rather than treating those files as a resumable source of authority.

The plan/report bind registry, boundary corpus, build registration, immutable image
and implementation identities. Per-control files retain source/input/evidence
identities, actual verifier diagnostics, expected and observed admission, phase,
outcome and explicit null consumer observation. Raw payloads, signing keys and
private model reasoning are excluded. The derived schema-fault identity is
separate from the original fixture identity.

Parser executions keep the existing 30-second stage deadline and independently
bounded container control/cleanup. Local traversal cooperatively checks
interruption between controls and stages. Java exits 0 for complete admission
coverage, 2 for retained failed/incomplete coverage and 1 for entry-point/storage
failure. The wrapper throws on a nonzero Java exit. No provider/model calls,
credentials or live customer data are needed.

Default tests check exact census, source/identity binding, immutable snapshots,
pending execution coverage, cancellation and create-only history. Actual container
tests check all rejections and healthy admissions, real SDK refusal,
infrastructure failure and interruption between controls. Linux CI retains their
`invoice-boundary-admission-tests/` journals with the protected execution receipts.
