# Stripe customer sources: omission is not an empty list

Status: curated inputs with an [executed isolated-method case](../../docs/stripe-case-runner.md).
The baseline passes all five scenarios; the unrepaired target fails four and the
historical reference fails two. There is no validated repair or full-installation
compatibility claim. The input contract's `curated-not-executed` status records
its creation state; execution evidence is stored separately, never written back
over the expected results.

## The integration problem

Kill Bill's Stripe plugin refreshes locally stored payment methods from Stripe.
Its historical implementation reads modern payment methods, reads legacy customer
sources, then deactivates local records that were not found. A missing collection
can therefore affect both completion and preservation of saved payment methods.

Stripe's [2020-08-27 change](https://docs.stripe.com/changelog/2020-08-27/deprecates-auto-expansion-customer-sources)
stopped including customer sources by default. The
[upstream migration](https://github.com/killbill/killbill-stripe-plugin/commit/b5b7b52e6c703f2292730996ce838a7538497762)
updates the SDK and guards the missing collection. That patch is a historical
reference, not the authority for expected results. Skipping a missing collection
may still leave existing legacy methods in the subsequent deactivation set.
The isolated runner demonstrates this risk with synthetic responses. It is not
evidence of an observed production incident.

## Exact transition

| Variant | Consumer source revision | Stripe Java | SDK default API version |
| --- | --- | --- | --- |
| Baseline | `b5445c6be4f195c5b59d7c867ad7dcc696aa7a38` | `17.4.0` | `2019-12-03` |
| Unrepaired target | Same baseline method bodies | `20.33.0` | `2020-08-27` |
| Upstream reference | `b5b7b52e6c703f2292730996ce838a7538497762` | `20.33.0` | `2020-08-27` |

The baseline is the migration commit's direct parent. Versions are checked against
both consumer POMs and immutable Stripe SDK source revisions in
[provenance.json](provenance.json). SDK `RequestOptions` pins its default version
to `Stripe.API_VERSION`; the selected consumer builds options without a version
override. These are source facts, not a measurement of any live account.

This is a request/response case. It makes no claim about an account's default
version, webhook version, Checkout behavior, or the separate Basil subscription
period-field migration. The historical SDKs are reproduction inputs, not versions
recommended for new integrations or workbench production dependencies.

## Case contents and rights

- [Consumer excerpts](consumer/baseline.java.txt) preserve the original
  `getPaymentMethods`, `syncPaymentMethods` and `buildRequestOptions` bodies.
  The [reference excerpts](consumer/upstream-reference.java.txt) retain the
  historical migration's corresponding bodies. Enclosing classes and imports are
  omitted; these text files are evidence, not compilable classes.
- Both upstream consumer revisions contain an Apache-2.0 license and the copied
  file's copyright header. The headers and modification notice are retained here;
  [LICENSE](../../LICENSE) supplies the license and [NOTICE](../../NOTICE) records
  attribution. Neither upstream revision's tree contains a separate NOTICE file.
- The JSON response fixtures and expectations were authored for this case. They
  contain synthetic identifiers and minimal fields, no customer captures or keys,
  and are distributed under this repository's Apache-2.0 license.
- Stripe SDK source, SDK binaries and Gson are referenced and hashed, not vendored.
  Stripe SDK source/POMs declare MIT; Gson's parent POM declares Apache-2.0.
  Execution packaging must retain their applicable licenses and notices.
- Provider documentation is linked and summarized, not copied into this package.
  Nothing here implies endorsement by Stripe or Kill Bill.

`provenance.json` pins original file bytes and future execution artifacts with
SHA-256. `integrity.json` pins local case inputs, including the provenance index.
JSON and excerpt files use UTF-8 and LF. Hashes detect changes, not authenticity
or approval; changes to expectations or fixtures require a new experiment identity.

## Expected behavior

[expectations.json](expectations.json) is separate from the consumer/reference
code. Its assertions come from the refresh operation's purpose, the pre-existing
source-of-truth reconciliation flow, and Stripe's distinction between omission and
an empty collection. They were checked against those sources rather than inferred
from the repair's output. This is a documented contract review, not an independent
human approval or execution result.

| Scenario | Controlled input | Required behavior |
| --- | --- | --- |
| `modern-method-survives-omission` | A known modern card method; no legacy sources | Complete and retain the modern method when customer sources are omitted |
| `empty-account-survives-omission` | No local or remote methods | Complete with no methods and no deletion |
| `legacy-method-not-lost` | A known legacy card still exists remotely, but the target customer omits sources | Retain that card; omission alone cannot justify deactivation |
| `present-legacy-collection-control` | A legacy card collection is explicitly present | Complete and retain the card |
| `confirmed-removed-legacy-method` | A local legacy card is genuinely absent remotely | Deactivate the stale record after establishing remote absence |

The collection-present scenario is a controlled response-shape check. Its target response is
not a claim that an unexpanded live request returns sources by default. For the
legacy scenario, `sourceListResponse` supplies the explicit source-list response
if an eventual repair fetches it. A runner must not silently inject that list into
the original customer response or assume that every consumer actually requested it.

The reference null guard may satisfy the first two scenarios while failing the
third. Preserve that distinction. Do not weaken the contract to mark the historical
patch successful, and do not treat a blanket catch-and-return-empty as a repair.
The confirmed-removal scenario rejects the opposite shortcut of keeping every
cached method forever. A passing final set alone does not prove reconciliation:
the runner must also record whether remote absence was established before deletion.

## Execution boundary

The local runner preserves the selected method bodies and exercises their ordering,
reconciliation and deletion behavior with the actual pinned SDK types. Database
and platform services are controlled test doubles; generated source assembly and
the [documented service substitutions](../../docs/stripe-case-runner.md) expose the
adaptation. Results apply to the isolated methods, not a full Kill Bill installation.

All variants use one declared Java 21 environment, isolated SDK classpaths and the
same fixtures, expectations and harness. Runtime/harness digests and actual
observations are recorded per run. A whole-plugin upgrade may fail to compile for other
reasons (including Checkout method changes); that is outside this isolated case.

Fail closed on unexpected network access. Only local fixture transport is allowed;
no Stripe credentials, live Stripe calls, host secrets or arbitrary upstream build
scripts are needed. Validate request version headers, response selection and
unexpected calls when implementing the transport. No pagination beyond a single
page is covered. Transactions, concurrency, real database writes, provider errors
and production API behavior remain outside the first case's coverage.

Execution shows the target dereferencing a missing collection and the reference
skipping it before deactivating the legacy record. Stripe's current changelog also describes the
Java SDK as unaffected; it does not establish that this consumer's runtime flow
is safe. The pinned model has a nullable reference field without an initializer.
The SDK's unchanged field shape does not prevent this observed runtime failure.

The historical repair is publicly available and may be known to models. This is
a reproducibility case, not held-out evidence of agent generalization.

## Check the input package

From the repository root with PowerShell 7:

```powershell
./scripts/Verify-CuratedCase.ps1
```

This checks local file hashes, scenario coverage and fixture structure without
network access or executing consumer code. A successful check means the curated
package is internally consistent; it does not mean the migration passed.
