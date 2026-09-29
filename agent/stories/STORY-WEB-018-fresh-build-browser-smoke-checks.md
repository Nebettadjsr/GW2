## Story ID

STORY-WEB-018

## Title

Reject stale frontend bundles in browser smoke checks

## Status

TODO

## Milestone

milestone-05

## Goal

Ensure browser smoke scripts exercise a frontend bundle built from the current source instead of silently using stale output.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-DOM-024-ectoplasm-fee-policy-alignment.md` Follow-up Findings F002: smoke scripts check only that `frontend/dist/index.html` exists before launching Chrome.

## Context

The finding identifies the Ectoplasm browser smoke script and other `smoke:*` scripts sharing the same existence-only guard. A stale bundle can therefore produce misleading browser evidence after additive source changes.

## Acceptance Criteria

1. Each affected browser smoke script verifies that its frontend bundle reflects the current source before launching Chrome, or performs the required build itself.
2. A missing or stale bundle fails with a clear actionable message; an up-to-date bundle proceeds.
3. Apply the guard consistently to all smoke scripts that share the identified existence-only pattern without weakening their browser/API assertions.
4. Record the affected scripts and verification evidence in Result.

## Required Tests

- Targeted checks demonstrating that affected smoke scripts reject missing and stale output and accept current output.
- Run the affected browser smoke scripts against a current build and record results.

## Constraints

- Keep the work limited to validating/building the frontend bundle for browser smoke checks.
- Preserve existing smoke assertions and avoid unrelated script cleanup.

## Dependencies

None.

## Definition of Done

Affected browser smoke scripts reliably detect stale output, targeted checks and browser smoke evidence are recorded, and the story revision passes the CI gate.

## Result

Not started.

## Blockers

None.
