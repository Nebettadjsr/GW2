# UD-007 - Phase 4 long-running HTTP sync approach

## Status

RESOLVED

## Decision Needed

Choose how Phase 4 HTTP sync/refresh triggers handle long-running operations: synchronous requests where acceptable, backend tasks with a status endpoint for longer operations, or an explicitly scoped combination. If choosing a combination, identify which operations use each approach.

## Why This Is Needed

The supplied Phase 4 high-level stories require an explicit long-running-operation decision. At request time, `docs/TARGET_ARCHITECTURE.md` section 23 (now section 9) marked the implementation approach TBD and offers these alternatives. They define materially different HTTP completion and status behavior; the planner cannot choose on the user's behalf.

## Context

- Supplied `docs/ROADMAP.md` section 8, Phase 4 sync/refresh endpoint exit criterion and long-running-operation high-level story.
- `docs/TARGET_ARCHITECTURE.md` section 9 sketches account sync, global sync, and price refresh endpoints; the names remain examples rather than a fixed contract.
- Sections 22 and 23 require backend-owned orchestration and the simplest practical initial approach; a full queue system or distributed job platform is not required.
- Existing UD-001 through UD-005 do not settle this choice. No operation duration or timeout behavior was measured during this planning pass.
- Once answered, record the intended approach in its authoritative owner, `docs/TARGET_ARCHITECTURE.md` section 9, before planning the corresponding milestone-04 implementation stories.

## Blocks

Planning Phase 4 sync/refresh HTTP completion and status behavior and satisfying its explicit approach-decision requirement.

## External Input Possibly Required

Product Owner choice of request completion versus background-task status behavior and, if applicable, the operation split. Routine implementation details remain for the implementing stories.

## User Decision

RESOLVED

## Resolution

## User Decision

Use a mixed synchronous/asynchronous approach based on the measured runtime and operational characteristics of each endpoint.

Prefer synchronous HTTP requests where the operation completes quickly and reliably. This is the preferred approach because it keeps the API and frontend interaction simple.

Operations that call the Guild Wars 2 API for synchronization should use asynchronous backend tasks with status reporting. These operations commonly require batching because of ArenaNet API rate limits and may take long enough that keeping one HTTP request open is unnecessarily fragile.

For other endpoints, do not assign synchronous or asynchronous behavior speculatively. Implement the endpoint far enough to obtain representative real-world runtime measurements, then choose:

* synchronous when execution is consistently short;
* asynchronous backend task with status endpoint when execution can take materially longer or is exposed to interruption/connection-timeout risk.

The exact per-endpoint split therefore remains an implementation-time decision based on measured behavior rather than an assumed duration.

Future backend-owned scheduled refreshes are allowed as a separate design concern. In particular, Trading Post price refresh and account synchronization may later run automatically on a backend schedule instead of relying exclusively on user-triggered frontend actions. Such scheduling must coordinate safely with crafting calculations so calculations do not observe an inconsistent partially-updated state. The scheduling mechanism and consistency strategy are not decided by this UD and should be designed when that work is reached.

The future web frontend is not required to reproduce the JavaFX UI interaction model one-to-one. It should preserve the application's functionality and authoritative backend behavior while using appropriate modern web interaction patterns, including asynchronous status/progress presentation where applicable.

Current implementation: Guild Wars 2 sync uses asynchronous tasks; measured short endpoints remain synchronous. The global metadata scheduler and on-demand ten-minute Trading Post cache are documented in `docs/TARGET_ARCHITECTURE.md` section 9. No distributed job system is included.
