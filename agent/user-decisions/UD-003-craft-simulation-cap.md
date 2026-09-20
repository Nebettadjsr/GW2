# UD-003 — Craft simulation cap policy

## Status

RESOLVED

## Decision Needed

Should the 250-craft simulation cap remain as an intentional performance guard documented in DOMAIN_SPEC.md, or should results expose a user-visible capped indicator? If choosing the indicator, confirm whether the existing limit of 250 should remain.

## Why This Is Needed

The supplied Phase 1 roadmap explicitly requires deciding whether the cap is intentional and documenting it, or exposing a capped indicator. Choosing the intended domain behavior requires the project owner's answer; the existing documentation does not establish that choice.

## Context

- Supplied Phase 1 roadmap, high-level item concerning the 250-craft simulation cap.
- docs/KNOWN_PROBLEMS.md §7.6 records that RecipeSimulator.simulatePhase(...) stops at a craft count of 250. It identifies the risk that truncated total profit may be mistaken for a true maximum and recommends documenting the intentional guard or exposing a capped indicator.
- No implementation inspection or new test execution was performed during this planning pass.

## Blocks

- Planning the Phase 1 simulation-cap resolution work.
- STORY-DOM-013 remains independently executable and does not depend on this decision.

## External Input Possibly Required

Project owner preference for the intended simulation boundary and presentation of capped results.

## User Decision

Keep the existing simulation cap of 250 crafts.

The cap is intentional. Stackable inventory and bank items are limited to stacks of 250, so the planner does not need to simulate more than 250 crafts of a recipe even when the available materials would theoretically allow a larger number.

No additional user-visible "capped" indicator is required.

## Resolution

RESOLVED.

The maximum simulated craft count remains 250 and should be documented as intentional domain behavior in `docs/DOMAIN_SPEC.md`.

When more than 250 crafts would theoretically be possible, the planner evaluates at most 250 crafts.