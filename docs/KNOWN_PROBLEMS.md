# GW2 Tool — Known Problems

## 1. Purpose and maintenance rule

This document is the **current open-problem register** for defects, architectural risks, ambiguities, technical debt, and incomplete functionality that still apply to the repository. The legacy JavaFX desktop interface no longer exists (`STORY-UI-004`), so the findings that were limited to it have been removed from this register rather than carried forward; the browser is the only interface a finding here can describe.

Use it as current-state input, not as a historical changelog:

- Keep only findings that are still open, unresolved, or explicitly require follow-up.
- Before adding or carrying forward a finding, check the current implementation and `agent/stories/BACKLOG.md` / the canonical story result for evidence that later work already resolved it.
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

### KP-03 — Two-phase simulation can exceed the agreed 250-craft maximum (CH-E3)

**Observed fact:** UD-003 / `DOMAIN_SPEC.md` §28 defines the 250-craft maximum as intentional, but `src/main/java/craft/RecipeSimulator.java#simulateRecipe` can exceed it. With own materials and buying enabled, phase 1 can finish at 250 crafts; phase 2 still enters `simulatePhase`. Its limit check occurs after resolving, committing, and incrementing another craft, so an affordable next craft can produce 251.

**Inferred risk:** the implementation can consume and report one additional batch beyond the accepted maximum.

**Recommendation:** enforce the existing maximum across both phases without reopening the cap policy.

### KP-04 — A requested recipe can be counted without executing that recipe (CH-15)


**Observed fact:** `resolveOneCraft(recipe, ...)` passes only the output item ID/quantity into the generic resolver (after coordinated eligibility checks). That resolver first consumes owned output items, so an already owned finished item can satisfy a “craft” without any recipe ingredients. If crafting is needed it selects a recipe again by output ID, not necessarily the requested recipe whose ID/count/revenue the planner reports.

**Inferred risk:** craftable counts include existing finished stock; multiple recipes for the same output can show another recipe's cost/requirements under the requested recipe's identity. DOMAIN_SPEC §28 asks how often the recipe can be executed, not how many outputs can be supplied from inventory. **Recommendation:** distinguish execution of the requested root recipe from acquisition of its ingredients.


### KP-05 — Intermediate crafting surplus is discarded instead of reused (CH-16)


**Observed fact:** `tryCraftAssigned` computes `produced = times * outputCount`, clamps satisfaction with `Math.min(produced, qtyRequested)`, and never stores `produced - qtyRequested` in simulation state. PlanState's inventory mutations consume quantities; there is no produced-surplus credit on this path. Subsequent branches/batches must obtain those items again.

**Inferred risk:** overstated materials/cash and understated craftability, contrary to DOMAIN_SPEC §17/DQ-009. For the specified six-produced/five-needed example the remaining one never reaches the next requirement. **Recommendation:** preserve produced surplus and its cost ownership across branches/batches, including speculative rollback; do not credit terminal surplus as extra profit.


### KP-06 — Non-tradable valuation fallback is absent from the calculation path (CH-17)


**Observed fact:** sellable-pool owned quantity is valued as quantity times direct TP sell price; a missing quote becomes zero. Although synchronization stores `items.vendor_value`, the calculation's item projection does not load it, and no recursive/vendor valuation fallback or explicit unvalued-nontradable state reaches the result/tree. Bound quantities' deliberate exemption from normal TP opportunity cost is separate and remains accepted.

**Inferred risk:** a non-tradable quantity reaching the ordinary owned pool receives zero without the recursive→vendor→explicit-unvalued distinction required by DOMAIN_SPEC §11.2/DQ-008. **Recommendation:** assess the current ingestion/classification and valuation contract together; do not substitute invented TP prices or silently treat unknown value as free.


---

## 3. Crafting presentation and explanation risks

No open finding. KP-08, KP-09 and KP-11 described the legacy JavaFX shopping lists, monetary formatting and a transient debug label; `STORY-UI-004` deleted those views, and the browser presentation they were contrasted against was never affected. The story files retain the history.

---

## 4. Synchronization, persistence, cache, and data-freshness risks

### KP-12 — Account refresh can publish inconsistent inventory snapshots across sources (CH-04)


