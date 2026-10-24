## Story ID

STORY-WEB-036

## Title

Show a credential-required state for unavailable account data

## Status

TODO

## Milestone

milestone-06

## Goal

Keep existing account-dependent browser pages understandable during the schema cutover by asking for a validated key when account data is unavailable, without exposing raw schema errors or another account's data.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` Phase 6 account isolation and post-reset usability exit criteria.
- `docs/TARGET_ARCHITECTURE.md` §§7.4, 8.1–8.2, missing sources, proof and browser credential handling.
- `agent/stories/STORY-WEB-035-memory-only-account-credential-lifecycle.md`, final browser lifecycle contract.
- `agent/stories/STORY-API-010-validated-account-access-foundation.md`, proof and error semantics.
- `docs/DOMAIN_SPEC.md` §§2.2.1, 39–40 and 55, existing account-dependent screens.
- `docs/TEST_STRATEGY.md`, browser behavior verification.
- `docs/QUALITY_METRICS.md`, affected coverage review.

## Context

The Product Owner reports raw fail-closed schema errors on account pages after the cache reset. Full credential lifecycle and scoped workflows are queued separately. An immediate browser state can explain unavailable account data without pretending that a missing source is an empty successful sync.

## Acceptance Criteria

1. Existing bank, materials, characters, Crafting Profit, Discovery and Luck views show a clear account-data-unavailable message with an enter/validate-key action when a required account source has no authorized scope or is missing after reset. No raw database exception is shown as page content.
2. A 401 or missing proof leads to the credential-required state; a 403 context/grant mismatch clears account-derived display and presents safe revalidation guidance. A retryable 503 preserves the distinction between temporary proof unavailability and an empty account dataset.
3. The state exposes no old account rows, calculations, details or task associations after account change or failed proof. Global-only content may remain visible, and the UI does not trigger account sync without verified scope and an explicitly supplied key.
4. Once a later scoped workflow supplies valid account data, the state yields to the existing page content; do not fabricate freshness or successful synchronization for missing sources.

## Required Tests

- QA covers the affected page families and 401/403/503 or missing-source responses with independently distinguishable account fixtures, checking safe messaging, state clearing and recovery. Review affected frontend coverage.
- Include a browser smoke check for the schema-cutover unavailable state because browser smoke is local-only; QA decides the concrete script and fixtures.

## Constraints

- Preserve the existing browser pages and do not implement the full STORY-WEB-035 credential lifecycle here.
- Do not persist a GW2 key or bypass backend account access; keep messages free of secrets and schema internals.
- Coding does not write or change QA-owned tests or fixtures.

## Dependencies

STORY-INFRA-004.

## Definition of Done

- Account pages show a safe credential-required state during cutover, with QA evidence for recovery and no account leakage. The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

None.
