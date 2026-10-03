## Story ID

STORY-WEB-026

## Title

Align account browser smoke bank reload assertion with read behavior

## Status

SUPERSEDED

## Milestone

milestone-05

## Goal

Make the account browser smoke check accurately verify that reloading Bank repeats only the Bank read.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-SYNC-004-complete-referenced-item-metadata.md`, Follow-up Finding F002.
- `docs/TEST_STRATEGY.md` §12.2, browser smoke checks.
- Supplied `docs/ROADMAP.md` Phase 5, normal feature workflows refresh needed account data.

## Context

The finding reports that the account browser smoke step named “bank reload repeats only that read” compares request counts using a flawed baseline. The assertion should establish the behavior named by the step without treating unrelated earlier requests as evidence of the reload.

## Acceptance Criteria

1. Capture a request baseline immediately before the Bank reload action.
2. Assert that the reload causes the expected Bank read and does not cause other account reads.
3. Keep the assertion isolated from requests made by prior account workflow steps.
4. Record focused browser smoke verification.

## Required Tests

- Run the account browser smoke check in its controlled browser runtime and verify the Bank reload assertion.

## Constraints

- Keep the change scoped to the smoke assertion and request accounting.
- Do not alter account refresh product behavior.

## Dependencies

None.

## Definition of Done

The check reliably measures requests caused by the Bank reload itself and records its verification evidence.

## Result

SUPERSEDED - withdrawn as duplicate work. Not implemented, and must not be.

This story was created on 2026-10-03 from `STORY-SYNC-004` Follow-up Finding F002 - the same finding
`STORY-SYNC-005` was created for and completed from. Its three acceptance criteria are already
satisfied in `frontend/scripts/account-browser-smoke.mjs`:

1. "Capture a request baseline immediately before the Bank reload action" - `:174` takes
   `beforeReload = apiRequests.length` immediately before `page.click('[data-test="bank-reload"]')`,
   over a list ordered by when each request was *issued* (`:58-88`) rather than answered.
2. "Assert that the reload causes the expected Bank read and does not cause other account reads" -
   `:182` requires `reloadOther` to deep-equal exactly `['/api/account/bank']`.
3. "Keep the assertion isolated from requests made by prior account workflow steps" - a call issued
   before the click cannot enter the window, and the still-pending ones are reported separately as
   `inFlightExcluded` (`:175-179`, `:188`).

`STORY-SYNC-005` is DONE and records that evidence against maintainer commit `0fcdbb4`.

Root cause of the duplicate: `STORY-SYNC-004`'s existing disposition read
`F002: ALREADY COVERED ? STORY-SYNC-005 isolates Bank reload requests ...`, with a literal `?` where
an em dash belonged, which the separator pattern in `agent/runtime/core/follow_up_findings.py` did
not accept. The finding was therefore re-supplied as unresolved on the next planning pass. That
pattern now accepts any separator, and nine such dispositions across six story files are visible
again.

## Blockers

None. Withdrawn as duplicate work, not blocked.

## Follow-up Findings

None.
