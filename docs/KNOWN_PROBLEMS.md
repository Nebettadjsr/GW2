# GW2 Tool — Known Problems

## 1. Purpose

This document records defects, architectural risks, ambiguities, technical debt, and incomplete functionality found while analyzing the existing implementation (see `CURRENT_ARCHITECTURE.md` for the structural map this is based on).

Every item below is explicitly labeled:

- **Observed fact** — directly read in source code or git history.
- **Inferred risk** — a consequence that follows from the observed fact but was not itself executed/tested.
- **Recommendation** — a suggested future action. Nothing in this document has been implemented.

No source files were modified while producing this document.

---

## 2. Configuration

### 2.1 Configuration values previously hardcoded in source (resolved)

**Observed fact (historical):** `src/repo/AppConfig.java` previously contained a literal GW2 API key and a literal PostgreSQL password as `public static final` fields, committed across multiple commits.

**Status: Resolved.** `AppConfig.java` now sources these values via `repo.EnvConfig` from environment variables, with a gitignored `.env` file as a local-development fallback (see `.env.example`). No credential values remain in source.

**Accepted project policy:** Per project decision, the GW2 API key used by this project is read-only and is not treated as an account-takeover credential, and the local PostgreSQL password is not treated as a material security finding. These two items are intentionally not re-raised as security findings — see `CLAUDE.md` § Project-Specific Security Policy. The externalization above was done for configuration hygiene (environment-based config is the direction defined in `TARGET_ARCHITECTURE.md` §15), not because either value was treated as a live security incident.

---

## 3. Domain-Spec Conflicts (verified against `docs/DOMAIN_SPEC.md`)

Per CLAUDE.md's operating rule, these are reported rather than silently fixed.

### 3.1 Craft-vs-buy selection optimizes cash cost, not effective economic cost

**Observed fact:** `craft/CraftingResolver.chooseBetterCandidate(...)` compares `buyEval.need.getBuyCostCopper()` vs `craftEval.need.getBuyCostCopper()` (cash cost) **first**, and only falls back to `getEffectiveCostCopper()` (cash + opportunity cost) when the two cash costs are exactly equal:

```java
if (craftCash < buyCash) return craftEval;
if (buyCash < craftCash) return buyEval;
return craftEval.need.getEffectiveCostCopper() <= buyEval.need.getEffectiveCostCopper() ? craftEval : buyEval;
```

**Conflict:** `DOMAIN_SPEC.md` §22 states explicitly: *"the planner must select the valid acquisition path with the lowest total effective economic cost... not minimize immediate cash spending,"* and gives a worked example (craft: cash 0 / effective 80; buy: cash 60 / effective 60 → buy should be chosen). The current code would choose **craft** in that exact example, because `craftCash (0) < buyCash (60)` short-circuits before the effective-cost comparison is reached.

**Inferred risk:** Any recipe where crafting an ingredient consumes valuable owned materials "for free" in cash terms will be systematically preferred by the planner even when buying is objectively cheaper, understating true opportunity cost across the whole crafting-profit feature.

**Recommendation:** This is a defined-rule conflict per `DOMAIN_SPEC.md` §51 — report and, when instructed to fix, compare `getEffectiveCostCopper()` directly rather than gating on cash cost first, backed by a regression test using the exact spec example (§22) and `TEST_STRATEGY.md` §6.4.

**Status: Resolved.** `CraftingResolver.chooseBetterCandidate(...)` now compares `getEffectiveCostCopper()` directly instead of gating on cash cost first, covered by `craft.CraftingResolverCraftVsBuyTest`.

### 3.2 Recipe selection for multiple recipes producing the same item does not follow the documented priority

**Observed fact:** `craft/CraftingResolver.firstRecipeFor(itemId, ctx)` returns the first recipe in `ctx.recipesByOutput.get(itemId)` whose id is in `ctx.allowedRecipeIds` — no discipline comparison, no cost comparison between candidate recipes for the same output item.

**Conflict:** `DOMAIN_SPEC.md` §30 (and DQ-003) requires: (1) prefer a recipe matching the parent recipe's discipline, (2) among same-discipline candidates prefer lowest effective cost, (3) otherwise prefer lowest effective cost among all valid candidates. None of this is implemented; recipe choice depends only on database/list ordering.

