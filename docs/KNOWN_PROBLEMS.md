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

**Scope correction (found by `STORY-QUALITY-001`, since closed):** The "No credential values remain in source" statement above was originally only true for the `repo.AppConfig`/`sync.*`/`repo.*` code path; §2.2 below records the two view classes that had never been migrated to it. Those were migrated by `STORY-APP-009`, so the statement now holds for the whole codebase.

### 2.2 Hardcoded database credentials remain in `BankView`/`MaterialsView` (resolved)

**Observed fact:** `BankView.java` (lines 33-35, 54) and `MaterialsView.java` (lines 19-21, 135) each declare their own literal `DB_URL`/`DB_USER`/`DB_PASS` constants (`jdbc:postgresql://localhost:5432/GWDatabase`, `postgres`, `0`) and call `DriverManager.getConnection(...)` directly, bypassing `repo.AppConfig`/`repo.EnvConfig` entirely. `MaterialsView.java` carries an explicit German TODO acknowledging this: `// TODO: später sauber zentralisieren (repo.AppConfig), für jetzt hier wie bei BankView:`.

**Inferred risk:** This is a third and fourth independent hardcoded-connection path beyond the two already tracked in §4.3 (`repo.Db`, `sync.Db`), and it re-opens exactly the hardcoding pattern §2.1 was resolved to eliminate for the rest of the codebase — a future environment/credential change (e.g. moving `DATABASE_URL`) silently would not reach these two views. Per the accepted project policy above, the specific literal value ("0", localhost) is not itself a material security finding; the finding is the hardcoded/uncentralized pattern and its architectural drift from `TARGET_ARCHITECTURE.md` §15.

**Recommendation:** When next touched, route both views through `repo.AppConfig`/`repo.Db` like the rest of the application (or their Phase 3 application-service replacement). Not urgent in isolation; not part of Phase 1's domain-stabilization scope.

