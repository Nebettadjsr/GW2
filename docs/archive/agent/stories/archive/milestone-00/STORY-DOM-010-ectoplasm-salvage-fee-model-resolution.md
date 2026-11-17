# STORY-DOM-010: Resolve Ectoplasm Salvage fee-model ambiguity (DQ-011)

## Story ID

STORY-DOM-010

## Title

Resolve Ectoplasm Salvage fee-model ambiguity (DQ-011)

## Status

DONE

## Milestone

milestone-00

## Goal

Apply the resolved `UD-002` decision to the Ectoplasm Salvage feature: `EctoView` remains the sole
authoritative implementation and continues to display Dust/Ecto figures without deducting Trading
Post selling fees, the user is shown a one-time informational notice that TP fees will still apply
when the items are actually sold, the disconnected legacy `Main.java` is deleted, and the decision
is recorded in `docs/DOMAIN_SPEC.md` as DQ-011.

## Authoritative Source Documents / Sections

- `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (Status: RESOLVED — read its `User
  Decision` and `Resolution` sections; they are the authoritative decision for this story).
- `docs/DOMAIN_SPEC.md` §25 (Trading Post Selling Fees — already notes "The separate Ectoplasm
  Salvage calculator may use its own fee model as defined in its dedicated domain section"), §45–47
  (Ectoplasm Salvage Domain / Net Cost / Cost per 1000 Luck), §51/§53 (domain-question process).
- `docs/KNOWN_PROBLEMS.md` §3.6 (the confirmed conflict this story closes).

## Context

- `EctoView.java` (`fillProfitGrid`/`fillLuckGrid`) already computes dust revenue as
  `dustSellPrice * DUST_PER_ECTO` (`DUST_PER_ECTO = 0.75`) with **no** Trading Post fee deduction —
  this already matches UD-002's resolved behavior ("must display prices/revenue without deducting
  Trading Post selling fees"). No calculation change is expected here; this story is about the
  notice, cleanup, and documentation catching up to already-correct behavior.
- `EctoView` currently has no informational notice of any kind about TP fees.
- `Main.java` (repo root `src/main/java/Main.java`) is the separate, UI-disconnected legacy
  implementation using `DUST_PER_ECTO = 1.0` and an explicit 15% fee via `applySellFees(...)`. It
  has no callers from any UI entry point (`Gw2App`) — confirmed by `docs/KNOWN_PROBLEMS.md` §3.6's
  own observed-fact wording ("UI-disconnected"). UD-002's Resolution explicitly states `Main.java`
  "is obsolete legacy code and may be deleted."
- `docs/DOMAIN_SPEC.md` §46/§47 currently describe the net-cost/cost-per-1000-luck formulas using a
  `dust_sale_price_after_fees` term, which implies a fee deduction — this wording reflects the
  `Main.java` model, not the now-authoritative `EctoView` (no-fee) model, and needs to be reconciled
  with DQ-011 so the spec does not contradict itself.
- DQ-001 through DQ-010 already exist in `docs/DOMAIN_SPEC.md` under a "## DQ-00N — Title" /
  "**Status:** DECIDED" pattern (see e.g. `## DQ-007 — Bound items`); DQ-011 should follow the same
  format.

## Acceptance Criteria

- `EctoView` shows a one-time informational notice (per view-open is acceptable; a persisted
  "don't show again" is not required) stating that displayed Ectoplasm Salvage profit/cost figures
  do not include Trading Post selling fees, and that fees will apply when the resulting items are
  actually sold.
- `EctoView`'s dust-revenue calculation itself is unchanged (still no fee deduction) — this story
  does not alter `DUST_PER_ECTO` or add a fee term to `fillProfitGrid`/`fillLuckGrid`.
- `src/main/java/Main.java` is deleted, after confirming (e.g. repository-wide search) it still has
  no callers.