**Inferred risk:** For any item with multiple valid recipes, results are effectively non-deterministic with respect to intended domain behavior and may silently pick an economically worse or cross-discipline recipe.

**Recommendation:** Report as a defined-rule conflict; implement discipline-aware, cost-based selection with a dedicated test per `TEST_STRATEGY.md` §6.8 before relying on multi-recipe items in profit/discovery output.

**Status: Resolved.** `CraftingResolver.firstRecipeFor(...)` now implements the discipline-first, lowest-effective-cost priority (`DOMAIN_SPEC.md` §30/DQ-003), covered by `craft.CraftingResolverMultipleRecipeSelectionTest`.

### 3.3 Owned-material pool omits character inventories entirely

**Observed fact:** `repo/InventoryRepository.loadOwnedInventory()` queries only `account_materials` and `account_bank`. A `character_items` table is defined in `src/PostgreSQL Query to create DB` but is not referenced anywhere in application source (confirmed via repository-wide search).

**Conflict:** `DOMAIN_SPEC.md` §9 and DQ-006 state the owned pool = material storage + bank + **all character inventories**, explicitly "DECIDED."

**Inferred risk:** Crafting-profit and discovery calculations understate owned materials whenever relevant items sit in character bags, causing the planner to report items as needing purchase/craft when they are already owned — directly affecting `craftableCount`, `buyCostCopper`, and profit figures.

**Recommendation:** Report as a defined-rule conflict; either sync `character_items` and extend `loadOwnedInventory()` to include it, or explicitly document this as an accepted temporary limitation if character inventory sync doesn't yet exist.

**Status: Resolved.** `InventoryRepository.loadOwnedInventory()` now sums `character_items` across all characters/locations, and `sync.CharacterSync` populates that table from the GW2 API. Covered by a Layer 2 PostgreSQL integration test (`repo.InventoryRepositoryOwnedInventoryTest`). Binding (`binding`/`bound_to`) is intentionally not filtered here — see §3.4.

### 3.4 Bound-material rules (account-bound / soulbound) are entirely unimplemented

**Observed fact:** No reference to `binding`, `bound_to`, or "soulbound" exists anywhere under `src/craft/` (verified by search). `InventoryRepository.loadOwnedInventory()` does not select the `binding`/`bound_to` columns that exist in `account_bank`.

**Conflict:** `DOMAIN_SPEC.md` §11.1 / DQ-007 requires soulbound items to be usable only by the bound character, and both account-bound and soulbound items to be excluded from normal Trading-Post opportunity cost.

**Inferred risk:** The planner currently treats all owned items identically regardless of binding, which both overstates usable inventory (soulbound items usable by any character) and misstates opportunity cost (bound items should carry no TP opportunity cost, but the code has no branch that would produce a different result for them anyway since it never distinguishes them from tradable owned items).

**Recommendation:** Report as a defined-rule conflict; this needs a character-scoping concept in the planner input (`DOMAIN_SPEC.md` §7/§11.1) that does not currently exist.

**Status: Resolved.** The repo + domain layers implement this rule (`repo.InventoryRepository.loadOwnedInventoryForCharacter(...)`, `craft.PlanState.boundInventory`), per the resolved `agent/user-decisions/UD-001-selected-character-concept.md`, covered by `craft.CraftingResolverBoundMaterialTest`/`repo.InventoryRepositoryBoundMaterialTest`. `CraftingProfitView`/`CraftingDiscoveryView` now have a character selector (`STORY-DOM-012`) that feeds the selected character into `CraftingProfitController`/`CraftingDiscoveryController`, which call the binding-aware repository method whenever "use own mats" is enabled.

### 3.5 Price-unavailable requirements are silently hidden rather than exposed as a blocked state