**Observed fact:** bank/material/character steps fetch and commit independently; inventory reads use separate statements without a common snapshot transaction.

**Inferred risk:** moving a stack from bank to material storage can leave its old bank quantity alongside its refreshed storage quantity in Profit indefinitely until a full account refresh; even full refresh has intermediate mixed snapshots. Concurrent runs can publish older fetches after newer ones. This is an application-created consistency problem, distinct from accepted GW2 API lag (DOMAIN_SPEC §39). **Recommendation:** define coherent account snapshot publication/refresh ownership and accurate freshness reporting; preserve or explicitly decide refresh scope.

**Scope reduced (STORY-APP-014), not closed:** the browser Crafting Profit path now reads every input through one `REPEATABLE READ` snapshot and each account source publishes its rows and completion marker in one transaction, so that path can no longer read part of a refresh under an older marker or mix generations within one calculation, and a failed refresh leaves the previous generation intact. Account writes are also serialized through one coordinator with accurate per-source freshness reporting. What remains is the cross-source question this entry names: a generation is per source, so bank and material storage can still be current as of different instants.


### KP-13 — Removed characters remain eligible and keep phantom inventory (CH-05)


**Observed fact:** synchronization upserts only names returned by the current account response. It deletes stale child rows only for characters processed in that run and returns immediately for an empty name list. It never removes characters absent from the response. Readers have no current-roster/freshness filter.

**Inferred risk:** deleting/renaming a character, or changing the configured account against the same database, leaves obsolete characters, ratings and items selectable/consumable; coordinated plans can use a nonexistent crafter. **Recommendation:** reconcile removed roster members after a successful authoritative roster fetch, preserving failure safety.


### KP-14 — Global/setup synchronization downloads data already available (CH-06)


**Observed fact:** the first recipe pass downloads every recipe batch and already parses its ingredients to collect item IDs. After item synchronization it downloads the identical recipe batches again to persist ingredients. During setup, ItemSync parses each item's `iconUrl` but omits it from the items upsert; the following IconSync queries missing URLs and downloads those item payloads again.

**Inferred risk:** an extra complete recipe-detail pass and, on fresh setup, an extra item-detail pass, with additional network latency/rate-limit exposure and repeated parsing. The FK-required recipe→item→ingredient write order does not require re-fetching the payloads. **Recommendation:** retain/reuse the fetched facts while preserving that ordering. No duration was measured.


### KP-16 — Graph cache has neither freshness validation nor atomic replacement (CH-08)


**Observed fact:** `load()` accepts any existing working-directory cache without checking its stored key, age or source database. Setup updates recipes without rebuilding/invalidation. Rebuild deletes the old file before querying the database and writes JSON directly to the final path; reads/rebuilds are not coordinated. A corrupt existing file is parsed repeatedly rather than treated as rebuildable derived data.

**Inferred risk:** stale recipes after setup/database changes; concurrent navigation can read partial JSON or initiate another rebuild during the deletion gap; failed writes can leave a persistent load failure. **Recommendation:** address cache identity/invalidation and safe publication as one lifecycle issue. File caching itself remains an allowed architectural choice.


### KP-17 — Trading Post 404 responses preserve old usable quotes (CH-09)


**Observed fact:** after a 404 batch or a missing ID in a 206 batch, single-item 404 returns null. `fetchTpBatchParsed` skips that ID, unlike an explicit `TpPrice.noData` quote which writes NULLs. Any existing database quote stays unchanged; readers do not check age or current tradeability.

**Inferred risk:** refresh can report success while an item that no longer has a quote retains an old price used by calculations; missing IDs can also be retried repeatedly without recording the absence. **Recommendation:** distinguish confirmed absence from transient failure and define its persisted quote state.


### KP-22 — Missing icon data hides occupied slots and cannot always be repaired (CH-10)


**Observed fact:** IconSync selects only rows whose stored `icon_path` is NULL/empty: it does not check the existence of already-recorded files. Moving/deleting the icon directory or changing `ICON_CACHE_DIR` leaves these rows excluded from repair. Account sync does not populate metadata/icons for newly seen account-only items; setup's item population is driven by recipe items.

