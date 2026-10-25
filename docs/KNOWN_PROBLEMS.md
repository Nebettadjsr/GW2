# GW2 Tool — Known Problems

## 1. Purpose and maintenance rule

This document is the **current open-problem register** for defects, architectural risks, ambiguities, technical debt, and incomplete functionality that still apply to the repository. The legacy JavaFX desktop interface no longer exists (`STORY-UI-004`), so the findings that were limited to it have been removed from this register rather than carried forward; the browser is the only interface a finding here can describe.

Use it as current-state input, not as a historical changelog:

- Keep only findings that are still open, unresolved, or explicitly require follow-up.
- Before adding or carrying forward a finding, check the current implementation, GitHub issues and PRs, and historical story results for evidence that later work already resolved it.
- When a DONE story or current-code verification establishes that a finding is resolved, **remove the finding from this file** instead of appending a long resolution history here. Story files and the backlog retain that history.
- Do not remove a finding merely because related work exists. If closure is not established, keep the finding and update only the current facts that matter.
- Do not duplicate the same underlying problem under multiple historical headings. Consolidate extensions into the live finding.
- Preserve the distinction between **Observed fact**, **Inferred risk**, and **Recommendation**. A recommendation is not evidence that a fix was implemented.
- A project/code-health review that touches this file should leave it cleaner than it found it: reconcile stale entries against completed work, remove confirmed-resolved findings, merge duplicates, and avoid retaining obsolete audit totals or rankings.

`CURRENT_ARCHITECTURE.md` owns the structural map; `DOMAIN_SPEC.md` and accepted user decisions own domain rules; `TARGET_ARCHITECTURE.md` owns target constraints. This file records where the current implementation still conflicts with, risks, or incompletely realizes those authorities.

No finding in this document by itself authorizes an implementation change. Normal planning and Product Owner decision boundaries still apply.

---

## 2. Crafting-domain correctness and calculation risks

### KP-01 — Candidate pruning and recipe ranking can reject the cheaper valid recursive path (CH-E1)

**Observed fact:** `CraftingResolver.resolveNeed(...)` calls `estimateDirectCraftFloor(...)` before `tryCraft(...)` and can skip crafting when that estimate is at least the direct purchase cost. The estimate sums direct ingredient purchase prices rather than resolved recursive effective cost. Owned bound ingredients, owned tradable ingredients valued by opportunity cost, and recursively craftable ingredients can therefore make the real effective cost lower than this estimate; a missing ingredient quote can also produce `Integer.MAX_VALUE` and suppress a feasible recursive route.

`CraftingResolver.computeFirstRecipeFor(...)` uses the same direct-purchase estimate when ranking multiple recipes. It does not rank candidates by fully resolved effective cost, does not account for available owned/bound inventory in that ranking, and compares recipe-batch costs without normalizing differing output counts to the requested quantity. `tryCraft(...)` then attempts only the selected recipe, so an unsatisfiable candidate can hide a feasible alternative.

**Inferred risk:** economically wrong purchases, recipe choices, budget results, and craftability despite the already-corrected effective-cost comparator. This remains relevant to `DOMAIN_SPEC.md` §22 and §30.

**Recommendation:** assess candidate pruning and multi-recipe ranking together. Any correction should compare valid candidates using the authoritative effective-cost semantics rather than treating direct ingredient purchase cost as a safe lower bound.

### KP-05 — Intermediate crafting surplus is discarded instead of reused (CH-16)


**Observed fact:** `tryCraftAssigned` computes `produced = times * outputCount`, clamps satisfaction with `Math.min(produced, qtyRequested)`, and never stores `produced - qtyRequested` in simulation state. PlanState's inventory mutations consume quantities; there is no produced-surplus credit on this path. Subsequent branches/batches must obtain those items again.

**Inferred risk:** overstated materials/cash and understated craftability, contrary to DOMAIN_SPEC §17/DQ-009. For the specified six-produced/five-needed example the remaining one never reaches the next requirement. **Recommendation:** preserve produced surplus and its cost ownership across branches/batches, including speculative rollback; do not credit terminal surplus as extra profit.


---

## 3. Crafting presentation and explanation risks

No open finding. KP-08, KP-09 and KP-11 described the legacy JavaFX shopping lists, monetary formatting and a transient debug label; `STORY-UI-004` deleted those views, and the browser presentation they were contrasted against was never affected. The story files retain the history.

---

## 4. Synchronization, persistence, cache, and data-freshness risks

### KP-12 — Account refresh can publish inconsistent inventory snapshots across sources (CH-04)


**Observed fact:** bank/material/character sources fetch and publish separately under one account coordinator. Each source publishes atomically, and Crafting Profit captures its inputs in one `REPEATABLE READ` transaction. These guarantees do not make the upstream bank and material-storage responses represent one instant: their published source generations can still differ in age.

**Inferred risk:** moving a stack from bank to material storage can leave its old bank quantity alongside its refreshed storage quantity in Profit indefinitely until a full account refresh; even full refresh has intermediate mixed snapshots. Concurrent runs can publish older fetches after newer ones. This is an application-created consistency problem, distinct from accepted GW2 API lag (DOMAIN_SPEC §39). **Recommendation:** define coherent account snapshot publication/refresh ownership and accurate freshness reporting; preserve or explicitly decide refresh scope.

