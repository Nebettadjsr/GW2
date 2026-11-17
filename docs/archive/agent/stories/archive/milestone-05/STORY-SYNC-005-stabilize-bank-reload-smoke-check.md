## Story ID

STORY-SYNC-005

## Title

Stabilize the Bank reload browser smoke check

## Status

DONE

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

DONE — satisfied by the existing maintainer implementation in commit `0fcdbb4`
("Update ecto salvage in other docs, Stories, Roadmap ect.", 2026-09-29), which changed
`frontend/scripts/account-browser-smoke.mjs` only. Nothing was reimplemented for this story; the
work below was verified against the current working tree by inspection on 2026-10-03.

How the shipped change satisfies each acceptance criterion:

1. The reload window is now delimited by when a call was **issued**, not when it was answered. A
   `page.on('request')` handler records every `/api/` request into `apiRequests` in issue order with
   an `answered` flag that the `response` handler sets (`account-browser-smoke.mjs:58-88`). The
   window is `apiRequests.slice(beforeReload)` with `beforeReload` captured before
   `page.click('[data-test="bank-reload"]')` (`:162-179`). A still-pending initial
   `/api/crafting/profit` was issued before the click, so it cannot enter the window, while anything
   the reload itself causes is necessarily issued after the click and therefore cannot escape it.
2. The Bank-only assertion is unchanged in strength: `reloadOther` must deep-equal exactly
   `['/api/account/bank']` (`:182`). Only paths matching the pre-existing narrow
   `ICON_ROUTE` pattern (`:32`, `/api/items/<id>/icon/<sha256>.<png|jpg>`) are separated out, and
   they are counted and reported rather than ignored. No application API traffic is broadly excluded.
3. The failure message names the calls the reload actually issued, the expected list, the icon-route
   image count, and the calls excluded as already in flight (`:183-190`); the success step records
   the same in-flight exclusion detail (`:191-196`).

Constraints held: the change is confined to the browser smoke check, and no application behavior or
account data was touched.

Verification limitation: the targeted check was **not** re-run for this reconciliation.
`npm run smoke:account` needs a real desktop Chrome, the backend and the dev server against the
real user database (`docs/TEST_STRATEGY.md` §12.2), and the CI gate cannot run it. The determinism
claim therefore rests on the issue-ordered window logic above, not on repeated local runs. The
story's "run the check repeatedly" step remains available to the maintainer if stronger evidence is
wanted; it is not a precondition for the code being correct.

## Blockers

None.

## Follow-up Findings

None.
