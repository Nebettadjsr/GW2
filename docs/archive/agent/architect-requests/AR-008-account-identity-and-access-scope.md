# AR-008 - Account identity and access scope

## Status

RESOLVED

## Architecture Question

Decide the Phase 6 stable GW2 account identity and application access contract: is a validated GW2 API key and its resolved account identity sufficient, or is separate application authentication required? Define how authenticated scope reaches account reads, calculations, synchronization and task-status access without allowing a caller-supplied account identifier to grant access.

## Context and Constraints

Phase 6 requires multiple accounts in one PostgreSQL database and replacement keys to reuse the same account dataset. TARGET_ARCHITECTURE sections 7-8 establish stable GW2 identity, transient backend credential input and no durable server key storage, but leave application identity undecided. CURRENT_ARCHITECTURE sections 5 and 7 describe single-account refresh state and an unauthenticated HTTP API; these are current limitations, not the target contract.

Resolve identity validation, scope establishment and lifetime, failure behavior, and protection of account-specific asynchronous tasks and results. Explain how cached-data reads remain authorized when an upstream operation is not otherwise required. Preserve global cache sharing and account-agnostic domain rules. Do not assume username/password infrastructure. Browser credential storage is the independent question in AR-009; schema migration is AR-010. Decide the access contract without waiting for either implementation.

## Authoritative References

- docs/ROADMAP.md, Phase 6 Objective and Exit Criteria (supplied planning excerpt).
- docs/TARGET_ARCHITECTURE.md, sections 6.2-6.4, 7-9 and 9.1.
- docs/CURRENT_ARCHITECTURE.md, sections 5 and 7.

## Blocked Work

Implementation stories for account identity resolution and replacement-key reuse, request/application scope propagation, account-specific task admission and visibility, and cross-account API/application isolation tests. The Phase 6 identity/authentication exit criterion cannot be closed before this decision.

## Blocking User Decision

None.

## Architect Decision

Class C — architecturally significant technical decision. Validated GW2 API-key possession is sufficient application identity for the already approved Phase 6 multi-account model. No independent application login, password infrastructure or identity provider is required. The principal is an account capability, not an identified person.

Choose opaque, process-local account sessions established by backend validation of the same transient key through GW2 account and token-info endpoints. The canonical identity is the account response's persistent GUID; replacement keys reuse it. Effective grants constrain cached access as well as upstream work. Sessions have an absolute 15-minute validation lease, no stored GW2 key, explicit logout/restart/expiry behavior and no renewal without upstream proof. Caller-supplied identifiers never establish scope. The complete normative contract, including HTTP failures, immutable application/worker context, cached-read authorization, transient synchronization credentials and task/result visibility, is in TARGET_ARCHITECTURE section 8.1.

Engineering judgment favors bounded sessions over the runner-up, validating the key on every request: the latter makes cached reads and frequent status polling depend on upstream availability and repeated secret transmission. Separate application authentication solves an independent-user/linking problem not established by this project and adds unnecessary maintenance and operations. Opaque state fits the single-process runtime more simply than signed tokens with revocation infrastructure. The explicit trade-off is up to 15 minutes of cached access after unobserved upstream key revocation; definitive invalid-key responses revoke the associated session immediately. No Product Owner decision is needed to implement the documented account model.

## Resolution

Updated docs/TARGET_ARCHITECTURE.md section 8 with the access contract, moved identity out of the undecided list, and reconciled section 9.1's process-wide account-task exclusion with per-account coordination and protected visibility. Recorded rationale and alternatives in docs/architecture/decisions/ADR-008-account-identity-and-access-scope.md. Current architecture was not changed: these are target requirements, not implemented behavior.

Planner owns bounded work for proof/session establishment, stable-identity replacement-key reuse, explicit scope propagation through reads/calculations/persistence, transient credential admission, account-specific coordination/status/results and isolation verification under the existing testing/coverage strategy. QA independently prepares the concrete plans. Browser key storage and schema/legacy migration remain their independently dispatched questions; this decision does not wait for or answer them. The existing System Status projection must be filtered so account task history, failures and associations cannot leak through shared diagnostics; global execution status may remain shared. Session expiry during automatic recovery must require renewed proof without resetting section 9.1's recovery deadline. No source, tests, planner state or other architect request was modified.
