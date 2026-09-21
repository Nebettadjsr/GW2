## Story ID

STORY-APP-003

## Title

Route Ectoplasm Salvage through an application service

## Status

TODO

## Milestone

milestone-03

## Goal

Introduce one Ectoplasm Salvage application use case that coordinates price acquisition and the existing domain calculation, and migrate EctoView to consume its results.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md section 7, Phase 3 objective, Ectoplasm convergence exit criterion and application-service migration stories.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md section 5.3 (sole Ectoplasm Salvage flow).
- docs/KNOWN_PROBLEMS.md sections 3.6 (resolved duplication and implemented fee behavior) and 4.4 (remaining application-boundary gap).

## Context

The documented historical duplicate Main.java has already been deleted. EctoView still acquires prices directly and calls the plain EctoSalvageCalculator without an application boundary. The remaining work is orchestration extraction, preserving the already-established calculation.

## Acceptance Criteria

1. A named application service coordinates price acquisition and invokes the single Ectoplasm domain calculator, returning results suitable for presentation without JavaFX dependencies.
2. EctoView calls that service for the use case instead of directly acquiring GW2 prices or invoking the domain calculator. Grid rendering and presentation formatting remain in the view.
3. Preserve the existing calculation across all four Ecto-buy/Dust-sell combinations: deduct the 15% Trading Post fee exactly once from recovered Dust sale proceeds, leave Ecto acquisition cost and expected-yield assumptions unchanged, and preserve profit and cost-per-1000-Luck results.
4. Reuse the existing calculator as the single domain calculation, placing it where the application layer can access it without depending on presentation classes. Do not restore Main.java or introduce a competing formula.
5. Preserve raw and net-of-fee price presentation, fee-inclusive wording, and existing refresh and failure behavior. Infrastructure price acquisition is replaceable with a fake for application tests.
6. Update the affected flow in docs/CURRENT_ARCHITECTURE.md and the remaining Ecto-boundary status in docs/KNOWN_PROBLEMS.md section 4.4, without marking unrelated synchronization work complete. Record verification evidence and limitations in Result.

## Required Tests

- Application orchestration tests using fake price acquisition: verify quote handoff, all four combinations, returned calculation results and failure propagation without live HTTP or a database.
- Run the existing EctoSalvageCalculator regression tests to protect fee and yield behavior.
- Run the existing Ecto fee-notice real-view regression and add a targeted deterministic view check using controlled service results to verify displayed profit/Luck values and refresh delegation. Record any unavailable prerequisites or verification limitations.

## Constraints

- Behavior-preserving Phase 3 extraction only; no new fee, yield or pricing rules.
- Keep calculation in the domain and infrastructure coordination in the application service.
- Limit changes to the Ecto use case and necessary seams; no unrelated synchronization or architecture implementation.

## Dependencies

None.

## Definition of Done

EctoView consumes the named application use case, one domain calculation remains authoritative, acceptance criteria have verification evidence, and affected architecture/problem documentation matches the resulting boundary.

## Result

Not started.

## Blockers

None.