- `docs/DOMAIN_SPEC.md` gains a `## DQ-011 — Ectoplasm Salvage fee model` entry (matching the
  existing DQ-00N format and `**Status:** DECIDED` marker) recording: `EctoView` is the sole
  authoritative Ectoplasm Salvage implementation; Trading Post selling fees are not deducted from
  its displayed price/revenue/profit figures; the UI must inform the user once that fees apply on
  eventual sale.
- `docs/DOMAIN_SPEC.md` §46/§47's `dust_sale_price_after_fees` wording is reconciled with DQ-011 (do
  not leave the spec internally contradictory — e.g. rename/clarify the term or note explicitly that
  no fee is currently subtracted, whichever keeps the formula description accurate to the
  implemented, authoritative `EctoView` behavior).

## Required Tests

None — this is a UI-notice addition, a dead-code removal, and a documentation change; `EctoView`'s
calculation itself is unchanged and has no existing automated test coverage to extend (it is a
live-HTTP-calling JavaFX view with no test seam, consistent with `docs/TEST_STRATEGY.md`'s current
scope). If, while implementing, a low-cost way to unit test the notice-shown-once behavior or the
unchanged calculation becomes obviously available, adding it is a reasonable bonus but not required
for this story's Definition of Done.

## Constraints

- Do not change `EctoView`'s underlying calculation (`DUST_PER_ECTO`, no-fee behavior) — that part
  of UD-002's decision is already implemented; changing it would be scope creep beyond this story's
  Goal.
- Do not reintroduce a second Ectoplasm Salvage implementation — `Main.java` is being removed, not
  refactored into a shared class (no application-service extraction here; that is Phase 3 per
  `docs/ROADMAP.md` §7).
- Keep the notice simple (a `Label`/`Alert`-style JavaFX element consistent with `EctoView`'s
  existing dark-theme styling) — no new dependencies.

## Dependencies

None.

## Definition of Done

- `EctoView` displays the one-time TP-fee notice described above.
- `src/main/java/Main.java` no longer exists.
- `docs/DOMAIN_SPEC.md` contains DQ-011 and §46/§47 no longer contradict it.
- `./mvnw test` passes (no regressions).
- `agent/PROJECT_STATE.md` and `agent/stories/BACKLOG.md` are updated per `CLAUDE.md`'s IMPLEMENTATION MODE rules (story moved to Done, materially changed project state noted).

## Result

`EctoView` now shows a persistent informational banner ("Note: figures below do not include Trading
Post selling fees. Fees will still apply when the resulting items are actually sold.") in the view's
main scroll area, built once per `show()` call (i.e. once per view-open, matching the acceptance
criteria's "per view-open is acceptable"). No change was made to `DUST_PER_ECTO`, `fillProfitGrid`,
or `fillLuckGrid` — the underlying no-fee calculation is untouched.

`src/main/java/Main.java` was deleted after confirming via repository-wide search that its only
matches were unrelated "Main table"/"Main content grid" comments in other views and the string
`Main` inside doc/planning files — the actual JavaFX entry point is `Gw2App` (`pom.xml`'s
`<main.class>`), so `Main.java` had zero real callers.

`docs/DOMAIN_SPEC.md` gained `## DQ-011 — Ectoplasm Salvage fee model` (DECIDED) directly after
DQ-010, using the existing DQ-00N format. §45 ("Relevant parameters") no longer lists "Trading Post
fees" as a parameter of the calculation and instead notes, per DQ-011, that TP fees are not deducted
and are only a cost incurred later on actual sale. §46's `dust_sale_price_after_fees` term was
renamed to `dust_sale_price` with a note that it is the raw TP sell price with no fee subtracted
(DQ-011), removing the contradiction with the now-authoritative no-fee `EctoView` behavior.

## Tests Run

`./mvnw clean test` — 16 tests run, 0 failures, 0 errors, `BUILD SUCCESS` (unchanged from baseline;
confirms `Main.java`'s removal does not break compilation of the other 76 source files). No new
automated test was added, per this story's own "Required Tests: None" guidance (`EctoView` has no
existing test seam — live-HTTP-calling JavaFX view, consistent with `docs/TEST_STRATEGY.md`'s current
scope).

## Blockers

None.