**Observed fact:** `CraftingProfitController.hasZeroPricedBuy(...)` filters an entire UI row out of the result list whenever a required purchase has no usable TP quote, rather than surfacing it. `craft/BlockedReason` has no `PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, or `INSUFFICIENT_BUDGET` value; it defines `NO_TP_PRICE` but that value is never assigned anywhere in `craft/*` (confirmed by search — only declared, never set).

**Conflict:** `DOMAIN_SPEC.md` §21 requires missing/invalid prices be treated as an explicit "price unavailable" state rather than silently resolved; §42 lists `PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, and `INSUFFICIENT_BUDGET` as blocked reasons the domain should preserve.

**Inferred risk:** A user cannot currently distinguish "not profitable" from "cannot be evaluated because a price is missing" — the row simply disappears from the Crafting Profit table, which contradicts §41's requirement to keep feasibility and profitability questions separate and explainable.

**Recommendation:** Report as a defined-rule conflict; extend `BlockedReason` with the missing values and thread them through instead of filtering rows at the controller layer.

**Status: Partially Resolved.** `craft.BlockedReason` now has `PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, and `INSUFFICIENT_BUDGET`, each set instead of silently dropping the row, each covered by a regression test. `CraftingProfitController.hasZeroPricedBuy(...)`'s controller-level row filtering was deliberately left unchanged and remains open.

### 3.6 Ectoplasm Salvage calculation exists in two disagreeing implementations

**Observed fact:** `EctoView.fillProfitGrid`/`fillLuckGrid` compute dust revenue as `dustSellPrice * DUST_PER_ECTO` with `DUST_PER_ECTO = 0.75` and **no Trading Post fee deduction**. The separate, UI-disconnected `Main.java` computes `profitPerEcto`/`costPer1000Luck` using `DUST_PER_ECTO = 1.0` and explicitly applies a 15% fee via `applySellFees(...)` before computing profit.

**Conflict:** `DOMAIN_SPEC.md` §45–47 defines "Dust sale value" and "Trading Post fees" as relevant parameters of the Ectoplasm domain but does not pin down (for the live `EctoView` feature specifically) whether the fee is applied to Crystalline Dust revenue — the spec's §25 fee exemption is scoped explicitly to "Crafting Profit," not to Ectoplasm Salvage, implying the Ecto feature likely should apply a fee, consistent with `Main.java` but not with the live `EctoView`.

**Inferred risk:** The feature a user actually reaches from the application UI (`EctoView`, via the "Salvage Ecto for Dust & Luck" button) produces different numeric output than the spec's implied model and than the project's own earlier implementation, with no indication in the UI that a fee is or isn't included.

**Recommendation:** This is a newly discovered ambiguity, not a settled conflict — per `DOMAIN_SPEC.md` §51/§53, it should be documented as an open domain question (e.g., "DQ-011 — Ectoplasm Salvage fee model") and resolved explicitly before either implementation is changed.

**Status: Resolved.** Per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (`DOMAIN_SPEC.md` DQ-011), `EctoView` is now the sole implementation; the disconnected `Main.java` was deleted.

### 3.7 Crafting Profit's character selector does not yet offer `All characters`

**Observed fact:** `CraftingProfitView`'s `characterBox` (`ComboBox<String>`) is populated only from `CharacterRepository.loadAllCharacterNames()` — individual synced character names — with no `All characters` entry anywhere in its item list or selection-default logic (`reloadCharacterChoices`, confirmed by direct reading). It defaults to the first synced character name, matching `UD-001`'s original default rather than `UD-004`'s.

**Conflict:** `DOMAIN_SPEC.md` §2.2.1, per the resolved `agent/user-decisions/UD-004-all-characters-calculation-semantics.md`, requires Crafting Profit to offer `All characters` as the first selector entry and initial default, explicitly replacing `UD-001`'s first-synced-character default for this view only.

**Inferred risk:** None beyond the documented gap itself — Crafting Profit still behaves exactly as `STORY-DOM-012` left it (individual-character selection only), which remains valid, tested behavior; it simply does not yet expose the newer coordinated `All characters` mode `UD-004` calls for.

**Recommendation:** No action needed beyond tracking — `STORY-DOM-014-coordinate-all-characters-crafting.md` (Status: TODO at the time of writing) is the story explicitly scoped to add the `All characters` entry together with the coordinated multi-character planning logic it requires (per-step eligibility, transferable intermediates, soulbound-per-step enforcement). Implementing only the selector entry without that planning logic would misrepresent `All characters` as available when the underlying calculation cannot yet honor it, so this is deliberately left for that story rather than partially addressed here.

**Status: Open**, tracked by `STORY-DOM-014` (TODO). `STORY-DOM-015` (verification of individual-character selection/refresh behavior) confirmed this gap during its own verification pass but its scope does not include implementing `All characters` mode.

---

## 4. Architectural Coupling

See `docs/CURRENT_ARCHITECTURE.md` §9 for the full structural observations; below is problem/risk framing and recommendations for each.

### 4.1 Domain layer depends on the persistence layer's types

**Observed fact:** See `docs/CURRENT_ARCHITECTURE.md` §9 item 1 — the crafting engine's core data types (`Recipe`, `Ingredient`, `TpQuote`) are repository-owned nested classes, not independent domain types.

**Inferred risk:** This is a direct violation of `TARGET_ARCHITECTURE.md` §7's Domain Independence Rule and §26's forbidden-dependency list (no `Domain → PostgreSQL`-adjacent coupling). It means the crafting engine cannot be unit tested, reused, or moved to a backend module without also carrying `repo.*` (and therefore JDBC-shaped) types along with it.

**Recommendation:** Introduce independent domain types (`Recipe`, `Ingredient`, `PriceQuote`) and a mapping boundary in `repo.*`, per `TARGET_ARCHITECTURE.md` §10. This is foundational for `TEST_STRATEGY.md` §4.1 domain unit tests, which currently cannot exist without either a live/fake PostgreSQL-shaped repository or awkward direct construction of repository-nested classes.

### 4.2 Business rule embedded in SQL

**Observed fact:** See `docs/CURRENT_ARCHITECTURE.md` §9 item 2 — the "recipe is unlocked" business rule is embedded in a SQL `UNION` CTE rather than application/domain code.

**Inferred risk:** This duplicates a domain concept (recipe knowledge, `DOMAIN_SPEC.md` §6) inside SQL text, making it untestable without a database and harder to keep consistent with the same rule if it's ever needed outside this one query (e.g., in Discovery's "already unlocked account-wide" rule, §34, which is currently implemented via a *different* query — `loadMissingDiscoverableRecipeIdsForCharacter` — not inspected in full this pass, but structurally a second, separate implementation of "is this recipe known").

**Recommendation:** Flagged per CLAUDE.md's Database rule ("do not embed domain behavior in SQL or repository classes"); worth confirming both unlock-related queries encode the same semantics before any refactor.

### 4.3 Duplicated JDBC connection helper

**Observed fact:** See `docs/CURRENT_ARCHITECTURE.md` §9 item 5 — two independent JDBC connection helpers (`repo.Db`, `sync.Db`) with identical behavior, both reading `repo.AppConfig`.

**Inferred risk:** Low functional risk today (both are simple `DriverManager.getConnection` calls), but any future change to connection handling (pooling, timeouts, SSL params) requires remembering to update both, and the duplication signals the `repo`/`sync` split was not deliberately designed as a layering boundary.

**Recommendation:** Collapse to one shared connection helper when touching either package next; not urgent in isolation.

### 4.4 UI-layer classes perform synchronization orchestration and domain calculation directly

**Observed fact:** See `docs/CURRENT_ARCHITECTURE.md` §9 items 3–4 — `EctoView` performs its domain calculation inline, and `Gw2App`'s button handlers call `sync.*`/construct repository instances directly rather than exclusively through `InitialSetupService`/`AccountRefreshService`. (The historical third path, `Main.java`, was deleted — see Status below.)

**Inferred risk:** Business logic reachable from three different UI-adjacent paths for what should be one calculation (see §3.6) increases the chance that a future domain-rule change is applied in one place and missed in another — this is very likely how the `EctoView`/`Main` divergence in §3.6 happened in the first place (**inferred**, not confirmed by commit-history analysis).

**Recommendation:** Consolidate to a single domain calculation source before any further Ecto-feature changes; matches `TARGET_ARCHITECTURE.md` §12's rule that the frontend must not reproduce authoritative calculations.

**Status: Partially Resolved.** `Main.java` was deleted (per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md`), removing that third path. `Gw2App`'s direct `sync.*`/`repo.*` calls and `EctoView`'s inline calculation remain unconsolidated — open, scheduled for Phase 3 (`docs/ROADMAP.md`).

---

## 5. Missing Test Protection

**Observed fact (historical):** No `test` directory, no JUnit/Mockito/testing dependency present in `lib/`, no build tool configured to run tests. `TEST_STRATEGY.md` (already present in `docs/`) explicitly acknowledges this in its own §28 "Current-State Reality."

**Inferred risk (historical):** All of §3's conflicts above were, at the time, protected by nothing — any future change to `craft/*` (including a well-intentioned refactor) had no automated way to detect whether it preserved or worsened the existing (already-nonconformant) behavior. This matches the exact failure mode `TEST_STRATEGY.md` §30 was written to prevent ("fix bug A → accidentally break B").

**Status: Resolved.** A Maven build and a JUnit 5 test framework now exist (`./mvnw test`), and the §3 conflicts above are covered by regression/characterization/integration tests. Current test status lives in `agent/PROJECT_STATE.md`; testing methodology lives in `docs/TEST_STRATEGY.md`.

**Recommendation:** Per `TEST_STRATEGY.md` §24, prioritize inventory consumption, recursive crafting, craft-vs-buy selection, and multi-recipe selection first — these map directly to the confirmed conflicts in §3.1–§3.3 above.

---

## 6. Duplicated or Conflicting Logic (summary)

| Concern | Location A | Location B | Status |
|---|---|---|---|
| Ectoplasm salvage profit/luck-cost calculation | `EctoView.java` (live UI, sole implementation) | `Main.java` (deleted) | Resolved — see §3.6 |
| JDBC connection acquisition | `repo.Db.open()` | `sync.Db.openConnection()` | Behaviorally identical, structurally duplicated — see §4.3 |
| "Recipe is unlocked" semantics | `RecipeRepository.loadRecipes` (SQL CTE) | `RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter` (separate query, not fully inspected) | Not confirmed consistent — see §4.2 |
| Craft-vs-buy cost comparison | `CraftingResolver.chooseBetterCandidate` (active path) | dead code: `CraftingPlanner.canCraft`/`simulateCraft`/`obtain` (removed, see §7.1) | Resolved — dead code removed |

---

## 7. Other Technical Debt

### 7.1 Dead code in the active crafting engine file

**Observed fact:** `craft/CraftingPlanner.java` defines `canCraft(...)`, `simulateCraft(...)`, `obtain(...)`, and the private `PlanRun` class. None of these are called by `evaluateAll`/`evaluateOneRecipeNew` (the methods actually used — confirmed by full-file read and a repository-wide search showing no external callers of `canCraft`/`simulateCraft`/`obtain`). This is a separate, older "pick the first recipe" implementation (its own comment says `// v1: first recipe`), superseded by `CraftingResolver`/`RecipeSimulator`.

**Inferred risk:** ~190 lines of unreachable logic in the single most important file in the codebase increases the chance a future reader or agent edits the wrong (dead) code path and observes no effect, or mistakes it for the active implementation.

**Recommendation:** Safe to delete once confirmed unused by a full build (not attempted here per this task's no-source-changes constraint).

**Status: Resolved.** Removed after re-confirming zero callers repo-wide.

### 7.2 Leftover debug instrumentation

**Observed fact:** `CraftingPlanner.evaluateOneRecipeNew(...)` contains:
```java
if (recipe.outputItemId == 70992 ) {
    System.out.println("DEBUG shouldSimulate for " + recipe.recipeId + ...);
}
```
a conditional debug print hardcoded to one specific item ID.

**Recommendation:** Remove when next touching this method; not urgent in isolation.

**Status: Resolved.** Removed.

### 7.3 Hardcoded local filesystem path

**Observed fact:** `InitialSetupService.firstFill()` calls `IconSync.syncItemIconsToDisk(Path.of("C:\\Users\\Administrator\\AppData\\Local\\NebetGw2Tool\\icons"))` — a Windows-specific, machine-specific absolute path compiled into source.

**Inferred risk:** Breaks on any other machine or OS; blocks containerization (`TARGET_ARCHITECTURE.md` §17) as-is.

**Recommendation:** Move to configuration once configuration handling is introduced (see §2.1 recommendation — likely the same piece of work).

**Status: Resolved.** `InitialSetupService.firstFill()` now resolves the icon-cache path via a portable `ICON_CACHE_DIR` env var (with a `<user.home>/...` default) instead of a hardcoded Windows path.

### 7.4 Non-deterministic-looking heuristic skip in profit evaluation

**Observed fact:** `CraftingPlanner.evaluateOneRecipeNew` skips simulation entirely (`maySkipCheap`) when `!useOwnMats && !allowBuying`, based on `shouldSimulateRecipe(...)`, which estimates recipe worth using only **direct, non-recursive** ingredient buy/sell prices (`CraftingResolver.resolveDirectBuyUnit`/`resolveDirectSellUnit`), not the full recursive crafting cost.

**Inferred risk:** A recipe whose direct ingredients look unprofitable by immediate TP price, but whose ingredients are themselves cheaper to craft recursively, could be incorrectly skipped (treated as `craftableCount = 0`) under this narrow settings combination, understating results without any visible indication to the user that a shortcut was taken.

**Recommendation:** Worth a targeted test once domain tests exist (`TEST_STRATEGY.md` §6.4) for the `useOwnMats=false, allowBuying=false` combination specifically; not urgent since it's scoped to one settings combination.

### 7.5 Checked-in generated cache file

**Observed fact:** `crafting_graph_cache.json` (~4.4 MB) is tracked in git (`git ls-files` confirms it) despite `CURRENT_STATE_SPEC.md` §14/§23 describing it as derived/rebuildable data. It is not listed in `.gitignore`.

**Inferred risk:** Generated data drifting from the database it was derived from, repository bloat, and merge-conflict-prone binary-ish diffs on every rebuild.

**Recommendation:** Consider `.gitignore`-ing it once a reproducible rebuild step (e.g., part of first-time setup) is guaranteed to exist for every environment that needs it.

**Status: Resolved.** `crafting_graph_cache.json` was untracked from git and added to `.gitignore`, and `CraftingGraphCache.load()` now auto-rebuilds when the file is missing.

### 7.6 Arbitrary craft-count cap

**Observed fact:** `RecipeSimulator.simulatePhase(...)` hard-stops at `result.getCraftCount() >= 250`.

**Inferred risk:** Not itself a bug, but an undocumented behavior boundary — a very large craftable count (plausible for cheap, high-volume materials) is silently truncated to 250 with no domain-level state indicating truncation occurred, which could misrepresent `totalProfitCopper` as a true maximum when it is actually a capped figure.

**Recommendation:** Document this as an intentional performance guard in `DOMAIN_SPEC.md` if it is meant to stay, or expose a "capped" indicator if it should be visible to users.

---

## 8. Incomplete Functionality (matches `CURRENT_STATE_SPEC.md` §31, confirmed by direct reading)

- **Observed fact:** The API-key `TextField`/"Save" button in `Gw2App.createHomeScene()` only sets a status label; it does not persist the entered key anywhere. `AppConfig.API_KEY` remains the only source of the key.
- **Observed fact:** `AccountRefreshService.refreshAll()` exists but has no call site in `Gw2App` (searched; only `InitialSetupService` and the individual `sync.*` calls in button handlers are wired up).

---

## 9. Summary of Highest-Priority Items

Ranked by combination of (a) confirmed conflict with an authoritative spec and (b) blast radius across features:

1. §3.1 — craft-vs-buy selection optimizes cash over effective cost (directly contradicts a worked example in `DOMAIN_SPEC.md`, affects every profit calculation). **Resolved** — see §3.1.
2. §3.3 — owned-material pool omits character inventories (affects every profit/discovery calculation for any item held on a character). **Resolved** — see §3.3.
3. §4.1 — domain layer coupled to repository types (blocks `TEST_STRATEGY.md`'s entire domain-test strategy and `TARGET_ARCHITECTURE.md`'s migration plan). **Still open** — scheduled for Phase 2 (`docs/ROADMAP.md`).
4. §3.2, §3.4, §3.5 — recipe-selection priority, bound-material rules, and price-unavailable state, each independently confirmed unimplemented. **§3.2 and §3.4 resolved; §3.5 partially resolved** — see each subsection.
5. §3.6 — duplicated/disagreeing Ecto calculation (user-facing numeric inconsistency, but isolated to one feature). **Resolved** — see §3.6.
6. §3.7 — Crafting Profit's character selector does not yet offer the resolved `All characters` default. **Open**, tracked by `STORY-DOM-014` — see §3.7.

(§2.1, configuration hardcoding, is resolved — see §2.)

This ranking reflects priority at the time this document was first written. Most items have since been implemented — see each subsection's Status line above and `agent/stories/BACKLOG.md` `## Done` for the stories that closed them. Still-open work: §3.5's controller-layer row filtering, §3.7's pending `All characters` selector/planning mode, and §4's architectural coupling (Phase 2).
