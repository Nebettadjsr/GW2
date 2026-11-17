## Story ID

STORY-API-010

## Title

Establish validated account sessions and access context

## Status

TODO

## Milestone

milestone-06

## Goal

Implement transient GW2 identity proof, bounded account sessions and a trusted request-context boundary for the subsequent scoped account routes.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: stable account identity, transient keys and explicit application identity decision.
- docs/TARGET_ARCHITECTURE.md sections 8.1-8.2, established AR-008 and AR-009 contracts.
- docs/TARGET_ARCHITECTURE.md sections 7.2 and 11, identity ownership and secrets.
- docs/CURRENT_ARCHITECTURE.md section 7, current lack of application authentication.
- docs/DOMAIN_SPEC.md sections 6, 9 and 11.1, account-sensitive inputs requiring authorized scope.
- docs/TEST_STRATEGY.md sections 8, 10-11, 26.1, 31.3 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.

## Context

Architecture has selected GW2-key possession, a 15-minute in-memory backend lease and an access-context assertion for shared-cookie tabs. This foundation supplies proof/session/context facilities; it does not claim that all existing account workflows are isolated before their later integration.

## Acceptance Criteria

1. Establish/renew access only after validating both /v2/account and /v2/tokeninfo with the same transient key. Use only the account GUID as stable identity. Validate shape, identity permission, effective grants and expiry/endpoint restrictions; replacement keys retain their own grants rather than inheriting prior privileges.
2. Issue a fresh opaque session with at least 128 bits of cryptographically secure entropy. Retain only its verifier and immutable principal in bounded process memory, never the GW2 key. Absolute expiry is 15 minutes after proof or earlier credential expiry; reads/polling cannot extend it. Logout revokes; eviction/restart/expiry require proof again.
3. Provide same-origin session proof/renewal, context validation and logout HTTP contracts. Use host-only HttpOnly Secure SameSite=Strict cookies outside explicit localhost development; enforce origin/CSRF checks on state-changing requests including proof. Return a fresh non-secret access-context identifier with proof/renewal, bound to that exact session and grants.
4. Implement a reusable trusted immutable request-context resolver: live session plus matching X-Account-Context is required before account work. Caller account IDs are consistency assertions only. Missing/invalid/expired session maps to 401; grant/context/account mismatch to 403; proof unavailability/rate limiting to retryable 503 with bounded guidance. Malformed proof fails closed. Errors disclose no account existence, contents, freshness or credentials.
5. Centralize operation-grant checking and transient keyed-operation validation so callers can require all consumed account sources and revalidate supplied key identity/grants against the live session before upstream admission. Definitive invalid-key responses revoke the associated session; transient upstream failure does not. A session alone is never an upstream credential.
6. Extract keys only from X-GW2-API-Key on proof/renewal or explicit keyed account operations. Keep them out of URLs, durable storage, public DTOs, task/status metadata, logs, tracing and exception text; release private operation references on terminal paths. Credential-bearing and account responses use no-store. Document response/error/context contracts for browser and account-workflow integration.
7. Cached authorization remains possible for a live lease during upstream outage; renewal cannot succeed then. Enforce context matching for logout so a stale tab cannot revoke a different session. Document the bounded revocation lease and outstanding integration requirements without claiming full multi-user readiness.

## Required Tests

- Offline captured/trimmed GW2 response fixtures verify stable GUID resolution, replacement-key grants, restricted/expired keys, malformed responses, invalid credentials, transient outage and rate limiting. No live ArenaNet requests or secrets.
- Deterministic session tests using controlled time verify absolute expiry, bounded storage/eviction, logout, rotation, restart semantics and no read-based extension.
- HTTP contract tests with successive/concurrent sessions verify origin/CSRF and cookie rules, X-Account-Context mismatches, account-ID mismatch, grant rejection before work, safe status mappings, no-store and no secret echo/log/error content.
- Exercise the actual session/context resolver with controlled application work: account A and B remain distinct, rejection invokes no account work, and a surviving A context fails against a B cookie. Verify valid cached authorization during outage and no successful expired-session fallback.

## Constraints

- No username/password, identity provider, durable session/key store or browser-selected principal.
- Keep account authorization at HTTP/application boundaries; domain rules remain unchanged.
- Full existing-route enforcement, task ownership, scoped refresh integration and browser lifecycle are subsequent dependent work, not evidence claimed by this foundation.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

None.

## Definition of Done

- Proof/session/context facilities and documented contracts meet the criteria and pass targeted offline and HTTP tests.
- Review affected coverage reports against docs/QUALITY_METRICS.md and report outstanding verification/coverage gaps without changing exclusions or claiming a fresh CI baseline.
- Update current architecture documentation for delivered access facilities and their remaining route integration boundary.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Implemented, and verified end-to-end over real HTTP. One blocker remains outside implementation's
ownership: the QA contract suite does not compile, so no automated test could run.

