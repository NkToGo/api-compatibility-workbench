# Read-only GitHub repository connection

The host can inspect one explicitly selected GitHub.com repository revision using
a GitHub App. It verifies the app, installation, owning account, effective token
scope and exact commit before returning a metadata snapshot. This command neither
downloads source nor assesses compatibility. Source/version inventory, durable
repository registration, browser integration and PR publication remain planned.

The implementation has credential-free loopback tests for signing, scope checks,
provider errors and transport limits. Real installations require their own access
check; passing fixtures do not establish that an installation works.

## Configure a host-owned GitHub App

Create an app in GitHub's developer settings with **Contents: read-only** and the
automatic **Metadata: read-only** repository permission. No organization/account
permissions, user authorization callback, webhook subscription or write permission
is needed for this command. A repository homepage URL can be used as the required
app homepage; the CLI does not require a public callback server. Install it using
**Only select repositories**, selecting the intended repository. All-repository
installations are refused even though each token request is narrowed separately.
Account/organization restrictions may require an administrator to allow the app.

Generate the app's private key and keep it outside the checkout, accessible only
to the host/service account. Both GitHub's downloaded PKCS#1 RSA PEM and unencrypted
PKCS#8 RSA PEM are supported, with 2048–8192 bit keys. Encrypted PEM, non-RSA keys,
oversized files and linked/special credential paths are refused. This is local
key-file loading, not encrypted storage or hardware-backed signing. Use trusted
local directories that cannot be replaced while the file is opened.

Set these environment variables in the host process, without putting key contents
in a shell argument, repository, report, browser or worker:

| Variable | Value |
| --- | --- |
| `WORKBENCH_GITHUB_APP_ISSUER` | App client ID (preferred) or numeric app ID |
| `WORKBENCH_GITHUB_APP_PRIVATE_KEY_FILE` | Path to the protected PEM file |

The command does not load `.env` files or use a personal access token, browser
session, Git credential helper or anonymous fallback. The key signs short-lived
RS256 JWTs on the host; installation tokens remain in process memory and are
excluded from snapshots and diagnostic output. JVM memory is not guaranteed to be
zeroized. The command discards its token when finished; GitHub token expiry is not
immediate revocation. No token-renewal or revocation service is implemented here.

## Select a repository and revision

Copy [the selection example](../examples/github-repository-selection.json) to a
local configuration file. **Every example ID and the zero commit SHA are dummy
values.** Replace them with the expected installation ID, owner account ID,
repository ID, owner/name and full 40-character lowercase commit SHA. These IDs
can be obtained from the installation and repository metadata in GitHub's API.
Branch names, shortened hashes, URLs and extra configuration fields are refused.
The selection is host/operator input; do not let assessed source or an agent
choose installation/account/repository scope.

After [building and verifying](../README.md#build-and-test), run in PowerShell 7:

```powershell
./scripts/Inspect-GitHubRepository.ps1 -Selection ./local-repository-selection.json
```

The JSON result contains the verified app ID, selected identities, commit/tree
SHAs, observation time and token expiry. It deliberately contains no source,
commit message, repository description, API token or private key. Store it outside
Maven build output if it must be retained. It is an observation, not a persistent
connection, future access grant, commit-signature verification or release approval.
Later actions must revalidate their own current authorization and exact inputs.

## Request and failure boundaries

Requests use the GitHub REST API version `2026-03-10` and only
`https://api.github.com`. There is no configurable production API origin, proxy,
redirect following, clone command or returned-URL traversal. Renames, transfers
and redirects require explicit reselection. GitHub Enterprise Server is unsupported.

The serial request sequence is:

1. Read `/app` and match the configured issuer to the authenticated app.
2. Read the selected installation; match app/account/owner and reject suspension
   or all-repository scope.
3. Request one installation token with exactly the selected repository ID and
   read-only contents/metadata permissions. Refuse unexpected permissions, malformed
   credentials or invalid expiry. Tokens are opaque and may use the newer long
   stateless format; no fixed 40-character token length is assumed.
4. List the token's effective repositories and require exactly one matching ID,
   account and owner/name. An expanded scope is rejected rather than paginated.
5. Read the exact Git commit object and retain its returned tree identity. Source
   files, dependencies and build instructions are not read or executed.

Each response is limited to 256 KiB with a 30-second whole-response deadline.
Selection JSON is limited to 8 KiB and key files to 16 KiB. JSON parsing rejects
invalid UTF-8, duplicate fields, excessive nesting/collections and malformed known
fields. Authentication is never relaxed for a public repository.

The application makes no automatic retry, reauthentication or token renewal.
The launch script also disables JDK connection/non-idempotent retries at JVM
startup and clears the JDK HTTP diagnostic-logging property. Embedders must set
equivalent startup properties before initializing `HttpClient` and must not enable
credential-bearing HTTP header/body logging. The host/JVM itself remains trusted.

Failures expose a fixed code, stage, HTTP status, optional `retryNotBefore` time
and `tokenMayRemainActive` flag. Provider bodies, credentials and exception causes
are not printed. `403`/`429` preserve rate-limit/retry headers without guessing
that a forbidden response proves a particular permissions problem. Retry only
after the reported time and after resolving the cause. Missing/denied/expired
access stops the probe; no success snapshot is emitted. A lost token response or
a later failure can leave a narrow token active until expiry. Interruption likewise
has no automatic retry and cannot guarantee that token creation did not occur.

## Primary references

- [App JWT claims and signing](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app)
- [Installation token scope and current token formats](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app)
- [App and installation-token REST endpoints](https://docs.github.com/en/rest/apps/apps)
- [Effective installation repositories](https://docs.github.com/en/rest/apps/installations#list-repositories-accessible-to-the-app-installation)
- [Git commit objects](https://docs.github.com/en/rest/git/commits#get-a-commit-object)
- [Rate-limit and retry guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#handle-rate-limit-errors-appropriately)
- [JDK HTTP client startup properties](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html)
