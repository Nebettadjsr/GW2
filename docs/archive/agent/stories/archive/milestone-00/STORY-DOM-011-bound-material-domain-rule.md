# STORY-DOM-011: Implement account-bound/soulbound material domain rule (domain + repo layers)

## Story ID

STORY-DOM-011

## Title

Implement account-bound/soulbound material domain rule using the resolved selected-character concept (domain + repo layers)

## Status

DONE

## Milestone

milestone-00

## Goal

Implement `docs/DOMAIN_SPEC.md` §11.1 / DQ-007's bound-material rule — soulbound materials usable
only by the character they are bound to, and no normal Trading Post opportunity cost charged for
either account-bound or soulbound owned quantities — using the selected-character design resolved
in `UD-001`. This story covers the repository and domain (`craft.*`) layers only, behind a new,
additively-introduced code path; it deliberately does **not** change what today's production
controllers call, so existing behavior is unaffected until `STORY-DOM-012` wires the new path in.

## Authoritative Source Documents / Sections

- `agent/user-decisions/UD-001-selected-character-concept.md` (Status: RESOLVED — its `User
  Decision`/`Resolution` sections are the authoritative design for how "selected character" is
  represented and threaded; read them in full before starting).
- `docs/DOMAIN_SPEC.md` §11.1 (Bound Materials) and `## DQ-007 — Bound items` (already `DECIDED`;
  the rule itself is not in question, only its implementation).
- `docs/KNOWN_PROBLEMS.md` §3.4 (the confirmed conflict this story addresses).
- `docs/TEST_STRATEGY.md` §24 (bound materials is priority 8 in the initial domain-test priority
  order) and its Layer 1 (domain unit test) guidance.

## Context