**Status: Resolved (`STORY-APP-009`), by the recommendation's second option.** Both views now obtain their data from a named application service (`application.BankContentsService`, `application.MaterialStorageService`) backed by a persistence adapter (`repo.BankRepository`, `repo.MaterialStorageRepository`) that opens its connection through the shared `repo.Db.open()` → `repo.AppConfig`/`repo.EnvConfig` path. The `DB_URL`/`DB_USER`/`DB_PASS` constants, the `DriverManager` calls, the `java.sql` imports and `MaterialsView`'s German TODO are all gone from both view classes; neither view contains SQL any more. Verified by `repo.BankRepositoryTest`/`repo.MaterialStorageRepositoryTest` (each asserts the no-argument load reaches a disposable schema through `repo.Db`'s configuration path) and by `BankViewIT`/`MaterialsViewIT` (each view renders controlled service results, with no database of its own). Consequence of adopting the shared configuration, recorded deliberately: a missing `DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD` now surfaces through each view's existing failure presentation (`BankView`'s red `DB error: ...` label, `MaterialsView`'s silent empty page) instead of the views connecting to a literal `localhost` database regardless of configuration.

Originally recorded by `STORY-QUALITY-001`'s Phase 1 project-health review; not previously tracked in this document.

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

**2026-09-23 code-health audit — additional confirmed gap (CH-E1):** The comparator correction above remains in place, but `src/main/java/craft/CraftingResolver.java` can prevent it from seeing the cheaper candidate. `resolveNeed(...)` calls `estimateDirectCraftFloor(...)` before `tryCraft(...)` and skips crafting when that estimate is at least the direct purchase cost. The estimate is the sum of direct ingredient *purchase* prices, not a lower bound on recursive effective cost: owned bound ingredients can cost zero, owned tradable ingredients can have a lower sell-side opportunity cost, and recursively crafted ingredients can be cheaper. For example, an intermediate purchasable for 60 copper whose ingredient costs 100 to buy but only 10 in owned-material opportunity cost is rejected for crafting before the 10-versus-60 comparison. Missing ingredient quotes return `Integer.MAX_VALUE` and can suppress a feasible recursive route too. **Consequence:** economically wrong purchases and budget/craftability results despite the corrected comparator; conflicts with DOMAIN_SPEC §22. **Recommendation:** separately assess candidate pruning together with the related recipe-ranking gap in §3.2. Static evidence confirmed; no runtime reproduction performed.

### 3.2 Recipe selection for multiple recipes producing the same item does not follow the documented priority

**Observed fact:** `craft/CraftingResolver.firstRecipeFor(itemId, ctx)` returns the first recipe in `ctx.recipesByOutput.get(itemId)` whose id is in `ctx.allowedRecipeIds` — no discipline comparison, no cost comparison between candidate recipes for the same output item.

**Conflict:** `DOMAIN_SPEC.md` §30 (and DQ-003) requires: (1) prefer a recipe matching the parent recipe's discipline, (2) among same-discipline candidates prefer lowest effective cost, (3) otherwise prefer lowest effective cost among all valid candidates. None of this is implemented; recipe choice depends only on database/list ordering.

**Inferred risk:** For any item with multiple valid recipes, results are effectively non-deterministic with respect to intended domain behavior and may silently pick an economically worse or cross-discipline recipe.

**Recommendation:** Report as a defined-rule conflict; implement discipline-aware, cost-based selection with a dedicated test per `TEST_STRATEGY.md` §6.8 before relying on multi-recipe items in profit/discovery output.

**Status: Resolved.** `CraftingResolver.firstRecipeFor(...)` now implements the discipline-first, lowest-effective-cost priority (`DOMAIN_SPEC.md` §30/DQ-003), covered by `craft.CraftingResolverMultipleRecipeSelectionTest`.

**2026-09-23 code-health audit — related evidence for CH-E1 (§3.1):** `CraftingResolver.computeFirstRecipeFor(...)` preserves discipline preference but ranks candidates using `estimateDirectCraftFloor(...)`, the same direct-purchase estimate, rather than resolved effective cost. It neither considers available bound/owned inventory nor recursively prices candidate ingredients, and compares recipe-batch costs without normalizing differing output counts to the requested quantity. `tryCraft(...)` attempts only this selected recipe, so an unsatisfiable candidate can also hide a feasible alternative. The new context memoization preserves that pre-existing choice; memoization itself is not the defect. **Consequence:** §30's lowest-effective-cost/valid-candidate requirement is still not established by the historical correction. This is part of CH-E1, not a second finding; preserve the historical resolution above while planning any follow-up.

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

**Status: Resolved.** `craft.BlockedReason` has `PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, and `INSUFFICIENT_BUDGET`, each set instead of silently dropping the row. `CraftingProfitController.hasZeroPricedBuy(...)` no longer exists; both `CraftingProfitController`/`CraftingDiscoveryController` now route result preparation through a shared `prepareRows(...)` boundary plus `CraftingResultPresentation`, which recomputes "required purchase has no usable price" (missing, zero, or negative — `DOMAIN_SPEC.md` §21) and reports it as `PRICE_UNAVAILABLE` instead of dropping the row. `CraftingProfitView`/`CraftingDiscoveryView` render a "Status / requirements" column carrying the blocked reason and replace numeric cells with "Unavailable" text (never a fabricated zero/free cost or completed profit) for rows where `calculationAvailable` is false. Per `STORY-DOM-013`: covered by unit regression `CraftingBlockedRowsTest` (missing/zero/negative/absent-quote price, both controllers, plus a valid-price control case unaffected) and by real-view TestFX regressions `uiverify.CraftingProfitViewBlockedRowIT`/`uiverify.CraftingDiscoveryViewBlockedRowIT` (STORY-UI-001's harness), each asserting the row stays visible with `PRICE_UNAVAILABLE` in the actual running view. `./mvnw test` (53 tests) and the four real-view IT tests (`FxCompatibilityPrototypeIT`, `CraftingProfitViewSmokeIT`, `CraftingProfitViewBlockedRowIT`, `CraftingDiscoveryViewBlockedRowIT`) all passed together — actual commands and evidence recorded in `STORY-DOM-013`'s Result.

### 3.6 Ectoplasm Salvage calculation exists in two disagreeing implementations

**Observed fact:** `EctoView.fillProfitGrid`/`fillLuckGrid` compute dust revenue as `dustSellPrice * DUST_PER_ECTO` with `DUST_PER_ECTO = 0.75` and **no Trading Post fee deduction**. The separate, UI-disconnected `Main.java` computes `profitPerEcto`/`costPer1000Luck` using `DUST_PER_ECTO = 1.0` and explicitly applies a 15% fee via `applySellFees(...)` before computing profit.

**Conflict:** `DOMAIN_SPEC.md` §45–47 defines "Dust sale value" and "Trading Post fees" as relevant parameters of the Ectoplasm domain but does not pin down (for the live `EctoView` feature specifically) whether the fee is applied to Crystalline Dust revenue — the spec's §25 fee exemption is scoped explicitly to "Crafting Profit," not to Ectoplasm Salvage, implying the Ecto feature likely should apply a fee, consistent with `Main.java` but not with the live `EctoView`.

**Inferred risk:** The feature a user actually reaches from the application UI (`EctoView`, via the "Salvage Ecto for Dust & Luck" button) produces different numeric output than the spec's implied model and than the project's own earlier implementation, with no indication in the UI that a fee is or isn't included.

**Recommendation:** This is a newly discovered ambiguity, not a settled conflict — per `DOMAIN_SPEC.md` §51/§53, it should be documented as an open domain question (e.g., "DQ-011 — Ectoplasm Salvage fee model") and resolved explicitly before either implementation is changed.

**Status: Resolved.** Per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (`DOMAIN_SPEC.md` DQ-011), `EctoView` is now the sole implementation; the disconnected `Main.java` was deleted.

**Subsequent product change, implemented:** The PO superseded the no-fee decision in DOMAIN_SPEC.md §45–47/DQ-011. `STORY-DOM-016-ecto-salvage-net-sale-proceeds.md` (DONE) applied it: `EctoView`'s calculation now lives in `EctoSalvageCalculator`, a plain class that deducts the project's 15% Trading Post selling fee from recovered Dust's sale proceeds exactly once (§46) before computing profit and cost-per-1000-Luck (§47), across all four Ecto-buy/Dust-sell combinations. Ecto acquisition cost and the expected yield assumptions (`DUST_PER_ECTO`, `LUCK_PER_ECTO`, `ECTOS_PER_1000_LUCK`) are unchanged and never fee-adjusted. The old duplicate-implementation defect (this section's original subject) remains resolved separately — this is a same-implementation behavior change, not a reappearance of the deleted `Main.java` duplicate.

### 3.7 Crafting selection and refresh verification remains pending

**Implementation evidence:** `agent/stories/STORY-DOM-014-coordinate-all-characters-crafting.md` is DONE and records implementation and PostgreSQL/real-view verification of `docs/DOMAIN_SPEC.md` §2.2.1. Earlier observations about the missing coordinated selector predated that completed story and no longer establish an implementation gap.

**Verification completed:** `agent/stories/STORY-DOM-015-preserve-refresh-state-and-verify-character-results.md` is DONE. Two new real-view TestFX regressions (`uiverify.CraftingProfitViewRefreshPreservationIT`, `uiverify.CraftingDiscoveryViewRefreshPreservationIT`, reusing `STORY-UI-001`'s harness) prove, by live execution against a disposable PostgreSQL schema, that the manual "Refresh" button in both views preserves the current Discipline/Character scope, sort mode, search text and checkboxes instead of resetting them to defaults, and that a scope/character change made after a refresh still reaches the coordinated-planner/inventory calculation for characters with differing bound-material ownership. No defect was found; no production code changed. Automatic refresh was confirmed by code reading to invoke the identical reload path as manual refresh; live scheduler execution remains impractical to automate without the live GW2 API (both schedulers call `AccountSync`/`CharacterSync` methods that hit `https://api.guildwars2.com`), consistent with `STORY-UI-001`'s own constraint against the live API as a normal test dependency. See `STORY-DOM-015`'s Result for full detail, including a recorded (non-blocking) open wording question about whether `DOMAIN_SPEC.md` §2.2.1's "ascending/reverse direction" also covers `TableView`'s native per-column click-sort, separate from the sort-mode `ComboBox` whose own preservation is proven.

**Status: Resolved.**

---

## 4. Architectural Coupling

See `docs/CURRENT_ARCHITECTURE.md` §9 for the full structural observations; below is problem/risk framing and recommendations for each.

### 4.1 Domain layer depends on the persistence layer's types (resolved)

**Observed fact:** See `docs/CURRENT_ARCHITECTURE.md` §9 item 1 — the crafting engine's core data types (`Recipe`, `Ingredient`, `TpQuote`) were repository-owned nested classes, not independent domain types.

**Inferred risk:** This was a direct violation of `TARGET_ARCHITECTURE.md` §7's Domain Independence Rule and §26's forbidden-dependency list (no `Domain → PostgreSQL`-adjacent coupling). It meant the crafting engine could not be unit tested, reused, or moved to a backend module without also carrying `repo.*` (and therefore JDBC-shaped) types along with it.

**Status: Resolved by `STORY-DOM-017`.** Independent domain types `craft.Recipe`, `craft.Ingredient`, and `craft.PriceQuote` replace the repository-nested classes; `repo.RecipeRepository`/`repo.tp.TpPriceRepository` own the persistence-to-domain mapping boundary per `TARGET_ARCHITECTURE.md` §10, and `craft.*` no longer imports anything from `repo.*`.

### 4.2 Business rule embedded in SQL (resolved)

**Status: Resolved by `STORY-DOM-019`** (extracted by `STORY-DOM-018`). The "recipe is unlocked"
decision itself is no longer expressed as SQL: `craft.RecipeKnowledgePolicy.isKnownAccountWide` (no
JDBC/SQL/repository dependency) is the single pure function over plain unlock facts, and
`repo.RecipeRepository` only fetches those facts (`account_recipes` ids, `character_recipes` ids)
and calls the policy in Java. `loadRecipes`, `loadRecipesForCharacter`, and
`loadMissingDiscoverableRecipeIdsForCharacter` all call this same policy function with the same two
facts (account unlock, unlock by any character on the account).

**Historical note — cross-character disagreement, now corrected:** `STORY-DOM-018` extracted the
policy but preserved a pre-existing disagreement, per its behavior-preservation constraint:
`loadRecipesForCharacter`/`loadMissingDiscoverableRecipeIdsForCharacter` checked only the *selected*
character's own `character_recipes` rows (`isKnownByCharacter`), while `loadRecipes` checked *any*
character's rows (`isKnownAccountWide`) — so a recipe unlocked only by a different character was
"known account-wide" for `loadRecipes` but still reported as a missing/undiscovered candidate for
another character, contradicting `DOMAIN_SPEC.md` §35's "once any character unlocks the recipe, it
must no longer appear as undiscovered for other characters on the account" and the decided
`DQ-010` ("Discovery recipe ownership semantics").

`STORY-DOM-019` corrected `loadRecipesForCharacter` and `loadMissingDiscoverableRecipeIdsForCharacter`
to use `isKnownAccountWide` (account_recipes OR any character's character_recipes) instead of the
narrower `isKnownByCharacter`, which was removed as no longer used anywhere. Selected-character
discipline filtering and minimum-rating eligibility are unchanged and remain separate from this
ownership decision. Verified by `repo.RecipeRepositoryTest#knowledgeCrossCheck_recipeKnownOnlyByAnotherCharacterAgreesAcrossAllThreeEntryPoints`,
which now asserts agreement across all three entry points for the same underlying unlock facts,
plus dedicated per-entry-point cases (`loadRecipesForCharacter_knownOnlyByAnotherCharacter_isIncluded`,
`loadMissingDiscoverableRecipeIdsForCharacter_knownOnlyByAnotherCharacter_isExcluded`).

### 4.3 Duplicated JDBC connection helper

**Status: Resolved by `STORY-INFRA-003`.** `sync.Db` was removed; all `repo.*` and `sync.*` callers now share `repo.Db.open()`. See `docs/CURRENT_ARCHITECTURE.md` §9 item 5.

### 4.4 UI-layer classes perform synchronization orchestration and domain calculation directly

**Observed fact:** See `docs/CURRENT_ARCHITECTURE.md` §9 items 3–4 — `EctoView` calls its own `EctoSalvageCalculator` directly (no repository/controller/application-service boundary), and `Gw2App`'s button handlers call `sync.*`/construct repository instances directly rather than exclusively through `InitialSetupService`/`AccountRefreshService`. (The historical third path, `Main.java`, was deleted — see Status below.)

**Inferred risk:** Business logic reachable from multiple UI-adjacent paths for what should be one calculation (see §3.6) increases the chance that a future domain-rule change is applied in one place and missed in another — this is very likely how the `EctoView`/`Main` divergence in §3.6 happened in the first place (**inferred**, not confirmed by commit-history analysis).

**Recommendation:** Wire `EctoSalvageCalculator` through an application service alongside Profit/Discovery before any further Ecto-feature changes; matches `TARGET_ARCHITECTURE.md` §12's rule that the frontend must not reproduce authoritative calculations.

**Status: Resolved.** `Main.java` was deleted (per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md`), removing that third path. `STORY-DOM-016` also extracted `EctoView`'s calculation out of the view class into the plain `EctoSalvageCalculator` (no JavaFX/repo/controller dependency), so it is no longer literally inline.

`STORY-APP-003` closed the Ecto-specific part of this gap: `EctoView` no longer acquires Trading Post prices or invokes the domain calculator directly. It now calls one named `application.EctoSalvageService` (`docs/CURRENT_ARCHITECTURE.md` §5.3), which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` (a live, unsynchronized HTTP call - unchanged behavior, just relocated) and invokes the calculator - now `ecto.EctoSalvageCalculator`, moved out of the default package so the application layer can import it - across all four Ecto-buy/Dust-sell combinations. `EctoView` is left with grid rendering and presentation formatting only, plus its own unrelated icon-fetching HTTP call (presentation-only, not part of this calculation). Verified by `application.EctoSalvageServiceTest` (fake price gateway; quote handoff, all four combinations, missing-quote and fetch-failure propagation), the existing `ecto.EctoSalvageCalculatorTest` regression (fee/yield math unchanged), the existing real-view `uiverify.EctoFeeNoticeSmokeIT` (still passes unmodified), and a new real-view `EctoSalvageViewIT` (default package) that injects a fake service into the real `EctoView` and asserts the rendered profit/Luck-cost grid values plus exactly-once delegation to the service.

`STORY-APP-004`, `STORY-APP-005` and `STORY-APP-007` closed the remaining `Gw2App` part of this
gap: "Sync Account" now calls `application.AccountRefreshService`, "Sync ALL tradeable Items..."
now calls `application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`, and
"First-time DB Setup" now calls `application.InitialSetupService` - none of `Gw2App`'s three sync
button handlers call `sync.*`/construct `repo.*` instances directly any more (see
`docs/CURRENT_ARCHITECTURE.md` §9 item 4). The previously separate top-level `InitialSetupService`
was deleted; one authoritative setup workflow remains in the application layer. This resolves both
observed facts in this item; it does not by itself mark Phase 3 complete, and
`BankView`/`MaterialsView` remain a separate, unrelated `repo.*` bypass (`docs/KNOWN_PROBLEMS.md`
§2.2).

**2026-09-23 code-health audit — additional presentation-boundary gaps (CH-E2, confirmed):** The extracted use cases above remain present. Separately, `src/main/java/CraftingProfitView.java#reloadDisciplineChoices` and `CraftingDiscoveryView.java#reloadDisciplineChoices/#reloadCharacterChoices` still instantiate `repo.CharacterRepository` and query it directly. Also `CraftingProfitController.UiRow` receives domain `totalProfitCopper`, but `CraftingProfitView` drops it when building `CraftRow` and independently recomputes total profit in the total-profit column and `applyClientFilterAndSort(...)` (`craftableCount * profitCopper`). These are current competing ownership paths, not a complaint about absent future REST infrastructure: TARGET_ARCHITECTURE §12/§32 and the current Phase 3 view-to-application boundary require presentation to use application/domain results. **Inferred risk:** future domain changes can update the backend total while the displayed/sorted total silently keeps the old formula; selector loading bypasses the reusable use-case boundary. **Recommendation:** handle these specific remaining call sites separately; the historical Ecto/home-sync extraction is not undone by this finding.

**Selector portion resolved by `STORY-APP-010`.** Both crafting views now populate their selectors through one named application operation, `application.CharacterSelectionService.getCraftingCharacterOptions()`/`getCharacterNames()` over the unchanged `repo.CharacterRepository` reads; neither view instantiates or queries a repository any more (`docs/CURRENT_ARCHITECTURE.md` §4, §5.1, §5.2, §6). Selector behavior is unchanged: Profit still offers "All" + the fixed nine base disciplines + one per-character entry per synced discipline row and defaults to "All"; Discovery still offers per-character entries only plus its separate Character name list, each selecting its first entry; the repository read order, the failure presentation on each of the three call sites, and the number of repository loads and calculation reloads per page open are all as before. Verified by `application.CharacterSelectionServiceTest` (fake repository), `CraftingProfitViewSelectorIT` and `CraftingDiscoveryViewSelectorIT` (real windows, injected selector results).

**Total-profit portion resolved by `STORY-APP-011`.** `CraftingProfitView.toCraftRow(...)` now carries `UiRow.totalProfitCopper` into `CraftRow`; the "Total profit" column binds to that property and both total-profit sort paths (the "Total profit" sort mode and the fallback default, now in `rowComparator(...)`) order by it. No `craftableCount * profitCopper` recomputation remains in the view, so presentation can no longer diverge from the domain total. The domain formula, per-craft profit semantics, currency formatting, blocked-row "Unavailable" rendering, the search filter and the descending sort direction/tie order are unchanged. Verified by `CraftingProfitTotalProfitPresentationTest` (rows whose supplied total deliberately differs from the old product, so a recomputation fails the test rather than agreeing with it) plus the existing `uiverify.CraftingProfitViewSmokeIT`, `uiverify.CraftingProfitViewBlockedRowIT` and `uiverify.CraftingProfitViewRefreshPreservationIT` real-view checks.

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
| JDBC connection acquisition | `repo.Db.open()` (shared by `repo.*` and `sync.*`, see §4.3) | `BankView`/`MaterialsView` inline `DriverManager.getConnection(...)` with hardcoded literals (removed) | Resolved — one shared path; see §4.3 and §2.2 (`STORY-APP-009`) |
| "Recipe is unlocked" semantics | `RecipeRepository.loadRecipes`/`loadRecipesForCharacter` (via `craft.RecipeKnowledgePolicy.isKnownAccountWide`) | `RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter` (via `isKnownAccountWide`) | Resolved — all three entry points share one policy function, see §4.2 |
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

**Status: Resolved.** `application.InitialSetupService.firstFill()` (`STORY-APP-007`, formerly a top-level `InitialSetupService` class) now resolves the icon-cache path via a portable `ICON_CACHE_DIR` env var (with a `<user.home>/...` default) instead of a hardcoded Windows path.

### 7.4 Non-deterministic-looking heuristic skip in profit evaluation

**Observed fact:** `CraftingPlanner.evaluateOneRecipeNew` skips simulation entirely (`maySkipCheap`) when `!useOwnMats && !allowBuying`, based on `shouldSimulateRecipe(...)`, which estimates recipe worth using only **direct, non-recursive** ingredient buy/sell prices (`CraftingResolver.resolveDirectBuyUnit`/`resolveDirectSellUnit`), not the full recursive crafting cost.

**Inferred risk:** A recipe whose direct ingredients look unprofitable by immediate TP price, but whose ingredients are themselves cheaper to craft recursively, could be incorrectly skipped (treated as `craftableCount = 0`) under this narrow settings combination, understating results without any visible indication to the user that a shortcut was taken.

**Recommendation:** Worth a targeted test once domain tests exist (`TEST_STRATEGY.md` §6.4) for the `useOwnMats=false, allowBuying=false` combination specifically; not urgent since it's scoped to one settings combination.

### 7.5 Checked-in generated cache file

**Observed fact:** `crafting_graph_cache.json` (~4.4 MB) is tracked in git (`git ls-files` confirms it) despite `CURRENT_STATE_SPEC.md` §14/§23 describing it as derived/rebuildable data. It is not listed in `.gitignore`.

**Inferred risk:** Generated data drifting from the database it was derived from, repository bloat, and merge-conflict-prone binary-ish diffs on every rebuild.

**Recommendation:** Consider `.gitignore`-ing it once a reproducible rebuild step (e.g., part of first-time setup) is guaranteed to exist for every environment that needs it.

**Status: Resolved.** `crafting_graph_cache.json` was untracked from git and added to `.gitignore`, and `CraftingGraphCache.load()` now auto-rebuilds when the file is missing.

### 7.6 Intentional craft-count cap (resolved)

**Observed fact:** `RecipeSimulator.simulatePhase(...)` hard-stops at `result.getCraftCount() >= 250`.

**Inferred risk:** Not itself a bug, but an undocumented behavior boundary — a very large craftable count (plausible for cheap, high-volume materials) is silently truncated to 250 with no domain-level state indicating truncation occurred, which could misrepresent `totalProfitCopper` as a true maximum when it is actually a capped figure.

**Status: Resolved by explicit decision.** `agent/user-decisions/UD-003-craft-simulation-cap.md` is answered and `docs/DOMAIN_SPEC.md` §28 records the intentional limit and indicator policy. No implementation change or new passing-test claim is made by this planning update.

**2026-09-23 code-health audit — implementation boundary defect (CH-E3, confirmed):** The intentional limit is accepted, but `src/main/java/craft/RecipeSimulator.java#simulateRecipe` can exceed it. With own materials and buying enabled, phase 1 can finish at 250 crafts; phase 2 then enters `simulatePhase` unconditionally. Its limit check is *after* resolving, committing and incrementing another craft, so an affordable next craft produces 251. **Consequence:** violates the existing UD-003 maximum and can consume/report an additional batch. **Recommendation:** preserve the agreed cap while enforcing it across both phases. This does not request a new cap policy or indicator, and leaves the historical policy resolution intact. Evidence is the two-phase control flow, not an executed test.

### 7.7 Crafting Profit exposes technical status counters (resolved)

**PO-reported issue:** `remove-crafting-profit-debug-status-text.md` reports a line below the Crafting Profit Analyzer title such as `Loaded 2179 recipes. | rows=2179 missingLines=0 missingTp=0 zeroBuyPrice=0`. These numbers are the request's example, not measured repository/runtime facts.

**Required correction:** Remove this developer diagnostic line from normal Crafting Profit presentation; preserve useful internal log diagnostics and actual user-facing errors. Keep the title and surrounding layout clean. This differs from the resolved planner console instrumentation in §7.2.

**Status: Resolved.** `STORY-UI-002-remove-crafting-profit-debug-text.md`: `CraftingProfitView`'s
reload success handler now logs the same row/missing/zero-price counters via `System.out.println`
instead of the shared `statusLabel`, and clears that label's text on success, so neither the "Loaded
N recipes" count nor the counters appear in the UI on initial load, manual Refresh, or auto-refresh.
Genuine user-facing messages on the same label (load/auto-refresh/TP-refresh failure text) are
unchanged. Verified live via `uiverify.CraftingProfitViewSmokeIT` (line absent after load and after
Refresh, title retained) and a new `uiverify.CraftingProfitViewErrorStatusIT` (a forced `SQLException`
still surfaces `"❌ DB load failed: ..."`). Not addressed: the same label's separate, transient
`"MaxBuy UI=..."` debug text shown at the very start of every reload — a different statement than the
one reported here, out of this story's scope.

### 7.8 Orphaned dead code left behind by the `Main.java` deletion (open)

**Observed fact:** `api/Gw2PriceFetch.java` (a ~50-line HTTP price-fetch helper) has no callers anywhere in the codebase (confirmed by repository-wide search) since its only caller, `Main.java`, was deleted per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (§3.6).

**Inferred risk:** Same category as §7.1's now-resolved dead code — unreachable logic that a future reader or agent could mistake for a live code path, or edit without observing any effect.

**Recommendation:** Safe to delete once independently confirmed (a full repository-wide search already shows zero callers here); not attempted in this review per its no-implementation-changes scope.

**Status: Open.** Newly recorded by `STORY-QUALITY-001`'s Phase 1 project-health review.

**2026-09-23 code-health audit — expanded orphaned-code inventory (CH-E4, confirmed for production reachability):** `src/main/java/model/Price.java` is used only by the already orphaned `api.Gw2PriceFetch`; it belongs to the same abandoned price-fetch path. `src/main/java/CraftingDiscoveryView.java#buildSearchBlob` is a private instance method with only its own recursive call and no entry call; the live workflow uses `CraftingDiscoveryController.prepareRows -> buildSearchBlob` instead. `src/main/java/craft/ResolvedNeedMapper.java#toNode` likewise has no production entry call; the live detail chain instead uses `RecipeTreeBuilder` (CH-12 below). Production caller/resource inspection found no FXML/controller binding, reflective lookup or service registration for these symbols. **Consequence:** obsolete competing implementations remain available to be mistaken for live behavior. **Recommendation:** assess retirement or, for the mapper, intentional reuse through normal work; this audit neither deletes them nor claims their test references were reviewed. Test-only injection overloads and framework/JSON DTO accessors were not classified as dead.

---

### 7.9 Crafting Profit page-load latency (Resolved — measured compliant and accepted 2026-09-23)

**User-reported observations:** navigating to CraftingProfitView takes approximately 20 seconds to populate the page on the user's real database (2026-09-22), later revised to roughly 38–45 seconds measured against the page's own 90-second refresh counter (`agent/user-interventions/UI-001-STORY-PERF-001.md`, 2026-09-23).

**Earlier backend-only measurement (superseded as evidence):** a real-database backend diagnostic (`application.CraftingProfitServiceRealDbPerfIT`) attributed a 67.4 s All-scope backend baseline almost entirely (98%) to `craft.CraftingPlanner.evaluateAllCoordinated(...)`, not to any `repo.*` query, and two bounded optimizations reduced it to ~22 s. That backend figure never matched the user's observation, because it measured one reload of the service in isolation rather than the real page, which ran the pipeline twice per open plus a per-row tree build for its console counters.

**Real-page measurement (`STORY-PERF-001`, 2026-09-23):** `uiverify.CraftingProfitPageLoadRealDbPerfIT` measures the real application from the navigation click until the real table stops changing and answers a displayed-value read, on the real database, All scope, view defaults. Baseline 36.4 / 41.9 / 43.2 s (maximum 43.2 s), reproducing the user's report. After the four changes recorded in `CURRENT_ARCHITECTURE.md` §5.1 (suppressing the duplicate reload, keeping the console counters off the tree-building path, replacing `PlanState`'s copy-per-speculation with an undo journal per UD-005, and memoizing two pure planner lookups): 2.8 / 2.0 / 1.4 s, then 2.8 / 1.7 / 1.4 s on the shipped revision — maximum **2.8 s against the ≤7 s limit** in `TARGET_ARCHITECTURE.md` §33. Results were verified unchanged against a pre-change real-database reference across five settings combinations (zero differences in any profit, cost, craftable count, blocked reason or resolution tree); the one observable difference is the iteration order of the unordered `missingToBuy` map, which changes which two materials the truncated "To buy: …" summary names first for a minority of rows when buying is enabled, and does not affect the default view settings.

**Required correction and acceptance:** `TARGET_ARCHITECTURE.md` §33 owns the complete-page time limit, real-data measurement conditions and mandatory subsequent user confirmation. `STORY-PERF-001` owns implementation/evidence; `ROADMAP.md` Phase 3 owns the blocking exit criterion. Mock/miniature-fixture timings do not resolve this issue. **Status: Resolved.** Both §33 conditions are met: the measured compliance recorded above, and the Product Owner's explicit subsequent dated confirmation, given 2026-09-23 and recorded in `STORY-PERF-001`'s Result (`agent/user-interventions/UI-001-STORY-PERF-001.md`). The disclosed `missingToBuy` iteration-order difference was accepted with no defined ordering required and no follow-up requested.

---

### 7.10 The unauthenticated HTTP boundary now also triggers writes (open)

**Observed fact:** the backend HTTP API has no authentication (`TARGET_ARCHITECTURE.md` §20 leaves it TBD, "only if required") and binds `server.port` on all interfaces, recorded as a limitation when the boundary was created in `STORY-API-001`. Until `STORY-API-003` every route was read-only. `POST /api/sync/account` is the first route that causes a write: any caller that can reach the port can start an account synchronization, which calls the GW2 API with the configured key and upserts account and character data. `STORY-API-004` added the second such route, `POST /api/sync/global`, as this section's own recommendation anticipated: it rewrites the global tradeable-item and recipe rows and then rebuilds `crafting_graph_cache.json`, so its write footprint is larger and longer-running than the account trigger's. `STORY-API-005` added the third and fourth, `POST /api/prices/refresh` with `variant: PROFIT` and with `variant: DISCOVERY`, which fetch Trading Post quotes and upsert `tp_prices`. `STORY-API-007` added no write route — the count is still four — but its two read routes (`GET /api/account/bank`, `GET /api/account/materials`) are the first that return stored account inventory itself rather than a calculation over it. `STORY-API-008`'s two detail routes (`POST /api/crafting/profit/resolution`, `POST /api/crafting/discovery/resolution`) are read-only calculations like the table routes and trigger no synchronization or persisted-inventory consumption, so the write count is still four; what they add to the unauthenticated read surface is the resolution structure behind a row — which materials were taken from stock, crafted or bought, on which character, at which costs.

**Inferred risk:** unauthenticated callers can consume the account's GW2 API rate budget and cause database writes, not only read account-derived data. The task facility's one-unfinished-task-per-operation admission rule limits this to one sync per operation at a time, which bounds the effect but is a concurrency control, not an access control — and since the operation keys are independent by design, an account sync, a global sync and both price-refresh variants can be started concurrently by the same unauthenticated caller. The number of independently startable write operations therefore grows with each trigger added; four is the current count. The unauthenticated *read* surface grew too: a caller reaching the port can now enumerate the account's bank slots and material stacks directly (`STORY-API-007`), where previously it could only obtain crafting results derived from them. No credential is exposed by those routes — they return item ids, quantities and display metadata only.

**Recommendation:** keep the API bound to a local single-user deployment until §20 is decided, and revisit before any non-local deployment or before adding further write-triggering routes. `STORY-API-003` deliberately did not introduce authentication — that is an undecided architecture question (§20), not an implementation detail.

**Status: Open.** Newly recorded by `STORY-API-003`; extends, rather than replaces, `STORY-API-001`'s recorded no-authentication limitation. Re-confirmed and widened by `STORY-API-004` and again by `STORY-API-005`, each of which, like the first, deliberately did not introduce authentication, §20 still being undecided. With Phase 4's sync/refresh routes now complete, this section's recommendation to revisit before further write-triggering routes is addressed to whatever adds the next one; `STORY-API-008` added none, and likewise did not introduce authentication.

---

### 7.11 The web Crafting Profit table still carries a `CYCLE_DETECTED` row diagnostic (open, by instruction)

**Observed fact:** `STORY-WEB-008` removed the web comparison table's general State column and moved `BUYING_DISABLED`, `NO_RECIPE`, `DAILY_LIMIT`, `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET` into the selected-result detail, as `DOMAIN_SPEC.md` §2.1.1 requires. The same section requires a minimal row-level `CYCLE_DETECTED` diagnostic to be retained, and explicitly calls that retention temporary presentation technical debt. It is implemented in `frontend/src/crafting/rowState.ts` (`rowDiagnostic`) and rendered beside the recipe name in `CraftingProfitTable.vue`.

**Inferred risk:** a single domain reason keeps a presentation affordance the information hierarchy otherwise rejects, which invites the column's gradual return if further reasons are added to the same slot. The three other cases that slot carries — a missing price, an absent calculation result, and an unreported or unrecognized code — are not this debt: `DOMAIN_SPEC.md` §2.1.1 requires those distinctions to survive the column's removal, so they stay regardless of what happens to the cycle diagnostic.

**Recommendation:** remove the `CYCLE_DETECTED` branch of `rowDiagnostic` when the Product Owner asks for it, leaving the other three branches in place. Do not extend the slot with additional reason codes in the meantime.

**Status: Open, by instruction.** Recorded by `STORY-WEB-008`; removal is the Product Owner's decision, not a defect to fix unprompted.

---

## 8. Incomplete Functionality (matches `CURRENT_STATE_SPEC.md` §31, confirmed by direct reading)

- **Observed fact:** The API-key `TextField`/"Save" button in `Gw2App.createHomeScene()` only sets a status label; it does not persist the entered key anywhere. `AppConfig.API_KEY` remains the only source of the key.
- **Resolved (`STORY-APP-004`):** The unused top-level `AccountRefreshService.refreshAll()` (no call site in `Gw2App`) has been deleted. Its orchestration now lives in `application.AccountRefreshService`, wired to `Gw2App`'s "Sync Account" button handler; one authoritative account-refresh implementation exists (`docs/CURRENT_ARCHITECTURE.md` §5.4).

---

## 9. Summary of Highest-Priority Items

§7.9's Phase 3 performance gate is no longer a blocking addition — it is Resolved (measured compliant and accepted 2026-09-23); the historical ranking below applies again unchanged.

Ranked by combination of (a) confirmed conflict with an authoritative spec and (b) blast radius across features:

1. §3.1 — craft-vs-buy selection optimizes cash over effective cost (directly contradicts a worked example in `DOMAIN_SPEC.md`, affects every profit calculation). **Resolved** — see §3.1.
2. §3.3 — owned-material pool omits character inventories (affects every profit/discovery calculation for any item held on a character). **Resolved** — see §3.3.
3. §4.1 — domain layer coupled to repository types (blocks `TEST_STRATEGY.md`'s entire domain-test strategy and `TARGET_ARCHITECTURE.md`'s migration plan). **Resolved** — see §4.1 (`STORY-DOM-017`).
4. §3.2, §3.4, §3.5 — recipe-selection priority, bound-material rules, and price-unavailable state, each independently confirmed unimplemented. **Resolved** — see each subsection.
5. §3.6 — duplicated/disagreeing Ecto calculation (user-facing numeric inconsistency, but isolated to one feature). **Resolved** — see §3.6.
6. §3.7 — Crafting Profit's coordinated scope selector and its refresh/selection verification. **Resolved** — see §3.7.

(§2.1, configuration hardcoding, is resolved for `repo.*`/`sync.*`; §2.2's two view classes are resolved as well, by `STORY-APP-009`.)

This ranking reflects priority at the time this document was first written. Most items have since been implemented — see each subsection's Status line above and `agent/stories/BACKLOG.md` `## Done` for the stories that closed them. §4.1–§4.3's Phase 2 architectural-coupling items are resolved (`STORY-DOM-017`/`STORY-DOM-018`/`STORY-DOM-019`/`STORY-INFRA-003`); §4.4's UI-to-application-service wiring gap is resolved (`STORY-APP-003`/`STORY-APP-004`/`STORY-APP-005`/`STORY-APP-007`). §2.2's hardcoded view-level DB credentials are resolved (`STORY-APP-009`). Still-open work from that original ranking: §7.8's orphaned dead code (low-risk, not a Phase 1 blocker, newly recorded by `STORY-QUALITY-001`). It is no longer the only open item in this document: §10's 2026-09-23 production-code health review adds CH-01–CH-22 (including the CH-E1–CH-E4 extensions to §3.1/§3.2, §4.4, §7.6 and §7.8), none of which this ranking covers and none of which has been dispositioned.

---

## 10. Repository-wide production-code health review — 2026-09-23

**Scope:** static inspection of the current working-tree production implementation: Java startup/views/controllers, application services, crafting/ecto domain, repositories, synchronization, HTTP adapters, parsers/models/utilities/resources and the database schema; also Python `agent/runtime` scheduling, process runners, planning guards, selection/state/archive, evaluation, human-input handling and support modules. Existing uncommitted changes were treated as current implementation, not as changes made by this audit.

**Basis:** `CLAUDE.md`, `docs/CODING_GUIDELINES.md`, `tasks/lessons.md`, `docs/TARGET_ARCHITECTURE.md` (especially §§7–13, 22–29, 33–36), `docs/CURRENT_ARCHITECTURE.md`, this document's existing findings, relevant `docs/DOMAIN_SPEC.md` rules, UD-001/UD-003/UD-004/UD-005, current Phase 3 scope in `docs/ROADMAP.md`, and `agent/runtime/README.md`. The current Ecto fee rule is DOMAIN_SPEC §§45–47, as already recorded in §3.6. Future REST/frontend work, intentional transitional adapters, the accepted cap policy and the accepted unordered shopping-summary variation are not defects merely because they could be implemented differently later.

**Audit-only declaration:** production code was not changed; test files were neither reviewed nor run. No application, build, database query, live API call or model/orchestrator execution was performed. Only this document was edited. Findings below distinguish directly observed code behavior from inferred consequences; “confirmed” means established by static call-chain inspection, not a claim of runtime reproduction or measured frequency. Numbering is for reference, not a new priority ranking.

### Existing findings extended, rather than duplicated

- **CH-E1 — confirmed:** remaining cost-estimate pruning/ranking gaps, §3.1/§3.2 (one related finding).
- **CH-E2 — confirmed:** remaining presentation/repository bypass and duplicated total-profit calculation, §4.4. (Both halves were resolved afterwards: the selector bypass by `STORY-APP-010`, the total-profit duplication by `STORY-APP-011` — see §4.4.)
- **CH-E3 — confirmed:** two-phase simulation can exceed the agreed cap, §7.6.
- **CH-E4 — confirmed production reachability:** expanded orphaned implementation inventory, §7.8.

The existing API-key Save placeholder (§8) and transient `MaxBuy UI=...` status (§7.7, still in `CraftingProfitView.reloadTable`) were reconfirmed, not counted as new findings. Remaining console reload counters use the raw result accessor; the previous tree-building logging cost and duplicate Profit initial All reload were not re-reported. §7.9's accepted measurements are historical evidence, not rerun here. No existing finding was resolved by this audit.

### CH-01 — Overlapping crafting reloads race on shared result state (confirmed)

**Affected files/symbols:** `src/main/java/CraftingProfitView.java` and `CraftingDiscoveryView.java`, `reloadTable`, selector/settings listeners and refresh handlers; both controllers' `reload`; `application/CraftingProfitService.java` and `CraftingDiscoveryService.java`, `reload/getResultByRecipeId` and `last*` fields.

**Observed fact:** every trigger starts a new thread against the same controller/service. There is no cancellation, generation check or serialized publication. Settings controls are read from those workers, rather than captured together on the JavaFX thread. Budget typing can launch a job for each edit. In Discovery, the independent discipline and character startup loads each call `selectFirst`; if discipline finishes first, both listeners launch reloads. Service caches are assigned field by field, while the FX row-selection handler also reads them and replaces the result map during lazy enrichment.

**Inferred risk:** redundant database/parallel-planner work, older results overwriting newer selections, mixed settings/caches, and row details belonging to a different reload. This remains after the specific Profit initial-load fix. **Recommendation:** address request ownership, FX input snapshots and coherent result publication together.

### CH-02 — Crafting schedulers outlive window closure (confirmed)

**Affected files/symbols:** both crafting views' `show`, static `scheduler/autoRefreshTask`, Back handlers; `src/main/java/Gw2App.java` lifecycle.

**Observed fact:** both views create `Executors.newSingleThreadScheduledExecutor()` with the default non-daemon thread factory. Shutdown occurs only on Back or reopening that same view; `Gw2App` has no `stop()`/window-close cleanup. Reload and TP threads are not tracked or cancelled on departure, and their queued `runLater` success callbacks can still reload the detached view. The static `autoRefreshTask` retains its captured view/controller even after Back.

**Inferred risk:** closing the application from a crafting page can leave the JVM and periodic account synchronization running; leaving a page does not reliably end its work. **Recommendation:** tie scheduler/jobs/callback validity to the view/application lifecycle. No shutdown experiment was run.

### CH-03 — Bank/Materials navigation blocks the UI; Materials conceals load failure (confirmed)

**Affected files/symbols:** `Gw2App.createHomeScene` Bank/Materials handlers; `BankView.show`; `MaterialsView.show/buildMaterialsContent/loadMaterialsGrouped`; `application.BankContentsService/MaterialStorageService`; `repo.BankRepository/MaterialStorageRepository` (all under `src/main/java`).

**Observed fact:** navigation calls the view synchronously; before setting the new scene, the view calls its application service through to JDBC connection/query/result loading on the JavaFX Application Thread. The extraction changed ownership, not execution timing. Materials catches the load exception, prints it, and returns `List.of()`, presenting the same empty page as genuinely empty storage.

**Inferred risk:** database connection/query delays freeze navigation and all UI interaction; a storage-load failure looks like missing possessions. **Recommendation:** separately handle background loading and an explicit Materials failure state. This does not reopen the resolved hardcoded-connection issue in §2.2.

### CH-04 — Account refresh can publish inconsistent inventory snapshots (confirmed)

**Affected files/symbols:** `Gw2App` sync button handlers; `application.AccountRefreshService.refreshAll/refreshMaterialsAndRecipes`; `sync.AccountSync`, `sync.CharacterSync`; `repo.InventoryRepository` loaders; `CraftingProfitView` auto-refresh.

**Observed fact:** bank/material/character steps fetch and commit independently; inventory reads use separate statements without a common snapshot transaction. Each home sync button disables only itself, so setup/global/account jobs can overlap, including with a navigated view's auto-refresh. Profit's timer refreshes materials and recipes only but reports “Auto-refreshed Bank + Materials”; the calculation still reads stored bank and character inventory.

**Inferred risk:** moving a stack from bank to material storage can leave its old bank quantity alongside its refreshed storage quantity in Profit indefinitely until a full account refresh; even full refresh has intermediate mixed snapshots. Concurrent runs can publish older fetches after newer ones. This is an application-created consistency problem, distinct from accepted GW2 API lag (DOMAIN_SPEC §39). **Recommendation:** define coherent account snapshot publication/refresh ownership and accurate freshness reporting; preserve or explicitly decide refresh scope.

### CH-05 — Removed characters remain eligible and keep phantom inventory (confirmed)

**Affected files/symbols:** `sync.CharacterSync.syncCharactersCraftingAndRecipes/upsertCharacter`; `repo.CharacterRepository.loadAllCharacterNames/loadAllCharacterCrafting`; `repo.InventoryRepository`; `application.CraftingProfitService.buildCoordinatedRoster`.

**Observed fact:** synchronization upserts only names returned by the current account response. It deletes stale child rows only for characters processed in that run and returns immediately for an empty name list. It never removes characters absent from the response. Readers have no current-roster/freshness filter.

**Inferred risk:** deleting/renaming a character, or changing the configured account against the same database, leaves obsolete characters, ratings and items selectable/consumable; coordinated plans can use a nonexistent crafter. **Recommendation:** reconcile removed roster members after a successful authoritative roster fetch, preserving failure safety.

### CH-06 — Global/setup synchronization downloads data already available (confirmed)

**Affected files/symbols:** `sync.RecipeSync.syncAllRecipesGlobalSafe/syncRecipeIngredients`, `sync.ItemSync.syncItemsByIds`, `parser.ItemParser.ItemRow`, `sync.IconSync.syncItemIconUrls`, `application.InitialSetupService.firstFill`.

**Observed fact:** the first recipe pass downloads every recipe batch and already parses its ingredients to collect item IDs. After item synchronization it downloads the identical recipe batches again to persist ingredients. During setup, ItemSync parses each item's `iconUrl` but omits it from the items upsert; the following IconSync queries missing URLs and downloads those item payloads again.

**Inferred risk:** an extra complete recipe-detail pass and, on fresh setup, an extra item-detail pass, with additional network latency/rate-limit exposure and repeated parsing. The FK-required recipe→item→ingredient write order does not require re-fetching the payloads. **Recommendation:** retain/reuse the fetched facts while preserving that ordering. No duration was measured.

### CH-07 — Global recipe synchronization does not replace obsolete ingredient facts (confirmed)

**Affected files/symbols:** `sync.RecipeSync.syncAllRecipesGlobalSafe/syncRecipeIngredients/upsertRecipeIngredients`; `repo.RecipeRepository.loadIngredientsByRecipe`; `repo.CraftingGraphCache.rebuild`.

**Observed fact:** recipe and ingredient writes are upserts only. An ingredient absent from a subsequently fetched recipe is never deleted; nor are removed recipe IDs reconciled. Recipe headers commit before ingredients, and `upsertRecipeIngredients` itself commits every `DB_FLUSH_BATCH`, inside its caller's nominal transaction. A later failure can therefore leave a partially published catalog; a rebuild reads whatever persisted facts remain.

**Inferred risk:** a changed recipe can permanently retain old requirements, and interrupted synchronization can mix new headers with old/partial ingredients. **Recommendation:** establish replacement semantics for successfully fetched recipes and explicit publication/transaction ownership. Actual upstream recipe changes were not fetched in this audit.

### CH-08 — Graph cache has neither freshness validation nor atomic replacement (confirmed)

**Affected files/symbols:** `repo.CraftingGraphCache.load/rebuild/buildCacheKeyFromDb`, `CraftingGraphDto.cacheKey/generatedAt`; `application.InitialSetupService.firstFill`, `GlobalDataRefreshService.refreshAll`, both crafting services' `reload`.

**Observed fact:** `load()` accepts any existing working-directory cache without checking its stored key, age or source database. Setup updates recipes without rebuilding/invalidation. Rebuild deletes the old file before querying the database and writes JSON directly to the final path; reads/rebuilds are not coordinated. A corrupt existing file is parsed repeatedly rather than treated as rebuildable derived data.

**Inferred risk:** stale recipes after setup/database changes; concurrent navigation can read partial JSON or initiate another rebuild during the deletion gap; failed writes can leave a persistent load failure. **Recommendation:** address cache identity/invalidation and safe publication as one lifecycle issue. File caching itself remains an allowed architectural choice.

### CH-09 — Trading Post 404 responses preserve old usable quotes (confirmed)

**Affected files/symbols:** `api.tp.TpPriceApi.fetchSingle`; `sync.TpSync.fetchTpBatchParsed/upsertTpPrices`; `repo.tp.TpPriceRepository.loadTpQuotes`.

**Observed fact:** after a 404 batch or a missing ID in a 206 batch, single-item 404 returns null. `fetchTpBatchParsed` skips that ID, unlike an explicit `TpPrice.noData` quote which writes NULLs. Any existing database quote stays unchanged; readers do not check age or current tradeability.

**Inferred risk:** refresh can report success while an item that no longer has a quote retains an old price used by calculations; missing IDs can also be retried repeatedly without recording the absence. **Recommendation:** distinguish confirmed absence from transient failure and define its persisted quote state.

### CH-10 — Missing icon data hides occupied slots and cannot always be repaired (confirmed)

**Affected files/symbols:** `BankView.createSlotTile`, `MaterialsView.createTile`; `sync.IconSync.syncItemIconsToDisk`; `sync.ItemSync`; `application.InitialSetupService`.

**Observed fact:** Bank renders a null `iconPath` using the exact empty-slot branch before displaying count/item identity; Materials likewise returns an empty-looking tile before count rendering. IconSync selects only rows whose stored `icon_path` is NULL/empty: it does not check the existence of already-recorded files. Moving/deleting the icon directory or changing `ICON_CACHE_DIR` leaves these rows excluded from repair. Account sync does not populate metadata/icons for newly seen account-only items; setup's item population is driven by recipe items.

**Inferred risk:** real possessions look absent when metadata/downloads are missing, and rerunning icon sync cannot repair nonempty paths pointing at missing files. **Recommendation:** separate possession display from icon availability and make cache repair account for stale paths.

### CH-11 — Optional Ecto icons can suppress a successful calculation (confirmed)

**Affected files/symbols:** `src/main/java/EctoView.java#show/fetchItemIcons`; `application.EctoSalvageService.calculate`.

**Observed fact:** the worker calculates price scenarios first, then synchronously requests icon metadata before publishing any values. Non-200 icon responses are tolerated, but an IOException, interrupted send or malformed icon response escapes to the shared catch, which reports “Failed to load prices.” The already successful scenarios are discarded. The metadata request has no configured timeout.

**Inferred risk:** a presentation-only failure/delay leaves the price/profit page empty or loading despite valid prices. **Recommendation:** independently publish calculation success and handle optional icon loading/failure.

### CH-12 — Displayed resolution trees use a different algorithm from the planner (confirmed)

**Affected files/symbols:** both crafting services' `getResultByRecipeId/buildTreeForRecipeId`; `craft.CraftingPlanner.evaluateOneRecipeNew`; `craft.RecipeTreeBuilder.buildIngredientNode`; both views' selection listeners.

**Observed fact:** the planner discards the simulation tree (`Node tree = null`). Selecting a row builds a new dependency tree from global recipes; `RecipeTreeBuilder` chooses `producing.get(0)` and labels it `craft`, without checking allowed recipes, character eligibility, inventory, prices or the actual craft-versus-buy choice. The `ResolvedNeedMapper` capable of representing inventory/buy/blocked choices has no production caller (CH-E4). The lazy accessor also rebuilds the full recipe index on the FX selection thread.

**Inferred risk:** the explanation can prescribe an unavailable or unchosen recipe and claim crafting where the calculation bought/consumed inventory. This conflicts with DOMAIN_SPEC §44 and TARGET_ARCHITECTURE §13; it is not merely a slower getter. **Recommendation:** preserve/expose the actual resolution explanation, including its snapshot ownership (CH-01), instead of a competing recipe-selection path.

### CH-13 — Shopping-list purchase prices follow the sell toggle (confirmed)

**Affected files/symbols:** Profit/Discovery views' row-selection shopping-list mapping; both controllers' `itemSellUnit`, Discovery service's `itemSellUnit`; `craft.CraftingResolver.resolveDirectBuyUnit`.

**Observed fact:** both shopping lists read `rbListingSell` and call `itemSellUnit` when pricing items to buy. The engine uses `settings.listingBuy` instead. With both modes set to instant, the list displays the buy-order bid while the planner purchases at the sell-offer ask; changing the output sell mode changes displayed acquisition prices.

**Inferred risk:** the purchase breakdown disagrees with required cash/budget despite identical quantities (DOMAIN_SPEC §20). **Recommendation:** render purchase-side quotes from the same calculation snapshot/mode as the plan.

### CH-14 — Monetary presentation loses signs and mislabels total purchase cost (confirmed)

**Affected files/symbols:** `util.CoinUtils.formatSigned`; `EctoView.fillProfitGrid/dataCell`; crafting views' profit cells; `CraftingProfitView` column tooltips; `CraftingDiscoveryController.prepareRows` and Discovery shopping list.

**Observed fact:** `formatSigned` calls `format(Math.abs(copper))`, stripping the minus sign. Ecto profit cells have the same white styling for positive/negative values; crafting tables use color but also lose the textual sign. Profit's Buy Cost tooltip says “ONE craft” while the supplied `CraftResult.buyCostCopper` is total plan cost. Discovery likewise displays that total in its table but uses `missingToBuyOne` in the detail list. Profit's revenue tooltip also says “TradingFees will still be deducted -15%” although its domain result intentionally excludes that deduction.

**Inferred risk:** losses appear as positive Ecto profits, and readers cannot reconcile table cash costs with the single-craft explanation; stale fee wording misstates the accepted rule. **Recommendation:** correct presentation against existing domain values/units, without inventing a new economic model.

### CH-15 — A requested recipe can be counted without executing that recipe (confirmed)

**Affected files/symbols:** `craft.CraftingPlanner.evaluateOneRecipeNew`; `craft.RecipeSimulator.simulatePhase`; `craft.CraftingResolver.resolveOneCraft/resolveNeed/tryCraft`.

**Observed fact:** `resolveOneCraft(recipe, ...)` passes only the output item ID/quantity into the generic resolver (after coordinated eligibility checks). That resolver first consumes owned output items, so an already owned finished item can satisfy a “craft” without any recipe ingredients. If crafting is needed it selects a recipe again by output ID, not necessarily the requested recipe whose ID/count/revenue the planner reports.

**Inferred risk:** craftable counts include existing finished stock; multiple recipes for the same output can show another recipe's cost/requirements under the requested recipe's identity. DOMAIN_SPEC §28 asks how often the recipe can be executed, not how many outputs can be supplied from inventory. **Recommendation:** distinguish execution of the requested root recipe from acquisition of its ingredients.

### CH-16 — Intermediate surplus is discarded instead of reused (confirmed)

**Affected files/symbols:** `craft.CraftingResolver.tryCraftAssigned`; `craft.PlanState`; `craft.RecipeSimulator.simulatePhase`.

**Observed fact:** `tryCraftAssigned` computes `produced = times * outputCount`, clamps satisfaction with `Math.min(produced, qtyRequested)`, and never stores `produced - qtyRequested` in simulation state. PlanState's inventory mutations consume quantities; there is no produced-surplus credit on this path. Subsequent branches/batches must obtain those items again.

**Inferred risk:** overstated materials/cash and understated craftability, contrary to DOMAIN_SPEC §17/DQ-009. For the specified six-produced/five-needed example the remaining one never reaches the next requirement. **Recommendation:** preserve produced surplus and its cost ownership across branches/batches, including speculative rollback; do not credit terminal surplus as extra profit.

### CH-17 — Non-tradable valuation fallback is absent from the calculation path (confirmed)

**Affected files/symbols:** `craft.CraftingResolver.resolveNeed/resolveDirectSellUnit`; `repo.ItemRepository.ItemInfo/loadItems`; `sync.ItemSync`; `craft.AcquisitionMode/BlockedReason/Node`.

**Observed fact:** sellable-pool owned quantity is valued as quantity times direct TP sell price; a missing quote becomes zero. Although synchronization stores `items.vendor_value`, the calculation's item projection does not load it, and no recursive/vendor valuation fallback or explicit unvalued-nontradable state reaches the result/tree. Bound quantities' deliberate exemption from normal TP opportunity cost is separate and remains accepted.

**Inferred risk:** a non-tradable quantity reaching the ordinary owned pool receives zero without the recursive→vendor→explicit-unvalued distinction required by DOMAIN_SPEC §11.2/DQ-008. **Recommendation:** assess the current ingestion/classification and valuation contract together; do not substitute invented TP prices or silently treat unknown value as free.

### CH-18 — Planner validation does not fully protect or roll back shared state (confirmed)

**Affected files/symbols:** `agent/runtime/core/project_planner.py#_git_dirty_src_lines/_planning_snapshot/_run_guarded_planner/run_planning_pass/validate_planning_result`; `runners/local_planner_runner.py#run_local_planner`.

**Observed fact:** source protection compares sets of `git status --porcelain -- src` lines, not file contents. An already modified source file can change again without changing that line; Python runtime source is outside this check. The planner has workspace-write access. The rollback snapshot covers Markdown and the planning result, not production source. Finally, result parsing/full validation occur *after* `_run_guarded_planner` has returned: a zero-exit planner that writes an invalid story, malformed result or impermissibly resolved request is marked FAILED without reverting its already-written planning changes.

**Inferred risk:** illegal source edits can escape detection, and rejected planning changes remain visible to later runs/users. Synchronous invocation prevents simultaneous model writers but does not make validation a commit boundary (TARGET_ARCHITECTURE §36). **Recommendation:** separately define content-based protected-state checks and a commit/rollback boundary covering validation; preserve pre-existing user changes.

### CH-19 — All nonzero Claude exits are treated as capacity interruptions (confirmed)

**Affected files/symbols:** `agent/runtime/runners/claude_runner.py#run_claude`; `core/orchestrator.py#execute_active_story`; `support/capacity.py#CapacityProbe.defer`.

**Observed fact:** the runner returns only the process exit code. Every nonzero exit sets the story UNFINISHED, defers capacity and loops back to the same prompt, without classifying usage exhaustion versus authentication/CLI/configuration/process failure. This path never consumes the normal retry limit or creates an intervention.

**Inferred risk:** a deterministic invocation failure becomes an indefinite hourly retry cycle masquerading as quota exhaustion, withholding actionable failure handling. **Recommendation:** preserve quota-resume behavior while distinguishing capacity events from terminal/tooling errors. No Claude command was invoked in this audit.

### CH-20 — Startup stylesheet lookup has the wrong resource case (confirmed)

**Affected files/symbols:** `src/main/java/Gw2App.java#start`; `src/main/resources/Styles/dark-scroll.css`; Maven resource packaging.

**Observed fact:** startup looks up `/styles/dark-scroll.css` and immediately applies `Objects.requireNonNull`, while the tracked resource directory is `Styles`. No resource-renaming configuration was found. Windows directory execution can mask this difference; a case-sensitive classpath/JAR lookup does not.

**Inferred risk:** startup fails at stylesheet loading when resources are resolved case-sensitively. **Recommendation:** verify the packaged resource lookup and align its case in separately authorized work. No packaging/build execution was performed.

### CH-21 — Full-catalog work persists for narrow crafting scopes (requires follow-up)

**Affected files/symbols:** both crafting services' `reload`; `repo.RecipeRepository.loadIngredientsByRecipe`; `repo.CraftingGraphCache.load`; `craft.CraftingGraph` constructor; `craft.CraftingPlanner.evaluateAll/evaluateAllCoordinated`; both controllers' `prepareRows`.

**Observed fact:** each reload parses the entire graph cache, constructs its output index, collects all graph item IDs and loads all those items/quotes. The planner constructs another output index and evaluates every global recipe before the controller keeps visible rows; the controller builds another index for search text. Even a selected discipline uses an unrestricted ingredient-table load for its recipe query. These paths were traced, not inferred from method names.

**Inferred risk requiring measurement:** avoidable graph/collection construction and forbidden-root simulation may materially affect narrow scopes and rapid repeated reloads; transitive ingredient/valuation needs mean simply restricting everything to visible rows would be unsafe. **Recommendation:** profile this concrete chain only if pursued, separating necessary dependency closure from redundant roots/indexes. This is not a claim that the accepted seven-second Profit gate has regressed, nor authorization to accelerate the deferred Discovery performance work.

### CH-22 — Discovery has two independent character authorities (requires follow-up)

**Affected files/symbols:** `CraftingDiscoveryView` discipline/character selectors and startup loaders; `application.CraftingDiscoveryService.reload`; `repo.InventoryRepository.loadOwnedInventoryForCharacter/loadOwnedInventory`.

**Observed fact:** the discipline entry supplies one character/rating for candidate eligibility; a separate independently selected character supplies soulbound inventory. Their defaults use different sort orders (discipline/rating versus character name), and no code keeps them aligned. If the character control is still null during the first reload, the service falls back to the unfiltered owned pool. Thus the actual computation can combine A's eligibility with B's bound items, or briefly use all bindings.

**Inferred risk requiring domain/UX confirmation:** the displayed discovery can appear feasible without a single eligible character owning the required bound materials. UD-001 explicitly introduced the material selector; UD-004 kept Discovery individual-only but did not settle how these two controls should interact. **Recommendation:** establish the intended single-character contract before changing selection behavior; distinguish it from the confirmed asynchronous reload race in CH-01.

### Review totals and limits

- **Confirmed findings: 24** — CH-01 through CH-20 plus four grouped extensions CH-E1 through CH-E4. Existing unchanged observations are not counted again; consequences marked “inferred risk” were not executed.
- **Findings requiring follow-up: 2** — CH-21 (material cost attribution) and CH-22 (character-selection contract).
- **Represented categories:** dead/obsolete duplicate implementations; remaining debug/status remnants; duplicate network/computation paths; expensive lazy accessors; application/presentation boundary violations; snapshot/cache ownership; JavaFX/thread/job lifecycle; error handling and partial publication; domain correctness and misleading monetary presentation; ordering/selection assumptions; Python workflow-state safety.
- **Coverage/limitations:** no first-party production package was excluded. Review depth was call-chain/static inspection, not proof of every branch. Live GW2 response behavior, the actual contents/schema of the user's database, real-data timing/frequency, packaged/platform-specific execution, subprocess failure behavior and third-party/JDK/driver internals could not be verified by this read-only audit. Test code, generated runtime artifacts and build output were outside scope. The accepted unordered shopping-summary behavior was deliberately not reported as an ordering defect. No source fixes, stories, new findings documents, test changes, test runs or existing-finding resolutions were made.
