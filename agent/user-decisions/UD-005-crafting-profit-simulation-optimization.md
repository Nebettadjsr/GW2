# UD-005 — Crafting Profit simulation optimization approach

## Status

RESOLVED

## Decision Needed

Choose the approach for continuing STORY-PERF-001's reported material simulation-design change: investigate replacing PlanState's full inventory copies with isolated copy-on-write/layered state; investigate replacing RecipeSimulator's sequential batch loop only if equivalence can be established; or specify another approach. Authorization to investigate an approach does not establish its correctness or permit changing domain behavior.

## Why This Is Needed

STORY-PERF-001's Result explicitly pauses for this architectural choice after bounded optimizations. docs/TARGET_ARCHITECTURE.md §17 requires a User Decision for meaningful architecture/product trade-offs. The recorded alternatives have different state-isolation, algorithm-equivalence and maintainability risks; no existing user decision settles this choice.

## Context

- agent/stories/STORY-PERF-001-crafting-profit-page-load-budget.md, Result: recorded real-database backend measurements after optimization are 22434 ms and 21987 ms, not complete-page acceptance measurements. The story attributes the remaining cost to repeated state copying and recursive simulation.
- Its Result describes copy-on-write/layered state as addressing the measured copy overhead, and batch-count binary search as requiring an unproven monotonicity/equivalence assumption. Neither proposed change is reported implemented.
- The story already references agent/user-interventions/UI-001-STORY-PERF-001.md. This decision formalizes the same question for planner handling; it does not initiate a separate implementation effort. The intervention file was not inspected during this scope-limited pass.
- The supplied Phase 3 exit criteria and docs/TARGET_ARCHITECTURE.md §17 retain the seven-second complete, interactive page requirement on the current real user database, followed by explicit dated user acceptance tied to the tested revision/results. This decision is not that acceptance and does not relax the requirement.
- Existing UD-003 preserves the intentional 250-craft limit; UD-004's coordinated crafting semantics remain binding. Routine behavior-preserving implementation details do not require separate decisions.

## Blocks

- Resuming STORY-PERF-001's reported material optimization choice and completing its Phase 3 performance gate.
- No dependency is added to STORY-APP-007; its existing setup extraction can proceed independently.

## External Input Possibly Required

Product Owner choice or authorization of the architectural approach described in the story's Result. Subsequent implementation must compare applicable correctness, freshness, maintainability and resource trade-offs and establish equivalence with targeted evidence; the planner has not verified implementation claims or run tests.

## User Decision

RESOLVED

## Resolution

Authorize investigation and implementation of a behavior-preserving `PlanState` representation that avoids repeated full inventory/bound-inventory copies. Copy-on-write/layered state is acceptable; equivalent approaches such as checkpoint/rollback or undo-log state may also be evaluated.

Do not replace `RecipeSimulator`'s sequential batch calculation with binary search unless behavioral equivalence can first be demonstrated for all relevant calculation/settings cases. Investigation is permitted; changed profit results are not.

The implementation team is also authorized to investigate broader performance architectures, including precomputed recipe/dependency structures, safe intermediate-result caching, versioned/materialized results, dependency-based invalidation, incremental recomputation and exact reuse of work between successive simulations.

Any caching or precomputation must explicitly preserve freshness and correctness for all inputs that can affect the calculation.

The existing ≤7 second real complete-page performance requirement remains unchanged.