## Story ID
STORY-PERF-002

## Title
Verify Phase 5 Crafting Profit page performance on the real user database

## Status
BLOCKED

## Milestone
milestone-05

## Goal
Verify that the integrated web Crafting Profit page meets the accepted complete-page performance budget on the real user PostgreSQL database, measuring navigation through usable rendered content across backend calculation, persistence, transport, and frontend rendering.

## Authoritative Source Documents / Sections
- `docs/ROADMAP.md` ? Phase 5 exit criteria (supplied current-phase excerpt)
- `docs/TARGET_ARCHITECTURE.md` ?33
- `docs/TEST_STRATEGY.md` ?34, Real-user Crafting Profit performance acceptance

## Context
Phase 5 requires performance verification for the complete browser page, including backend, transport, and rendering, against the real user database. The accepted target is at most 7 seconds. Miniature or synthetic data, service-only timings, and first-row timing are not acceptance evidence. The final frontend integration must be in place before this measurement is meaningful.

## Acceptance Criteria
- On the real user PostgreSQL database, measure navigation/request initiation through completed calculation and usable table/control rendering, including UI-thread completion and rendering.
- Record reproducible baseline/post-change context as applicable: revision, hardware/JVM/database environment, selected scope/settings, relevant data counts, cache state, individual elapsed times, and maximum; include default All scope, first opening after startup, and repeat openings.
- Attribute major pipeline stages without excluding them from the end-to-end total; determine whether the maximum complete-page time meets the 7-second target.
- Verify the completed table answers a real displayed-value read before treating it as interactive, and record limitations or unavailable environment access honestly without claiming compliance from substitute data.
- Record measurements and any outstanding or received user acceptance in this story's Result without exposing credentials or private account contents.

## Required Tests
- Explicit real-user-database browser performance measurement following `docs/TEST_STRATEGY.md` ?34. This is a local acceptance check and is not established by CI or mocked browser smoke tests.

## Constraints
- Do not use or alter disposable fixtures as a substitute for real-database acceptance evidence.
- Do not disclose credentials or private account contents.
- Do not reduce or replace real data to make the benchmark faster.

## Dependencies
- STORY-WEB-015
- STORY-SYNC-004
- STORY-DOM-023
- STORY-DOM-024
- STORY-WEB-016

## Definition of Done
- The integrated page has been measured using the required real-database procedure, and evidence, result, limitations, and user-acceptance status are recorded here.

## Result
Not started.

## Blockers
Blocked until the listed Phase 5 frontend and shared-calculation integration stories are complete, so measurement covers the final integrated Profit page.