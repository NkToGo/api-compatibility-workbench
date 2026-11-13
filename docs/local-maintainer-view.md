# Local maintainer view

Inspect one recorded customer-sources comparison through a local, read-only web
view. It shows the five declared scenarios, the unrepaired upgrade, the historical
reference patch, blocking issues and exact input identities. The view derives
comparisons and repair outcomes from the supplied observations; it ignores the
producer's claimed conclusions.

## Start

Build and verify the backend using the commands in the [README](../README.md#build-and-test).
Then run the curated case in PowerShell 7:

```powershell
./scripts/Run-StripeCase.ps1
```

Use the `report.json` next to the printed `report.html`. Select that exact file at
startup, replacing the example run ID:

```powershell
./scripts/Start-MaintainerView.ps1 -Report ./backend/target/case-runs/RUN_ID/report.json
```

Open the printed `http://127.0.0.1:8765/` address. Stop the process with Ctrl+C.
Use `-Port 0` to request an available local port, or choose another port with
`-Port 8766`. Startup reports a generic failure for an unsupported/malformed report
or unavailable port. It does not print report contents or local source paths.

The runner uses local synthetic responses and pinned historical dependencies;
neither the runner nor this view calls a model or live Stripe. Keep reports outside
`backend/target` if they must survive Maven `clean`. The existing offline
`report.html` remains available independently.

## Read the result

- **Original** is the baseline. A failed baseline is a blocking comparison issue.
- **Upgrade** is the same extracted source under the declared target conditions.
- **Historical patch** is the retained reference source, not a proposed release.
- **Unresolved** preserves missing/duplicate records, changed input identities and
  incomplete or unevaluated assertions. Known regressions elsewhere remain visible.
- Expand a scenario for the supplied observation metadata. Expand advanced details
  for comparison issues and declared input identities. The JSON link exposes the
  same allowlisted projection, not the original report.

The curated unmodified report records four upgrade regressions and two historical
patch regressions: two fixed, two remaining and one passing control. These numbers
are computed, not hard-coded acceptance results. Altered or incomplete evidence can
change them.

## Authority and limits

This is a **supplied recorded report** view. It does not authenticate executions,
revalidate retained artifact inventories, compare against a current checkout or
make a release decision. A SHA-256 identifies the selected report bytes; it is not
a signature or a trust certificate. The displayed timestamp is a producer-supplied
recording time. All results remain limited to the declared synthetic scenarios.

The server loads one bounded snapshot at startup. Later filesystem changes do not
change that snapshot; restart to load another report. The accepted input is the
version 1 local `stripe-customer-sources-2020-08-27` report with its declared five
scenarios and transition. Invoice/subscription campaigns and arbitrary repositories
are not supported by this view. There is no upload, report path selector, filesystem
browser, execution control, authentication service or repository integration.
The projected JSON format is versioned separately as version 1 and is local to
this interface; it is not the original report's full wire contract.

## Local access boundary

The server binds only IPv4 `127.0.0.1`. It requires the exact address/port in `Host`
and rejects foreign browser origins and cross-site fetch metadata. Only exact
`GET`/`HEAD` routes for `/`, `/api/report` and `/assets/maintainer.css` are served;
queries, encoded aliases, request bodies and other methods are rejected. No CORS
permission is granted. Responses disable caching, framing, external scripts and
external assets. The process uses a bounded request executor.

Input reads are limited to 1 MiB and reject invalid UTF-8, duplicate JSON fields,
excessive nesting/collections, malformed domain records and linked/special paths.
Raw report details, artifact paths, payloads and producer-written assessments are
excluded from the projection. The selected file and its parent directories must
be on a trusted local filesystem and must not be replaced by another process while
opening the snapshot. This is not sandboxing against a malicious local account.

Other local processes can read the view. Loopback origin checks are not user
authentication or organization isolation. Do not reverse-proxy, tunnel or expose
this listener to other machines. A shared service requires the separate access,
retention and deployment boundaries in the [roadmap](roadmap.md).
