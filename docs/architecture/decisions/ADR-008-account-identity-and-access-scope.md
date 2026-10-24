# ADR-008 — GW2-key identity with bounded account sessions

## Status

ACCEPTED

## Context

AR-008 asks how Phase 6 proves access to cached account data and asynchronous work while reusing a stable account dataset across replacement keys. The roadmap already authorizes a shared multi-account application. Current unauthenticated HTTP routes and process-wide account refresh state are migration limitations, not an access model to preserve.

## Constraints

One PostgreSQL database, shared global caches, backend-owned GW2 communication, transient keys without durable server storage, strict account isolation and account-agnostic domain rules are established constraints. No requirement establishes independent application users, account delegation, or password recovery.

## Decision

Class C — architecturally significant technical decision. Validated GW2-key possession is sufficient identity; separate application authentication is not required. Use a short-lived opaque backend session carrying the resolved account GUID and effective grants, independent of retaining the GW2 key. The normative identity, lifetime, failure, propagation and asynchronous-access contract lives in `TARGET_ARCHITECTURE.md` section 8.1; account task coordination is reconciled in section 9.1.

Requires Product Owner Decision: NO. This implements the approved account model without introducing independent application-user ownership.

## Rationale

Engineering judgment: a bounded session is the simplest way to authorize cached reads and polling without sending a key or depending on ArenaNet for every read. Process-local state fits the established single-backend-instance task facility and supports immediate application logout without a database session store, signing-key lifecycle or extra service. An absolute validation lease bounds undetected upstream revocation; it is deliberately independent of data freshness. Source-based grants avoid turning a minimally privileged key into access to cached data obtained with a stronger key.

The GW2 API documentation identifies `/v2/account.id` as persistent account identity and documents token-info permissions and optional expiry/URL restrictions. These establish what proof can resolve; the application's lease duration and access rules are project decisions. OWASP guidance supports unpredictable session tokens, server-enforced expiration and protected transport. No benchmarks or ecosystem adoption claims decide this choice.

## Alternatives Considered

- **Live key validation on every account request (runner-up):** directly checks upstream validity without a session, but couples cached reads and task polling to upstream latency, outages and rate limits and repeatedly transmits the key. It conflicts with the purpose of the cached-result path more than the bounded lease does. It remains appropriate when establishing/renewing proof and admitting keyed upstream work.
- **Separate application login or external identity provider:** would support independent people, delegation and account linking if required later. Those capabilities are not required here; introducing them adds registration/recovery/linking and operational responsibilities without replacing the need to prove GW2 access.
- **Self-contained signed access tokens:** avoid an in-memory lookup but add signing-key management and revocation machinery for no established replica requirement. Opaque local sessions are simpler at this scale.
- **Trust an account GUID, key syntax/hash or prior synchronization:** cannot establish present access and would expose another account's cached data. Rejected for correctness and isolation.

## Consequences

Cached reads work during an upstream outage only within an already validated lease. After expiry or restart, validation must succeed again. Undetected upstream revocation is bounded rather than immediate. Anyone holding a suitable key can establish access to that account: this is an account capability model, not personal identity or an owner/delegate hierarchy.

Implementation must remove single-account fallback paths from multi-user boundaries, scope asynchronous state and account-derived caches, and preserve globally shared refresh coordination. Session expiry does not cancel authorized in-flight writes. Future multiple replicas would require revisiting both session routing/state and task coordination together. Separate authentication remains addable later if product requirements establish independent users; domain calculations need not change.

Planner follow-up required: YES. Convert the target contract into bounded implementation work and isolation verification; browser key storage and schema/legacy migration remain independent questions. No implementation or QA plan was created in this pass.

## References

- `docs/TARGET_ARCHITECTURE.md` sections 6–9.1 and 13.
- `docs/ROADMAP.md` Phase 6 objective and exit criteria.
- `docs/CURRENT_ARCHITECTURE.md` sections 5 and 7.
- `docs/TEST_STRATEGY.md` sections 8–11 and 35; `docs/QUALITY_METRICS.md` coverage KPI.
- [GW2 account API](https://wiki.guildwars2.com/wiki/API:2/account) and [token-info API](https://wiki.guildwars2.com/wiki/API:2/tokeninfo), checked 2026-10-17 through indexed documentation; direct page fetches returned 403.
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html), checked 2026-10-17.
