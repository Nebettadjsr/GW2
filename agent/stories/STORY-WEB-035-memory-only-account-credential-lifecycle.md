## Story ID

STORY-WEB-035

## Title

Implement memory-only browser credentials and account lifecycle

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Let users prove GW2 account access with a document-memory-only key and use isolated account workflows without stale responses or shared-cookie tab changes exposing another account's state.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: browser-held transient GW2 credentials and strict account isolation.
- docs/TARGET_ARCHITECTURE.md sections 8.1-8.2 and 9.1, resolved AR-008/AR-009 contracts.
- docs/DOMAIN_SPEC.md sections 2.2.1, 39-40 and 55, preservation of account selection/freshness and Luck presentation.
- docs/TEST_STRATEGY.md sections 12-14, 26.1 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.
- agent/stories/STORY-API-010-validated-account-access-foundation.md.
- agent/stories/STORY-API-011-enforce-account-isolation-across-http-workflows.md.

## Context

Architecture selects private document memory for credentials and a backend access-context assertion to detect shared-cookie changes. This story integrates those contracts into existing browser pages and recovery flows; it adds no account identity or domain policy.

## Acceptance Criteria

1. Provide masked key entry and explicit clear/logout/replacement flows. Keep the key in a private holder outside serializable/reactive stores, clear the input after submission and retain it across SPA navigation only. No key enters persistent browser storage, cookies, URL/history/router state, telemetry, console output or general application snapshots; no remember-key feature is added.
2. A surviving cookie alone never restores account content without entry and fresh proof. Reload, new/duplicated tab and browser restart require entry. On pagehide clear key references, entry fields and account state; back/forward-cache restore starts at the credential gate. Mask account content while suspended and validate the current context before resuming.
3. Maintain a monotonically changing local credential generation. Clear/replacement/logout/loss of context invalidates the old generation before asynchronous callbacks can commit. Abort obsolete requests and stop timers, while independently rejecting late success, error and cleanup callbacks for proof, renewal, reads, calculation/detail, polling and retry. Clear all account rows, selected character, details, source settings, freshness, tasks and dependent caches. Only identity-independent settings/global metadata may survive.
4. Replacement releases the old key and account view before proving the candidate. Failed proof leaves the view empty. A same-GUID replacement still obtains a fresh session and reloads under its actual grants, reusing only backend persisted data. Definitive invalid-key responses clear credentials/state; lease expiry may retain the key for serialized renewal, and transient proof failure may retain a candidate for explicit retry without restoring prior data.
5. Send X-Account-Context on every account request, including polling and logout, and reject mismatched response contexts. Use BroadcastChannel invalidation-only messages for clear/replacement/session rotation; receiving tabs clear state and wait for entry without automatically re-proving. Context mismatch clears locally without revoking a different tab's session. Delayed/absent notifications and out-of-order proof replies still fail closed through server context and local generation checks.
6. Send X-GW2-API-Key only on proof/renewal and explicit keyed account operations through same-origin /api routes; refuse redirects for keyed requests. Do not attach it to global requests, cached-only reads, polling, status or logout. Account/credential traffic bypasses caching/offline replay. The frontend never calls GW2 directly.
7. Integrate credential-required refresh and scoped tasks on existing account screens, calculations/details and System Status. Preserve the established bounded recovery cycle and settings within an unchanged authorized generation; account changes reset account-derived state. Cached authorized results do not require a new upstream proof per read. Clear works offline immediately; failed server revocation does not claim the lease was revoked or delete stored account records.

## Required Tests

- Deterministic frontend tests cover private-holder lifetime, request header selection, redirect refusal, generation/context rejection and complete state clearing. Exercise deferred proof/read/detail/poll/renewal success, error and cleanup callbacks after clear, replacement and renewed generations, including same-GUID lower-grant replacement.
- Test serialized renewal, invalid key versus transient failure, credential-required refresh, bounded recovery and offline logout without fabricated server revocation.
- Explicitly run a real-browser credential/account lifecycle smoke check against the integrated backend with controlled offline upstream fixtures: SPA navigation, reload/new/duplicated tab, pagehide/back-forward restore, two tabs sharing cookies, delayed invalidation and out-of-order proof. Verify displayed account rows never cross scope and inspect application-controlled storage/network for credential persistence or unintended transmission. Record the concrete script/command and limitations; browser smoke is excluded from CI.
- Run relevant frontend tests and type-check/build; backend HTTP context isolation remains verified by API-011. Add behavior-based regressions for any confirmed defect. No normal automated test requires live credentials, GW2 or a populated developer database.

## Constraints

- Follow section 8.2 exactly; no durable key vault, separate app authentication or frontend domain calculations.
- Do not promise independent simultaneous accounts in tabs sharing cookies, secure memory erasure or cancellation of admitted backend work.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

STORY-API-011

## Definition of Done

- Browser lifecycle and integrated account workflows satisfy the criteria with targeted state tests and recorded real-browser evidence.
- Review affected frontend coverage under docs/QUALITY_METRICS.md and report unresolved verification gaps without padding tests or changing measurement scope.
- Update current browser/runtime documentation to describe delivered memory lifetime and shared-cookie behavior.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-API-011, which is not DONE (currently BLOCKED)