**Inferred risk:** real possessions look absent when metadata/downloads are missing, and rerunning icon sync cannot repair nonempty paths pointing at missing files. **Recommendation:** separate possession display from icon availability and make cache repair account for stale paths.

**Status: resolved.** `STORY-SYNC-004` closed "account sync does not populate metadata for newly seen account-only items": the explicit metadata refresh now discovers referenced ids from the reference families rather than from the `items` table and creates the missing rows, and it was executed against the real user database (see `CURRENT_ARCHITECTURE.md` §5.14 for the behavior and the measured before/after coverage). On the web clients possession display was already separate from icon availability — `STORY-WEB-010`'s `ItemIcon` renders count and identity beside a neutral fallback, and empty slots stay empty — so on that path the symptom this entry describes no longer occurs. **Resolved by removal, not by repair:** the stale-path problem was a property of `syncItemIconsToDisk`, which selected on `icon_path IS NULL OR = ''` and never checked whether a recorded file still existed. `STORY-UI-004` deleted that desktop-only download with `sync.DesktopIconAdoption`, so no production path records or re-reads `items.icon_path` any more and there is no stale-path selection left to repair; the condition it described cannot occur. The browser serves icons from the keyed `assets-v1` cache and repairs a miss on demand, which never consults `icon_path`. The JavaFX Bank/Materials rendering half of this entry went away with those views (`STORY-UI-004`).


---

## 6. HTTP boundary and deployment risk

### KP-24 — The unauthenticated HTTP boundary exposes account reads and write-triggering operations


**Inferred risk:** unauthenticated callers can consume the account's GW2 API rate budget and cause database writes, not only read account-derived data. The task facility's one-unfinished-task-per-operation admission rule limits this to one sync per operation at a time, which bounds the effect but is a concurrency control, not an access control — and since the operation keys are independent by design, an account sync, a global sync and both price-refresh variants can be started concurrently by the same unauthenticated caller. The number of independently startable write operations therefore grows with each trigger added; four is the current count. The unauthenticated *read* surface grew too: a caller reaching the port can now enumerate the account's bank slots and material stacks directly (`STORY-API-007`), where previously it could only obtain crafting results derived from them. No credential is exposed by those routes — they return item ids, quantities and display metadata only.

**Recommendation:** keep the API bound to a local single-user deployment until §20 is decided, and revisit before any non-local deployment or before adding further write-triggering routes. `STORY-API-003` deliberately did not introduce authentication — that is an undecided architecture question (§20), not an implementation detail.

**Status: Open.** Newly recorded by `STORY-API-003`; extends, rather than replaces, `STORY-API-001`'s recorded no-authentication limitation. Re-confirmed and widened by `STORY-API-004` and again by `STORY-API-005`, each of which, like the first, deliberately did not introduce authentication, §20 still being undecided. With Phase 4's sync/refresh routes now complete, this section's recommendation to revisit before further write-triggering routes is addressed to whatever adds the next one; `STORY-API-008` added none, and likewise did not introduce authentication.

---


---

## 7. Incomplete or obsolete implementation

### KP-25 — Orphaned production code remains (CH-E4)

**Observed fact:** `api/Gw2PriceFetch.java` has no production callers since its former caller `Main.java` was deleted. `model/Price.java` is used only by that orphaned helper. `craft.ResolvedNeedMapper#toNode` likewise has no production entry call; the live detail chain uses `RecipeTreeBuilder` instead. The 2026-09-23 production-code audit found no FXML/controller binding, reflective lookup, or service registration for these symbols. The three orphans the JavaFX view deletion created — `application.InitialSetupService`, `application.EctoSalvageService` and `util.CoinUtils` — were themselves removed by `STORY-UI-004`, together with `ecto.EctoSalvageCalculator` and `sync.IconSyncGateway`, so they are no longer part of this problem; the same story then removed the desktop icon download the last of those left without a caller (`sync.IconSync.syncItemIconsToDisk` and `sync.DesktopIconAdoption`), so it is not part of this problem either.

**Inferred risk:** obsolete competing implementations remain available to be mistaken for live behavior or modified without effect.

**Recommendation:** reassess production reachability when this area is next touched and retire confirmed-dead paths, or intentionally reuse the mapper through normal planned work if that is the chosen design.

