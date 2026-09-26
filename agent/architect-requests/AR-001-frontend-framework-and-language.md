# AR-001 — Phase 5 frontend framework and language

## Status

RESOLVED

## Architecture Question

Which frontend framework and which programming language should the Phase 5 web
frontend use?

`docs/TARGET_ARCHITECTURE.md` §4.1/§30 lists React, Vue, or another modern web
framework as candidates and records the choice as TBD; TypeScript is possible
but not mandatory. An explicit choice is required for both the framework and
the language.

Evaluate the question architecturally first. Determine whether the project's
existing authoritative constraints already force one answer, or whether
materially different viable alternatives remain. Escalate to the Product Owner
only if the choice genuinely remains open after that evaluation.

## Context and Constraints

- Current scope is Phase 5, milestone-05: frontend migration against the
  backend HTTP API.
- `docs/TARGET_ARCHITECTURE.md` §4.1/§12 require presentation and interaction
  only; authoritative calculations remain in the backend, and the frontend does
  not reproduce domain calculations.
- The frontend must never call the Guild Wars 2 API directly and must never
  receive API keys or database credentials.
- Phase 5 covers crafting profit/discovery, resolution trees and special
  states, bank/materials, Ectoplasm Salvage, and API-triggered synchronization
  status.
- JavaFX and the web UI must coexist during this phase against the same
  backend, so the choice must suit incremental migration rather than a rewrite.
- `docs/TARGET_ARCHITECTURE.md` §30 prohibits selecting a TBD technology merely
  from familiarity.
- The existing backend boundary is the Spring Boot HTTP API delivered by
  `STORY-API-001`/`STORY-API-002` (see `docs/CURRENT_ARCHITECTURE.md`).
- Existing decisions UD-001 through UD-007 do not resolve this choice.
- This request supersedes `agent/user-decisions/UD-008-frontend-framework-and-language.md`,
  which recorded the same question before the architect role existed. No
  Product Owner answer was ever recorded there; its context is preserved above.

## Authoritative References

- `docs/TARGET_ARCHITECTURE.md` §4.1 (frontend container), §12 (frontend
  responsibilities), §30 (decided vs TBD technology)
- `docs/ROADMAP.md` Phase 5 (explicit frontend technology decision exit
  criterion)
- `docs/CURRENT_ARCHITECTURE.md` (existing backend HTTP boundary)
- `agent/user-decisions/UD-008-frontend-framework-and-language.md` (superseded
  origin of this question)

## Blocked Work

Framework-dependent Phase 5 implementation planning, and the Phase 5 exit
criterion requiring an explicit frontend technology decision. No implementation
story can be created for the web frontend until this is answered.

## Blocking User Decision

None

## Architect Decision

Class C — architecturally significant technical decision. Select **Vue 3 with
TypeScript and strict type checking**, with single-file components using the
Composition API and `<script setup lang="ts">` as the default convention.

The existing constraints do not force one framework. This is a technical selection
within the approved browser/frontend and backend HTTP boundaries, requiring no
Product Owner decision. UD-008 was closed as rerouted, not as a technology answer.

By architectural judgment, Vue's component conventions are the simplest overall
fit for this single-maintainer presentation client: tables, recursive tree views,
settings and asynchronous status can be built as bounded components, while typed
API shapes help check missing values and special states across agent changes.
TypeScript adds build/type maintenance but is preferable to JavaScript for those
contracts. Neither types nor the framework implement or validate domain rules.

React with TypeScript is the runner-up: it can satisfy the same requirements and
run as a browser-only client, but its additional application-convention choices
bring no required benefit here. Vue is preferred on simplicity, not familiarity
or an unmeasured speed claim. Svelte and Angular were also considered; comparative
evidence, maintenance costs and reversibility are recorded in
`docs/architecture/decisions/ADR-001-frontend-framework-and-language.md`.

The authoritative selection is in `docs/TARGET_ARCHITECTURE.md` section 4.1.
The seven-second complete-page requirement remains subject to real browser
measurement; no framework is presumed to meet it without evidence.

## Resolution

Updated `docs/TARGET_ARCHITECTURE.md` section 4.1 with the selection and boundaries,
replaced the diagram's stale TBD with a section reference, and moved the section 30
frontend entries from TBD to a reference to their owner. Added accepted ADR-001
with alternatives, official technical sources, rationale and consequences.

Planner follow-up: consume this decision for Phase 5 implementation planning and
reconcile the roadmap's stale statement that frontend framework/language remain
TBD. Plan the required frontend verification under `TEST_STRATEGY.md`, including
the complete browser-load requirement; this decision does not close Phase 5.
Build tooling and optional frontend libraries are not selected by this request.

Related scope observations for planner/later requests only: current architecture
section 5.5 says resolution trees are absent from the existing profit response and
need a future detail endpoint, so selecting a UI framework does not resolve that
contract gap. The target's backend-container section 4.2 still says framework TBD
despite section 30 and UD-006 deciding Spring Boot. That separate documentation
inconsistency is reported here and was not edited. JavaFX currently reuses backend
application services in-process; coexistence does not imply a JavaFX HTTP rewrite.

No User Decision was created or changed. No implementation or planner-owned state
was modified by this architecture run.