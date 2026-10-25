# ADR-009 — Memory-only browser GW2 credentials

## Status

SUPERSEDED by Product Owner decision [UD-018](../../../agent/user-decisions/UD-018-proportionate-gw2-key-handling.md), 2026-10-25. TARGET_ARCHITECTURE section 8.2 now selects simple browser session retention and proportionate handling. The original rationale below is historical, not a requirement for current work.

## Context

AR-009 requires an explicit Phase 6 browser storage decision. The roadmap requires browser-held keys, backend-owned upstream calls and no durable server key storage. TARGET_ARCHITECTURE section 8.1 already establishes validated account sessions; this decision consumes that identity contract without choosing authentication again. Current-state documentation reports environment-based single-account credentials and no browser credential handling, so this is target guidance, not an implementation claim.

## Constraints

Stable account datasets survive key replacement. Credentials remain transient at the backend and absent from logs/errors. Late browser responses must not restore another account's data. Existing cached-page performance, backend authorization, asynchronous operation ownership, domain boundaries and test layers remain in force.

## Decision

Class C — architecturally significant technical decision. Select private document-memory-only key retention with explicit credential-bearing requests. TARGET_ARCHITECTURE section 8.2 owns the normative lifetime, clearing/replacement, generation checks, shared-cookie context binding and transport contract. Section 8.1 continues to own identity, grants and server session lifetime.

Requires Product Owner Decision: NO. Selecting the explicitly delegated storage mechanism and its technical lifecycle does not introduce a new supported user/identity model or durable credential custody. Reload re-entry is an explicit consequence of the selection, not an assumed existing requirement.

## Rationale

Engineering judgment: this project needs a key while its browser document performs account operations, but has no established requirement for automatic sign-in after reload or restart. Memory retention satisfies that need with the least application-controlled credential persistence. It needs no storage library, encryption-key management, database, new service or recurring cost. It fits the existing Vue/TypeScript HTTP boundary and bounded implementation stories; domain calculations and rendering data shapes do not change. The choice introduces no extra upstream check on ordinary cached reads within a valid session, and makes no new measured performance claim.

The identity cookie is shared across tabs, whereas the key is document-local. Aborting a request does not prevent every late callback or cookie update. Local generations prevent old browser work from committing; the session-bound non-secret context assertion prevents an older tab's request from reading the account selected by a newer shared cookie. These are transport consistency measures, not another bearer credential or independent application login. Parallel account views in one shared cookie scope would require a different access design and are not inferred from multi-account server support.

Memory is still exposed to hostile executing script and privileged local tooling. Its benefit is limiting retention and copies, not XSS resistance or guaranteed physical erasure. The cost is re-entry on document reload and explicit recovery after conflicting tab session changes. In this project's documented constraints, that cost is preferable to persisting a reusable GW2 capability merely for reload convenience.

## Alternatives Considered

- **sessionStorage (runner-up):** standard browser support, tab separation and reload continuity make it simple and convenient. MDN documents persistence across reload/restore and initial copying from an opener. It remains script-readable and introduces storage/restore/copy cleanup obligations. Engineering judgment: reload convenience does not justify those additional credential copies without an established persistence requirement.
- **localStorage or IndexedDB:** support restart continuity and a remembered-key experience. That experience is not required; durable origin storage lengthens exposure and complicates clearing across tabs. IndexedDB adds asynchronous storage machinery for a tiny secret. OWASP advises against storing sensitive information in local storage.
- **Application-encrypted browser storage:** encryption with a key available to the same script does not isolate the credential from that script. A separately supplied passphrase or platform vault adds recovery/unlock behavior and maintenance with no documented need. Revisit only if durable retention becomes an explicit requirement.
- **Cookie containing the GW2 key:** automatic attachment expands credential transport. HttpOnly would prevent the browser from retrieving the key for the required explicit operation input; a readable cookie keeps script exposure. The existing opaque session cookie serves a different purpose and contains no GW2 key.
- **Backend credential vault:** violates the established no-durable-server-key model and adds custody and operational responsibilities. Rejected within this request's constraints.

## Consequences

Users re-enter a key after reload, restart or opening another document. Clearing is immediate locally; server revocation while offline cannot be guaranteed beyond the existing lease. Already admitted account work may finish under its immutable backend scope. Same-account key replacement reuses persisted data but reloads browser state under newly validated grants.

No new dependency support lifecycle is introduced: ordinary JavaScript memory and browser APIs suffice. Credential policy is isolated at the browser/HTTP edge and can be revisited without changing domain logic or account schema. Moving to durable retention later would require an explicit security/UX decision and storage cleanup/migration; it is not an implementation option silently left open.

Planner follow-up required: YES. Translate section 8.2 into bounded browser and transport work, preserving section 8.1's identity contract and section 9.1's recovery/performance constraints. QA owns the independent verification plan. Schema migration is not a prerequisite to making this decision. No source code, stories or tests were changed by this pass.

## References

- `docs/TARGET_ARCHITECTURE.md` sections 8–8.2, 9.1, 11 and 16.
- `docs/ROADMAP.md` Phase 6 objective and exit criteria.
- `docs/CURRENT_STATE_SPEC.md` runtime/configuration and limitations.
- `docs/architecture/decisions/ADR-008-account-identity-and-access-scope.md` (established identity contract; no other request was read).
- `docs/TEST_STRATEGY.md` sections 12 and 31; `docs/QUALITY_METRICS.md` coverage KPI.
- [MDN sessionStorage](https://developer.mozilla.org/en-US/docs/Web/API/Window/sessionStorage), checked 2026-10-17: tab partitioning, reload/restore lifetime and opener copying.
- [OWASP HTML5 Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html), checked 2026-10-17: sensitive local-storage data and script access risks.
