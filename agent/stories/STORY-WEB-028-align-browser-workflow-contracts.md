## Story ID

STORY-WEB-028

## Title

Align remaining browser workflow contracts and coverage

## Status

TODO

## Milestone

milestone-05

## Goal

Resolve the remaining Phase 5 browser consistency findings recorded by STORY-WEB-024 and verify that shared page structure, compact resolution presentation, and root-sourcing presentation match their current authoritative contracts. Preserve the confirmed Crafting Profit placement of the maximum and Show all beside the results title.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-024-repair-fresh-build-smoke-contracts.md`, Follow-up Findings F001-F004.
- `docs/DOMAIN_SPEC.md` §2.1.1, current Crafting Profit display-control placement.
- `docs/TEST_STRATEGY.md` §§12.1-12.2, frontend and browser smoke verification.
- Supplied `docs/ROADMAP.md` Phase 5, browser rendering, coverage, and acceptance.

## Context

STORY-WEB-024 records four remaining concerns: Bank and Materials duplicate the shared page header structure; its F002 described the maximum displayed recipe count and Show all controls being outside the Calculation controls subgroup as a discrepancy; the compact-value prop documentation disagrees with the presentation used by both crafting workflows; and browser smoke fixtures no longer exercise `describeRootSourcing` output. The F002 placement requirement is superseded by the later confirmed Product Owner decision and the reconciled `DOMAIN_SPEC.md` §2.1.1. Confirm each remaining concern against the current implementation before changing it. Preserve established product behavior and backend-provided authoritative values.

## Acceptance Criteria

1. Reconcile Bank and Materials page headers with the shared page-header contract, preserving current navigation focus behavior and reload actions.
2. Preserve the maximum displayed recipe count and Show all beside the results table title, as specified by the current DOMAIN_SPEC §2.1.1. Verify that the smoke check asserts this placement and that the separate calculation and filter groups retain their accessible grouping. Do not move these controls into the Calculation controls panel.
3. Make the compact-value documentation and component behavior accurately describe the presentation used by Crafting Profit and Discovery; remove unreachable presentation branches only if the current implementation confirms they are unused and removal is within this scope.
4. Add browser smoke coverage that reaches and verifies the root-sourcing presentation produced by `describeRootSourcing`, using fixture data that actually renders that presentation.
5. Add or update focused component/browser checks for each changed contract and retain meaningful existing workflow assertions.
6. If any recorded concern is already resolved or superseded by a later confirmed Product Owner decision, preserve that implementation and record the confirming evidence and disposition in Result rather than reintroducing the issue.

## Required Tests

- Focused component tests for any changed page-header, Crafting Profit control, or resolution presentation behavior.
- Controlled-browser smoke checks for affected navigation/layout behavior and both crafting workflows, including a case that renders root-sourcing text.
- Review affected frontend coverage reports and unresolved quality gaps as required by `docs/TEST_STRATEGY.md`.

## Constraints

- Keep changes within the four findings and their direct tests.
- Preserve domain behavior and backend ownership of authoritative crafting results.
- Do not weaken existing browser assertions to make checks pass.
- Do not restore obsolete Ectoplasm backend calculation behavior.

## Dependencies

- STORY-WEB-024 (DONE; source of findings).

## Definition of Done

Each finding is checked against the current implementation; applicable contracts are aligned; focused component and browser checks pass; affected coverage is reviewed; and Result records verification evidence and any finding already resolved without code changes.

## Result

Not started.

## Blockers

None.
