## Story ID

STORY-WEB-024

## Title

Repair browser smoke assertions against current workflow contracts

## Status

TODO

## Milestone

milestone-05

## Goal

Restore meaningful layout, Crafting Profit, and Discovery smoke coverage where existing browser assertions or readiness selectors contradict the current frontend workflows.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-018-fresh-build-browser-smoke-checks.md`, Follow-up Findings F002-F004.
- `docs/TEST_STRATEGY.md` §12.2, browser smoke checks.
- Supplied `docs/ROADMAP.md` Phase 5, browser coverage and acceptance.

## Context

STORY-WEB-018 records three smoke scripts that proceed past bundle validation but fail against selectors or expectations inconsistent with current workflow content: layout waits for the removed `sync-controls` hook, Profit expects a phrase contradicted by its component test, and Discovery reads legends from non-legend control containers. These failures prevent the scripts from establishing their intended workflow evidence.

## Acceptance Criteria

1. Align the layout smoke synchronization readiness selector with a current stable workflow hook and retain its later layout assertions.
2. Reconcile the Profit smoke detail assertion with the current selected-result detail contract and component expectations.
3. Reconcile the Discovery control-group assertion with its current rendered control structure while preserving useful workflow checks.
4. Record focused verification for all affected smoke scripts and any remaining limitations.

## Required Tests

- Run the layout, Profit, and Discovery browser smoke checks in a controlled browser runtime and record their results.

## Constraints

- Keep changes within smoke assertions and directly necessary stable hooks.
- Do not weaken workflow or API assertions to make a script pass.
- Do not change product behavior merely to satisfy stale smoke expectations.

## Dependencies

- STORY-WEB-018 (DONE; published as commit 2f9b2d5 and verified by that commit's CI run).

## Definition of Done

The three affected smoke checks use current workflow contracts, retain their intended assertions, and record verification evidence.

## Result

Not started.

## Blockers

None.

## Follow-up Findings

None.
