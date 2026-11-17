# UD-008 - Phase 5 frontend framework and language

## Status

RESOLVED

## Decision Needed

Choose the frontend framework and programming language for Phase 5. `docs/TARGET_ARCHITECTURE.md` §4.1/§30 lists React, Vue, or another modern web framework as candidates; TypeScript is possible but not mandatory. Record an explicit choice for both framework and language.

## Why This Is Needed

The supplied `docs/ROADMAP.md` Phase 5 section requires an explicit framework/language decision. `docs/TARGET_ARCHITECTURE.md` §30 prohibits implementation agents from selecting these TBD technologies merely from familiarity. Planning must obtain the human decision instead of guessing.

## Context

- Current scope is Phase 5, milestone-05: frontend migration against the backend HTTP API.
- `docs/TARGET_ARCHITECTURE.md` §4.1/§12 requires presentation and interaction only; authoritative calculations remain in the backend.
- Phase 5 includes crafting profit/discovery, resolution trees and special states, bank/materials, Ectoplasm Salvage, and API-triggered synchronization status.
- JavaFX and the web UI must coexist during this phase using the same backend.
- Existing decisions UD-001 through UD-007 do not resolve the frontend framework/language choice.

## Blocks

Framework-dependent Phase 5 implementation planning and the explicit frontend technology decision exit criterion. No implementation story is created pending this decision.

## External Input Possibly Required

Product Owner preference or constraints on the frontend framework and language. No external input is otherwise required to record the choice.

## User Decision

No Product Owner answer was ever recorded here, and none is recorded now. This
decision was raised before the Architect role existed, when the planner's only
way to escalate an architectural question was a User Decision.

## Resolution

Superseded by `agent/architect-requests/AR-001-frontend-framework-and-language.md`,
which carries the full question and the context above. RESOLVED here means
"closed as rerouted", not "answered": the architect
(`agent/ARCHITECT_INSTRUCTIONS.md`) now evaluates the question first, against
`docs/TARGET_ARCHITECTURE.md` §4.1/§12/§30 and the Phase 5 roadmap section. If
the choice genuinely remains open afterwards, the architect raises a new OPEN
`UD-*` here with real alternatives and trade-offs, and AR-001 records it as its
blocking decision. Nothing about the Phase 5 exit criterion changes; only which
role poses the question first.


Current outcome: AR-001 was resolved with Vue 3 and TypeScript, recorded in ADR-001 and `docs/TARGET_ARCHITECTURE.md` section 4.3. This user-decision file records the earlier routing history only.
