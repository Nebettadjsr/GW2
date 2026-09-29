## Story ID

STORY-SYNC-005

## Title

Stabilize the Bank reload browser smoke check

## Status

UNFINISHED

## Milestone

milestone-05

## Goal

Make the account browser smoke check's Bank reload assertion deterministic when a Crafting Profit request from the initial page is still in flight.

## Authoritative Source Documents / Sections

- agent/stories/STORY-SYNC-004-complete-referenced-item-metadata.md, Follow-up Findings F002.
- agent/stories/STORY-SYNC-004-complete-referenced-item-metadata.md, Acceptance Criteria 6 and Required Tests.
- docs/TEST_STRATEGY.md, frontend/browser smoke checks.

## Context

The smoke check navigates from the default Crafting Profit page to Bank and compares requests during the Bank reload window. An outstanding initial `/api/crafting/profit` request can enter that window, causing a timing-dependent failure even though Bank reload itself issues only its bank read and icon requests.

## Acceptance Criteria

1. The browser smoke check reliably distinguishes requests caused by the Bank reload from any still-pending request caused by the initial Crafting Profit page.
2. Preserve verification that Bank reload repeats only the Bank data read, allowing the expected icon-route requests.
3. The check fails with a useful diagnostic if Bank reload causes another unrelated application API request.

## Required Tests

- Run the targeted account browser smoke check repeatedly against the supported local application setup and confirm the Bank reload assertion is stable.
- Verify the assertion still detects an unrelated API request caused by the Bank reload.

## Constraints

- Limit changes to the browser smoke check and directly necessary synchronization/assertion logic.
- Do not weaken the assertion by broadly ignoring application API traffic.
- Do not change application behavior or account data.

## Dependencies

- STORY-SYNC-004 (DONE; source of the recorded finding).

## Definition of Done

The targeted browser smoke check distinguishes the initial-page request from Bank reload traffic, preserves the Bank-only assertion, and its relevant checks pass.

## Result

Not started.

## Blockers

None.
