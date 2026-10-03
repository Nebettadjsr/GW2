## Story ID

STORY-WEB-027

## Title

Remove the tracked temporary account smoke control copy

## Status

TODO

## Milestone

milestone-05

## Goal

Remove the tracked throwaway control copy of the account browser smoke script so it cannot be mistaken for maintained verification code.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-018-fresh-build-browser-smoke-checks.md`, Follow-up Finding F001.
- Supplied `docs/ROADMAP.md` Phase 5, browser smoke coverage and acceptance.

## Context

The finding identifies `frontend/scripts/.tmp-control-unrelated-call.mjs` as a tracked throwaway control copy with no npm script reference. The Phase 5 browser verification scope includes maintaining meaningful smoke checks.

## Acceptance Criteria

1. Remove the tracked temporary control copy.
2. Confirm no maintained smoke command depends on that temporary file.
3. Record the repository change and focused verification.

## Required Tests

- Verify the temporary script is absent and the relevant account smoke check remains runnable.

## Constraints

- Do not modify the maintained account smoke script except where directly required to confirm it is independent.
- Do not broaden into general script cleanup.

## Dependencies

None.

## Definition of Done

The throwaway tracked file is removed, maintained verification remains intact, and evidence is recorded.

## Result

Not started.

## Blockers

None.

## Follow-up Findings

None.