### KP-14 — Global/setup synchronization downloads data already available (CH-06)


**Observed fact:** the first recipe pass downloads every recipe batch and already parses its ingredients to collect item IDs. After item synchronization it downloads the identical recipe batches again to persist ingredients.

**Inferred risk:** an extra complete recipe-detail pass adds network latency/rate-limit exposure and repeated parsing. The FK-required recipe→item→ingredient write order does not require re-fetching the payloads. **Recommendation:** retain/reuse the fetched facts while preserving that ordering. No duration was measured.


### KP-16 — Graph cache has neither freshness validation nor atomic replacement (CH-08)


**Observed fact:** `load()` accepts any existing working-directory cache without checking its stored key, age or source database. Setup updates recipes without rebuilding/invalidation. Rebuild deletes the old file before querying the database and writes JSON directly to the final path; reads/rebuilds are not coordinated. A corrupt existing file is parsed repeatedly rather than treated as rebuildable derived data.

**Inferred risk:** stale recipes after setup/database changes; concurrent navigation can read partial JSON or initiate another rebuild during the deletion gap; failed writes can leave a persistent load failure. **Recommendation:** address cache identity/invalidation and safe publication as one lifecycle issue. File caching itself remains an allowed architectural choice.


### KP-17 — Trading Post 404 responses preserve old usable quotes (CH-09)


**Observed fact:** after a 404 batch or a missing ID in a 206 batch, single-item 404 returns null. `fetchTpBatchParsed` skips that ID, unlike an explicit `TpPrice.noData` quote which writes NULLs. Any existing quote stays unchanged in low-level storage/readers. Profit now separately reports its age in the freshness envelope, but a confirmed upstream absence is still not recorded as such.

**Inferred risk:** refresh can report success while an item that no longer has a quote retains an old price used by calculations; missing IDs can also be retried repeatedly without recording the absence. **Recommendation:** distinguish confirmed absence from transient failure and define its persisted quote state.



---

## 7. Incomplete or obsolete implementation

### KP-25 — Orphaned production code remains (CH-E4)

**Observed fact:** `api/Gw2PriceFetch.java` has no production callers since its former caller `Main.java` was deleted. `model/Price.java` is used only by that orphaned helper. `craft.ResolvedNeedMapper#toNode` likewise has no production entry call; the live detail chain uses `RecipeTreeBuilder` instead. The 2026-09-23 production-code audit found no FXML/controller binding, reflective lookup, or service registration for these symbols. The three orphans the JavaFX view deletion created — `application.InitialSetupService`, `application.EctoSalvageService` and `util.CoinUtils` — were themselves removed by `STORY-UI-004`, together with `ecto.EctoSalvageCalculator` and `sync.IconSyncGateway`, so they are no longer part of this problem; the same story then removed the desktop icon download the last of those left without a caller (`sync.IconSync.syncItemIconsToDisk` and `sync.DesktopIconAdoption`), so it is not part of this problem either.

**Inferred risk:** obsolete competing implementations remain available to be mistaken for live behavior or modified without effect.

**Recommendation:** reassess production reachability when this area is next touched and retire confirmed-dead paths, or intentionally reuse the mapper through normal planned work if that is the chosen design.

---

## 8. Platform and performance follow-up

### KP-30 — Full-catalog work persists for narrow crafting scopes (CH-21; follow-up required)


**Observed fact:** each reload parses the entire graph cache, constructs its output index, collects all graph item IDs and loads all those items/quotes. The planner constructs another output index and evaluates every global recipe before the caller keeps visible rows. Even a selected discipline uses an unrestricted ingredient-table load for its recipe query. These paths were traced, not inferred from method names.

**Inferred risk requiring measurement:** avoidable graph/collection construction and forbidden-root simulation may materially affect narrow scopes and rapid repeated reloads; transitive ingredient/valuation needs mean simply restricting everything to visible rows would be unsafe. **Recommendation:** profile this concrete chain only if pursued, separating necessary dependency closure from redundant roots/indexes. This is not a claim that the accepted seven-second Profit gate has regressed, nor authorization to accelerate the deferred Discovery performance work.


---

### KP-32 — Global recipe change detection misses edits under unchanged IDs

**Observed fact:** `RecipeSync.syncAllRecipesGlobalSafe()` compares the remote recipe ID set with the local `recipes.recipe_id` set and returns without detail requests when the sets match. Recipe detail fields and ingredients are therefore not checked for in-place changes unless some recipe ID is also added or removed.

**Inferred risk:** an ArenaNet edit to an existing recipe's output, discipline, or ingredients can remain stale locally, and the crafting graph will not rebuild for that edit. The current six-hour job detects catalog membership changes, not full recipe freshness.

**Recommendation:** retain the cheap ID-list gate while a future decision defines an affordable way to detect in-place detail changes; do not describe the current check as detecting every upstream recipe-data edit.

