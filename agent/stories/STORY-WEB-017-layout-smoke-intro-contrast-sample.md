## Story ID

STORY-WEB-017

## Title

Keep browser layout smoke contrast sampling valid after Profit intro removal

## Status

TODO

## Milestone

milestone-05

## Goal

Restore the browser layout smoke script's page-intro contrast sample so the removal of the Crafting Profit introduction does not silently reduce its measured contrast coverage.

## Authoritative Source Documents / Sections

- agent/stories/STORY-WEB-015-profit-purchase-and-blocking-details.md, Follow-up Findings F001: the `page intro` sample on `AREAS[0]` no longer finds an element after the Profit intro was removed.
- Supplied docs/ROADMAP.md Phase 5: frontend tests focused on rendering, interaction and state.
- docs/TEST_STRATEGY.md section 12: frontend rendering and interaction test focus.

## Context

The layout browser smoke currently samples contrast on Crafting Profit (`AREAS[0]`), whose introduction was removed under WEB-015. The finding states that this sample now silently contributes no measurement while the existing sample-count floor still passes. Preserve the intended sample by targeting an existing page that has an introduction, rather than changing product presentation or weakening the smoke check.

## Acceptance Criteria

1. The `page intro` contrast sample targets an existing rendered introduction on a page that retains one.
2. The smoke check fails clearly if that target is absent or cannot be measured; it must not silently count a missing sample as successful coverage.
3. The change preserves the existing contrast sampling behavior and does not reintroduce the removed Crafting Profit introduction.
4. Record the focused verification and any limitations in the story Result.

## Required Tests

- Run the layout browser smoke and confirm the page-intro sample is measured on its new target.
- Add or adjust a focused check proving a missing sample target is reported as a failure rather than silently omitted.

## Constraints

- Keep this limited to the identified browser smoke coverage gap; do not broaden layout or contrast policy.
- Do not alter product-facing page content to satisfy the smoke script.

## Dependencies

Completed STORY-WEB-015.

## Definition of Done

The layout smoke measures a valid page-intro contrast pair, missing targets cannot silently pass, and the focused verification is recorded.

## Result

Not started.

## Blockers

None.