---

## 8. Agent/runtime workflow risks

These findings concern the repository's agent/runtime implementation. They remain recorded as open problems, but this document does not authorize changing orchestrator flow or agent behavior.

### KP-27 — Planner validation does not fully protect or roll back shared state (CH-18)


**Observed fact:** source protection compares sets of `git status --porcelain -- src` lines, not file contents. An already modified source file can change again without changing that line; Python runtime source is outside this check. The planner has workspace-write access. The rollback snapshot covers Markdown and the planning result, not production source. Finally, result parsing/full validation occur *after* `_run_guarded_planner` has returned: a zero-exit planner that writes an invalid story, malformed result or impermissibly resolved request is marked FAILED without reverting its already-written planning changes.

**Inferred risk:** illegal source edits can escape detection, and rejected planning changes remain visible to later runs/users. Synchronous invocation prevents simultaneous model writers but does not make validation a commit boundary (TARGET_ARCHITECTURE §36). **Recommendation:** separately define content-based protected-state checks and a commit/rollback boundary covering validation; preserve pre-existing user changes.


### KP-28 — All nonzero Claude exits are treated as capacity interruptions (CH-19) (resolved)

**Resolved 2026-10-10:** `run_claude_attempt()` classifies each non-zero exit from its output tail, and the pipeline's `implement` step double-checks `/usage`. Only a real capacity signal pauses the story; other failures count against `MAX_CLAUDE_FAILED_RUNS_PER_STORY` and then create a user intervention (`workflow_service/README.md`). The original finding is kept below for history.

**Observed fact (original):** the runner returns only the process exit code. Every nonzero exit sets the story UNFINISHED, defers capacity and loops back to the same prompt, without classifying usage exhaustion versus authentication/CLI/configuration/process failure. This path never consumes the normal retry limit or creates an intervention.

**Inferred risk:** a deterministic invocation failure becomes an indefinite hourly retry cycle masquerading as quota exhaustion, withholding actionable failure handling. **Recommendation:** preserve quota-resume behavior while distinguishing capacity events from terminal/tooling errors. No Claude command was invoked in this audit.


---

## 9. Platform and performance follow-up

### KP-30 — Full-catalog work persists for narrow crafting scopes (CH-21; follow-up required)


**Observed fact:** each reload parses the entire graph cache, constructs its output index, collects all graph item IDs and loads all those items/quotes. The planner constructs another output index and evaluates every global recipe before the caller keeps visible rows. Even a selected discipline uses an unrestricted ingredient-table load for its recipe query. These paths were traced, not inferred from method names.

**Inferred risk requiring measurement:** avoidable graph/collection construction and forbidden-root simulation may materially affect narrow scopes and rapid repeated reloads; transitive ingredient/valuation needs mean simply restricting everything to visible rows would be unsafe. **Recommendation:** profile this concrete chain only if pursued, separating necessary dependency closure from redundant roots/indexes. This is not a claim that the accepted seven-second Profit gate has regressed, nor authorization to accelerate the deferred Discovery performance work.


---

## 10. Open domain/UX clarification

### KP-31 - Discovery had two independent character authorities (resolved)

**Resolved:** the Discovery API now accepts only its character/discipline scope and settings. The selected character supplies both candidate eligibility and binding-aware inventory; the page has one selector and no inventory-character fallback. The regression is covered by application, API and frontend tests.

### KP-32 — Global recipe change detection misses edits under unchanged IDs

**Observed fact:** `RecipeSync.syncAllRecipesGlobalSafe()` compares the remote recipe ID set with the local `recipes.recipe_id` set and returns without detail requests when the sets match. Recipe detail fields and ingredients are therefore not checked for in-place changes unless some recipe ID is also added or removed.

**Inferred risk:** an ArenaNet edit to an existing recipe's output, discipline, or ingredients can remain stale locally, and the crafting graph will not rebuild for that edit. The current six-hour job detects catalog membership changes, not full recipe freshness.

**Recommendation:** retain the cheap ID-list gate while a future decision defines an affordable way to detect in-place detail changes; do not describe the current check as detecting every upstream recipe-data edit.