- `repo.InventoryRepository.loadOwnedInventory()`/`loadOwnedInventory(Connection)` currently sum
  `item_id`/`count` from `account_materials`, `account_bank`, and `character_items` into a flat
  `Map<Integer, Integer>`, with no binding awareness (`docs/KNOWN_PROBLEMS.md` §3.4's observed fact).
  All three tables carry a `binding` column (`TEXT`); `account_bank` and `character_items` also
  carry `bound_to` (`TEXT`, holding a character name) — see `src/PostgreSQL Query to create DB`.
  `account_materials` has no `bound_to` column (material storage cannot hold soulbound items).
- `craft.PlanState` is constructed from that same flat `Map<Integer, Integer>` (see
  `CraftTestFixtures.state(...)`) and has no per-unit binding metadata.
- `craft.CraftingResolver.resolveNeed(...)` (lines ~49–58) consumes owned inventory via
  `state.consumeInventory(itemId, remaining)` and then charges opportunity cost as
  `usedFromInventory * resolveDirectSellUnit(itemId, ctx.tp, ctx.settings)` — i.e. it looks up a TP
  sell price **by item ID alone**. Because a soulbound stack of an item can share the same item ID
  as an ordinarily-tradable version of that item (the TP price is a property of the item, not of a
  specific bound stack), simply excluding unusable soulbound quantities at the repository layer is
  **not sufficient** to satisfy §11.1's "no normal Trading Post opportunity cost" requirement for the
  usable portion — the domain layer needs enough information to also avoid charging TP opportunity
  cost for account-bound/soulbound-and-usable quantities specifically. Decide and document (in this
  story's Result section) how you represent that distinction; a reasonable approach is to have the
  repository return separate sellable-vs-bound owned quantities per item ID (e.g. two maps, or a
  small per-item value object) rather than a single count, and have `PlanState`/`CraftingResolver`
  consume bound quantity first (zero opportunity cost) before falling back to ordinary sellable
  quantity. Do not treat this as license to redesign unrelated parts of `PlanState`/`CraftingResolver`
  beyond what's needed for this rule.
- `craft.CraftingResolverBoundMaterialCharacterizationTest` (`STORY-DOM-007`, already DONE) documents
  today's non-compliant behavior (a soulbound-to-another-character quantity is currently consumed as
  ordinary usable inventory). This story's new intended-behavior test supersedes it as the "does
  §3.4 have an intended-behavior test" answer for `docs/ROADMAP.md` Phase 0 exit criterion 3 — decide
  whether to replace, retire, or keep the characterization test alongside the new one (a short note
  in Result is enough; do not spend effort preserving it if the new test makes it redundant).
- Per `UD-001`'s Resolution: "The domain layer must not choose a character itself" — the selected
  character must be an explicit input to the relevant repository/domain call(s), never inferred or
  defaulted inside `craft.*`.

## Acceptance Criteria

- A new repository method (or overload) exists that, given a selected character identifier (GW2
  character name, per `UD-001`'s Resolution — "GW2 character name is an acceptable identifier"),
  returns owned inventory quantities such that:
  - An `account_materials`/`account_bank` row with `binding` indicating account-bound is usable by
    any character (already true today) and is understood by the domain layer as ineligible for
    normal TP opportunity cost.
  - An `account_bank`/`character_items` row with `binding` indicating soulbound is usable only when
    its `bound_to` matches the selected character; a soulbound row bound to a different character is
    excluded entirely from the returned usable quantity for that item ID.
  - Ordinary unbound/tradable owned quantities are returned and priced exactly as they are today (no
    regression to the existing craft-vs-buy/opportunity-cost behavior for non-bound materials).
- `craft.CraftingResolver`/`craft.PlanState` (or a narrowly-scoped new type introduced alongside
  them) correctly avoids charging Trading-Post opportunity cost for the account-bound/soulbound
  portion of consumed owned inventory, while continuing to charge it normally for ordinary tradable
  owned quantities.
- The existing no-argument `InventoryRepository.loadOwnedInventory()`/`loadOwnedInventory(Connection)`
  methods and the domain code path they feed remain unchanged in behavior — this story adds a new
  path, it does not modify what `CraftingProfitController`/`CraftingDiscoveryController` currently
  call. (Production call-site wiring is `STORY-DOM-012`.)
- A domain-level unit test (in `src/test/java/craft/`, using `CraftTestFixtures`) demonstrates the
  *intended* (spec-correct) behavior for at least: (a) a soulbound item bound to the selected
  character is usable and carries no TP opportunity cost; (b) a soulbound item bound to a different
  character is not counted as usable owned inventory for the selected character; (c) an account-bound
  item is usable by any character and carries no TP opportunity cost.

## Required Tests

- New domain unit test(s) under `src/test/java/craft/` covering the three scenarios in the last
  Acceptance Criteria bullet, following the existing `CraftTestFixtures`-based pattern used by
  `CraftingResolverBoundMaterialCharacterizationTest`/`CraftingResolverPriceUnavailableTest`.
- If the new repository method is most naturally verified at the repository level (real
  `binding`/`bound_to` filtering against Postgres), extend or add a Layer 2 integration test
  following the `repo.InventoryRepositoryOwnedInventoryTest` (`STORY-TEST-002`) pattern — a disposable
  schema on the local Postgres instance, no new tooling. Required only if the filtering logic itself
  lives in SQL/the repository rather than being purely a Java-side transformation the domain unit
  tests already exercise; use judgment and record the choice in Result.

## Constraints

- Do not modify `CraftingProfitController`/`CraftingDiscoveryController` call sites or add UI in this
  story — that is `STORY-DOM-012`, kept separate so this story stays domain/repo-focused and
  independently testable.
- Do not introduce a persisted "selected character" setting — per `UD-001`'s Resolution, selection is
  a per-run/per-call explicit input, not global state.
- Do not add a new `craft.BlockedReason` value unless the Acceptance Criteria above cannot be met
  without one — the spec's requirement is about pool composition and opportunity cost, not
  necessarily a new blocked state; if you find you do need one, note why in Result rather than adding
  it silently.
- Preserve existing behavior for every call path this story does not explicitly change (`CLAUDE.md`
  Working Rules).

## Dependencies

None (UD-001 is RESOLVED; no story-level dependency).

## Definition of Done

- New repository method + domain-layer changes implemented as scoped above.
- New domain unit test(s) pass and demonstrate spec-correct (not merely characterized) behavior for
  §11.1/DQ-007.
- `./mvnw test` passes (all existing tests, including `CraftingResolverBoundMaterialCharacterizationTest`
  unless deliberately retired/replaced — explain the choice in Result).
- Existing production controllers/UI are unaffected (no behavior change for current users until
  `STORY-DOM-012` lands).
- `agent/PROJECT_STATE.md` and `agent/stories/BACKLOG.md` updated per `CLAUDE.md`'s IMPLEMENTATION
  MODE rules.

## Result

Implemented as an additive path, per the Context section's "separate sellable-vs-bound owned
quantities" approach, without touching any existing production call site:

- `repo.InventoryRepository` gained `loadOwnedInventoryForCharacter(String selectedCharacterName)`
  / `loadOwnedInventoryForCharacter(Connection, String)`, returning
  `Map<Integer, InventoryRepository.OwnedQuantity>` where `OwnedQuantity(sellableQty, boundQty)`
  is per item ID. Classification (`classifyOwnedRow`, private) reads each row's `binding`/`bound_to`
  (GW2 API convention: `"Account"` / `"Character"` / absent, matching how `BankParser`/
  `CharacterItemsParser`/`MaterialParser` already store the raw API string):
  - `binding` absent → ordinary owned quantity → `sellableQty` (unchanged pricing/opportunity-cost
    behavior).
  - `binding = "Account"` → `boundQty` (usable by any character).
  - `binding = "Character"` → `boundQty` only if `bound_to` equals `selectedCharacterName`;
    otherwise the row is skipped entirely (not counted anywhere), satisfying "excluded entirely
    from the returned usable quantity for that item ID."
  - `account_materials` rows are classified the same way but with `bound_to = null` always passed
    in (that table has no `bound_to` column, so its rows can only be unbound or account-bound,
    never soulbound), matching the Context section's note.
  - The existing `loadOwnedInventory()`/`loadOwnedInventory(Connection)` methods were not touched.
- `craft.PlanState` gained a second owned-quantity map, `boundInventory` (account-bound/soulbound-
  usable, consumed first, zero opportunity cost), plus a `PlanState(Map sellableInventory, Map
  boundInventory)` constructor. The existing single-arg `PlanState(Map baseInventory)` now delegates
  to it with `boundInventory = Map.of()`, so every existing caller (production and tests) is
  unaffected. `copyFrom(...)` was extended to also copy `boundInventory` (needed for the craft-vs-buy
  candidate-state-copy machinery in `CraftingResolver` to keep working correctly on the new path).
  Added `consumeInventoryWithBinding(itemId, qtyWanted)` returning a small
  `PlanState.InventoryConsumption(usedBound, usedSellable)` record - consumes `boundInventory`
  first, then falls back to the existing `consumeInventory(...)` for `inventory`. This is the
  "narrowly-scoped new type introduced alongside them" allowed by the Acceptance Criteria; no new
  top-level class was needed.
- `craft.CraftingResolver.resolveNeed(...)`'s inventory-consumption step (the only place that
  charges TP opportunity cost on owned quantity) now calls `consumeInventoryWithBinding(...)` and
  charges opportunity cost only on `usedSellable()`, never on `usedBound()`. Because `boundInventory`
  defaults to empty for every existing call path, `usedBound()` is always 0 there, so
  `usedSellable() == usedFromInventory` exactly as before - no behavior change for any input that
  doesn't populate `boundInventory` (i.e. every current production controller call, per the
  Acceptance Criteria's "existing ... methods and the domain code path they feed remain unchanged").
- No new `BlockedReason` was added - not needed; the rule is entirely about which pool an owned
  quantity is counted/priced in, not a new terminal state. A soulbound-to-another-character quantity
  that the repository excludes simply isn't present in either pool, so downstream behavior (blocked
  via `NO_RECIPE`/`PRICE_UNAVAILABLE`/etc., same as any other missing supply) falls out of the
  existing machinery unchanged.

