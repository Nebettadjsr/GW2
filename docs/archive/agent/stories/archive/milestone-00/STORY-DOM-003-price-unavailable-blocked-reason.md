## Story ID

STORY-DOM-003

## Title

Expose PRICE_UNAVAILABLE instead of silently filtering unresolvable rows

## Status

DONE

## Milestone

milestone-00

## Goal

Add the missing `craft.BlockedReason` value(s) `DOMAIN_SPEC.md` §42 expects (at minimum `PRICE_UNAVAILABLE`), have the resolver set it when a required purchase has no usable Trading Post quote, and stop treating that case as an invisible/zero-cost outcome.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §21 "Missing Trading Post Price", §42 "Blocked Reasons".
- `docs/KNOWN_PROBLEMS.md` §3.5 — the confirmed conflict, including that `BlockedReason.NO_TP_PRICE` is already declared but never set anywhere.
- `docs/TEST_STRATEGY.md` §6.11 (TP price semantics), §24 (priority list).
- `src/main/java/craft/BlockedReason.java`, `src/main/java/craft/CraftingResolver.java`.
- `src/main/java/CraftingProfitController.java`, method `hasZeroPricedBuy(...)` — the current row-filtering behavior this story's scope decision (see Constraints) concerns.

## Context

Today, a required purchase with no usable TP quote causes `CraftingProfitController.hasZeroPricedBuy(...)` to remove the row entirely from the Crafting Profit table. This conflates "not profitable" with "cannot be evaluated," which `DOMAIN_SPEC.md` §41 requires to stay distinct. The domain layer itself has no explicit state for this today.

## Acceptance Criteria

- `craft.BlockedReason` gains a `PRICE_UNAVAILABLE` value (or the existing unused `NO_TP_PRICE` is repurposed and actually set — pick one, do not carry both as duplicates).
- When a resolved requirement's purchase price is missing or `<= 0`, the domain layer marks it with this reason instead of silently treating it as zero-cost or leaving the caller unable to distinguish it from a genuinely fully-resolved requirement.
- A new test demonstrates: given a required purchase with no TP quote, the resolved need carries the price-unavailable reason rather than silently succeeding.

## Required Tests

- `./mvnw test` — the new test plus the full existing suite (including `craft.CraftingResolverCraftVsBuyTest`) must pass.

## Constraints

- Treat the domain-layer fix (the `BlockedReason` value and where `CraftingResolver` sets it) as the core, required scope of this story.
- Only change `CraftingProfitController.hasZeroPricedBuy(...)`'s row-filtering behavior if it can be done as a small, clearly-scoped change directly enabled by the domain fix. If changing what the UI displays turns out to be a larger decision (e.g. how to visually represent a blocked row), stop and record it as a follow-up recommendation in `agent/runtime/artifacts/CLAUDE_RESULT.md` instead of expanding this story's scope.
- Do not implement `RECIPE_NOT_ALLOWED` or `INSUFFICIENT_BUDGET` in this story unless doing so is trivial alongside the above — otherwise defer them explicitly as follow-ups.
- Follow `docs/CODING_GUIDELINES.md`.

## Definition of Done

- [x] `PRICE_UNAVAILABLE` (or repurposed `NO_TP_PRICE`) is added/used and set by `CraftingResolver` where a purchase price is missing/invalid.
- [x] New test passes; full existing suite still passes.
- [x] Any deferred sub-scope (`RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`, UI-level filtering change) is explicitly recorded as a follow-up recommendation, not silently dropped.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Repurposed the previously-unused `BlockedReason.NO_TP_PRICE` as `BlockedReason.PRICE_UNAVAILABLE`
(matching `DOMAIN_SPEC.md` §42's naming exactly, no duplicate value carried). `CraftingResolver`
now sets it in `resolveNeed(...)` whenever a required direct purchase is attempted
(`allowBuying && allowDirectBuy`) but `resolveDirectBuyUnit(...)` returns `<= 0` (missing or
non-positive quote), instead of falling through to the misleading `NO_RECIPE` reason. Also fixed
`tryCraft(...)`'s "not all children satisfied" branch, which previously always overwrote a
child's real `BlockedReason` (e.g. `PRICE_UNAVAILABLE` surfacing from a nested ingredient) with a
hardcoded `NO_RECIPE`, discarding it; it now propagates the first unsatisfied child's own reason
when one is set. See `agent/runtime/artifacts/CLAUDE_RESULT.md` for full detail.

## Blockers

None.