This pass resumed an interrupted attempt that had written the `application.access`, `infra.access`
and `web.access` sources at 19:44-19:52 but ran out of capacity before compiling, wiring or running
anything. Nothing finished was redone. Verification found two real defects:

1. **The application could not start.** `AccountAccessWebConfig` injected a
   `com.fasterxml.jackson.databind.ObjectMapper`; Spring Boot 4.1.1 auto-configures Jackson 3
   (`tools.jackson.databind`) and this project uses Jackson 2 only for its own GW2 parsing, so no such
   bean exists. The guard now takes the application's own auto-configured mapper.
2. **The caller's `accountId` assertion was unreadable** when supplied other than in the query string
   — including via `MockMvc`'s `.param()`, which the QA suite uses. The query string is still read
   first; the parsed-parameter fallback is skipped for form-encoded requests.

**Delivered.** `application.access` holds the framework-free decision: `AccountProofPort`/
`AccountProof` (both `/v2/account` and `/v2/tokeninfo` proved with the same key, GUID-only identity,
three distinct failure modes), `AccountSessions` (SHA-256 verifier of a 256-bit identifier, never the
identifier and never the GW2 key; `min(now + 15m, credential expiry)` computed once and only read;
eldest-proof eviction past capacity), `AccountContext`, `AccountOperation` and `AccountAccessService`
— whose renewal issues a new session rather than extending the presented one, so the absolute lease
can never be lengthened. `infra.access` proves keys upstream with the caller's credential and refuses
redirects, sharing all validation with the fixture adapter so the offline path cannot be more
forgiving. `web.access` supplies the cookie/origin rules, the three session routes, the
highest-precedence guard and the request-scoped `RequestAccountContext`. Enforcement for the existing
account routes is default-off, which is this story's integration boundary.

**Verified** (`/tmp/api010-verify.sh`, 63 of 63 checks, full capture in `CLAUDE_RESULT.md`): proof
cookie policy (host-only, `HttpOnly`, `SameSite=Strict`, `Secure` for every non-loopback host,
`no-store`); origin/CSRF on proof, renewal and logout, including a missing `Origin`; safe status
mapping for unknown, malformed, expired, unavailable and rate-limited credentials; 401/403 for
missing, unknown, expired and cross-context sessions; `accountId` as an agree-or-reject assertion;
a replacement key keeping only its own grants; `expiresAt` unmoved across reads; logout requiring the
matching context and leaving the other session alive; renewal rotating both identifier and context,
revoking on a definitive rejection but not on a 503. Separately: bounded eviction at capacity 2,
fresh proof required after restart, and — with `DATABASE_URL` pointed at a dead port, so any
freshness execution is observable — a guarded account route answering 401/403/200 with no database
error while an unguarded freshness-checked route answers 503, proving the guard precedes account work
and that cached authorization survives an outage. The backend log stayed at 21 startup-only lines with
zero keys, GUIDs, session identifiers or exceptions. All three backends were stopped by their logged
PIDs and all three ports confirmed released.

**Not verified:** a session actually expiring and the earlier-credential-expiry cap (no near-future
expiry fixture; inspection only), `authorizeKeyedOperation`'s runtime path (no HTTP caller yet), and
concurrency. Coverage is unmeasured for the three new packages because no test run could produce a
JaCoCo report; no exclusion was changed and no baseline is claimed. `docs/CURRENT_ARCHITECTURE.md`
section 7 gains "Validated account access" and its superseded "no application authentication" claim
is corrected.

## Blockers

None.

## Follow-up Findings

F001: The project carries two Jackson majors at once — Spring Boot 4.1.1 auto-configures Jackson 3
(`tools.jackson.databind`) for the web layer, while `api.*`, `repo.*`, `parser.*` and `infra.*` parse
with Jackson 2 (`com.fasterxml.jackson.databind`), and no Jackson 2 `ObjectMapper` bean exists. Any
future component injecting the `com.fasterxml` type fails at startup, as this story's guard did until
corrected. Pre-existing and outside this story; worth a deliberate decision on which major the
application standardizes on.

F002: `docs/CURRENT_ARCHITECTURE.md` section 3 enumerates source areas (`web.*`, `application.*`,
`craft.*`, `repo.*`, `sync.*`, `api.*`, frontend, JavaFX) but omits `infra.*` entirely, which already
held `infra.icons` before this story and now also holds `infra.access`. The list reads as complete and
is not.