Tests:

- `src/test/java/craft/CraftingResolverBoundMaterialTest.java` (new, 3 tests) - the required
  domain-level intended-behavior test, using `CraftTestFixtures.stateWithBoundInventory(...)` (new
  fixture helper): (a) soulbound-to-selected-character quantity is usable with zero opportunity cost
  even when a TP quote exists for that item ID (proves the bound path ignores TP pricing rather than
  happening to compute zero because no quote existed); (b) account-bound quantity is likewise usable
  with zero opportunity cost (domain layer does not need to distinguish account-bound from
  soulbound-and-usable once the repository has already split them into the same `boundInventory`
  pool - only the repository-layer classification needs to know the difference); (c) a
  soulbound-to-a-different-character quantity, modeled as simply absent from both pools (exactly
  what the new repository method would hand the domain layer), is not usable and leaves the request
  blocked (`PRICE_UNAVAILABLE`, since the item is non-tradable and there's no recipe in the test
  fixture).
- `src/test/java/repo/InventoryRepositoryBoundMaterialTest.java` (new, 3 tests) - a Layer 2
  PostgreSQL integration test (`docs/TEST_STRATEGY.md` §31.2 pattern, same disposable-schema idiom
  as `InventoryRepositoryOwnedInventoryTest`), added because the classification's `binding`/
  `bound_to` column reads and real-row filtering aren't exercised by the domain-level tests above
  (which start from an already-split `PlanState`). Verifies: unbound/account-bound/soulbound-to-
  selected/soulbound-to-other rows are classified correctly across all three source tables; a
  soulbound-to-another-character row with no other owned quantity for that item ID is excluded from
  the result map entirely (not merely zeroed); and the existing flat `loadOwnedInventory(Connection)`
  is unaffected by binding columns (still sums every row regardless of binding).
