# AR-009 - Browser GW2 key storage

## Status

RESOLVED

## Architecture Question

Select and document the browser-side GW2 API-key storage mechanism for Phase 6, including its lifetime and clearing/replacement behavior, and the browser/backend credential transport boundary.

## Context and Constraints

Phase 6 explicitly requires this storage decision. TARGET_ARCHITECTURE section 8 leaves it undecided: the browser retains the key, all GW2 API calls remain backend-owned, and the backend receives credentials only as transient operation input. No API key may enter durable server/database storage; logs and errors must not expose it.

Assess the security and usability consequences of candidate browser storage mechanisms rather than silently choosing one in a browser story. Define reload/tab/session behavior, clearing and replacing credentials, and how credential changes invalidate outstanding account-specific browser state so late responses cannot restore another account's data. Coordinate with the identity/access contract requested in AR-008; do not assume its authentication choice or duplicate that decision. The browser storage choice can be evaluated independently of schema migration.

## Authoritative References

- docs/ROADMAP.md, Phase 6 Exit Criteria and High-Level Stories (supplied planning excerpt).
- docs/TARGET_ARCHITECTURE.md, sections 8, 11 and 16, To Be Decided.
- agent/architect-requests/AR-008-account-identity-and-access-scope.md.

## Blocked Work

Browser credential-entry/storage and account-change stories, safe credential transport wiring, and browser verification of credential replacement and account-state clearing. No speculative browser storage implementation is planned before this explicit decision.

## Blocking User Decision

None.

## Architect Decision

Class C — architecturally significant technical decision. Select private document-memory-only browser GW2 key retention. SPA navigation retains it; reload, document exit/restore, a new tab and browser restart require re-entry. Clear/logout and replacement invalidate the old browser generation before any asynchronous result can commit; even same-account replacement reloads state under freshly validated grants. The normative contract is TARGET_ARCHITECTURE section 8.2; ADR-009 records rationale and alternatives.

Engineering judgment: no established requirement demands reload/restart credential persistence, so minimizing reusable credential copies is the strongest fit for this small Vue/HTTP application. sessionStorage is the runner-up for reload convenience, but its script-readable storage, restore lifetime and opener copying add retention and clearing obligations. localStorage/IndexedDB retain secrets longer; application-managed encryption adds complexity without isolating a secret from the script that can decrypt it. No new dependency, service or durable server credential storage is needed.

Consume the established section 8.1/ADR-008 identity contract without deciding authentication again. Send keys only on explicit proof/renewal and keyed upstream-operation requests through a redacted header over the same-origin HTTPS boundary. Ordinary cached reads and polling use the existing cookie plus a non-secret session-context consistency assertion. Local generations, response-context checks and shared-cookie tab invalidation prevent late answers or another tab's cookie rotation from silently restoring another account or earlier grants. The GW2 key never becomes a session identifier, public DTO, task record, log entry or durable server data.

Requires Product Owner Decision: NO. This resolves the explicitly delegated technical storage question within the browser-held/transient-backend model. The reload re-entry and shared-cookie limitations are documented consequences, not invented prior requirements.

## Resolution

Updated docs/TARGET_ARCHITECTURE.md section 8 with the section 8.2 lifecycle/transport contract and replaced the browser-storage TBD in section 16 with the recorded decision. Added docs/architecture/decisions/ADR-009-browser-gw2-key-storage.md for rationale, rejected alternatives and consequences. Existing identity rules were consumed from section 8.1 and ADR-008; no other architect request was read or edited.

Planner follow-up: translate the contract into bounded credential-entry/lifecycle, explicit transport, shared-cookie context checking and account-state invalidation work; reconcile story acceptance with reload re-entry, offline logout limits, same-account replacement and stale asynchronous answers. Preserve the existing cached-page/recovery contract and independent QA planning, browser/HTTP/application verification and real PostgreSQL isolation requirements. No schema migration choice was made. No additional architecture problem requiring work outside this request was identified.