- `CraftingResolverBoundMaterialCharacterizationTest` (`STORY-DOM-007`) was kept, not retired: it
  still accurately documents the still-unchanged, still-live production path (flat
  `loadOwnedInventory()` → single-arg `PlanState(Map)` constructor, `boundInventory` always empty),
  which this story deliberately leaves unaffected. The new `CraftingResolverBoundMaterialTest` is the
  "intended behavior" counterpart for the same DOMAIN_SPEC.md §11.1/DQ-007 rule, exercised through
  the new, not-yet-wired path.

`./mvnw test` (offline, local Postgres reachable): 22 tests run, 0 failures, 0 errors, `BUILD
SUCCESS` (16 previous + 3 new domain tests + 3 new repo integration tests).

No production controller/UI call sites were touched - `CraftingProfitController`/
`CraftingDiscoveryController` still call the original `loadOwnedInventory()` and construct
`PlanState` via the single-arg constructor, so current users see no behavior change. Wiring the new
path into those controllers (character dropdown → `loadOwnedInventoryForCharacter(...)` →
`PlanState(sellable, bound)`) is `STORY-DOM-012`, now unblocked.

No unresolved domain question or scope surprise encountered.

Follow-up (retry pass): `docs/ROADMAP.md` Phase 0 exit criterion 3 ("each `KNOWN_PROBLEMS.md` §3
conflict has at least one intended-behavior test") was left unchecked in the initial pass pending
confirmation that §3.6's already-accepted no-test decision (`STORY-DOM-010`, DONE) counts toward it.
On review, that decision was already finalized by `STORY-DOM-010` reaching DONE — it is not a new
judgment call introduced by this story — so all six `KNOWN_PROBLEMS.md` §3.x conflicts now satisfy
the criterion (five with an automated test, §3.6 with an explicit accepted rationale for having
none). The checkbox is now checked in `docs/ROADMAP.md`, with a clarifying note distinguishing
"test coverage" (this criterion) from "wired into production" (`STORY-DOM-012`, separate and still
open). `agent/PROJECT_STATE.md`'s Current Phase paragraph was updated to match.

## Blockers

None.
