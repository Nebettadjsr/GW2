# GW2 Tool — Known Problems

## 1. Purpose and maintenance rule

This document is the **current open-problem register**. Findings explicitly limited to JavaFX refer to the legacy desktop interface and should not be read as browser behavior. for defects, architectural risks, ambiguities, technical debt, and incomplete functionality that still apply to the repository.

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

### KP-08 — Legacy JavaFX shopping-list prices follow the sell toggle (CH-13)


**Observed fact (legacy JavaFX):** both JavaFX shopping lists read `rbListingSell` and call `itemSellUnit` when pricing items to buy. The browser shopping list displays the selected acquisition price and backend purchase total. The engine uses `settings.listingBuy` instead. With both modes set to instant, the list displays the buy-order bid while the planner purchases at the sell-offer ask; changing the output sell mode changes displayed acquisition prices.

**Inferred risk:** the purchase breakdown disagrees with required cash/budget despite identical quantities (DOMAIN_SPEC §20). **Recommendation:** render purchase-side quotes from the same calculation snapshot/mode as the plan.


### KP-09 — Legacy JavaFX monetary presentation loses signs and mislabels total purchase cost (CH-14)


**Observed fact (legacy JavaFX):** `formatSigned` calls `format(Math.abs(copper))`, stripping the minus sign. Ecto profit cells have the same white styling for positive/negative values; crafting tables use color but also lose the textual sign. Profit's Buy Cost tooltip says “ONE craft” while the supplied `CraftResult.buyCostCopper` is total plan cost. Discovery likewise displays that total in its table but uses `missingToBuyOne` in the detail list.

**Inferred risk:** losses appear as positive Ecto profits, and readers cannot reconcile table cash costs with the single-craft explanation. **Recommendation:** correct presentation against existing domain values/units, without inventing a new economic model.

**Partly resolved (`STORY-DOM-024`).** The Ectoplasm half is fixed at the call site, not in `CoinUtils`: `EctoView.fillProfitGrid` now formats its four cells with `CoinUtils.format`, which keeps the minus sign, so a salvage loss reads as a loss (`EctoSalvageViewIT` asserts the rendered `-0g 8s 73c`). `formatSigned` itself is unchanged and still strips the sign, and the crafting-table styling and the two Buy Cost labelling problems above are untouched.

**Disposition (2026-10-03): resolved by removal, not by repair.** The story that carried the remaining JavaFX half (`STORY-UI-003`) is SUPERSEDED: the Product Owner declared the legacy JavaFX UI obsolete and removable, and `docs/ROADMAP.md` §2 now permits that removal from Phase 5 onward. The defect below is still present in the legacy views - `CoinUtils.formatSigned` still strips the sign, and `CraftingProfitView` still contains a signed/unsigned conditional whose branches are identical - and it is retained here as legacy-interface debt until those views are deleted. The browser presentation is unaffected. Do not open new JavaFX-only presentation work against this entry.


### KP-11 — Legacy JavaFX `MaxBuy UI=...` debug text still appears transiently

**Observed fact (legacy JavaFX):** `CraftingProfitView.reloadTable` still writes a transient `"MaxBuy UI=..."` developer/debug string to the shared status label at the start of reload. The separate loaded-row/counter diagnostic previously tracked here was removed by `STORY-UI-002`; this remaining text was explicitly outside that story's scope.

**Inferred risk:** implementation/debug state leaks into normal user-facing presentation.

**Recommendation:** remove the transient debug presentation while preserving genuine user-facing status/error messages.


---

## 4. Synchronization, persistence, cache, and data-freshness risks

### KP-12 — Legacy JavaFX account refresh can publish inconsistent inventory snapshots (CH-04)


**Observed fact:** bank/material/character steps fetch and commit independently; inventory reads use separate statements without a common snapshot transaction. Each home sync button disables only itself, so setup/global/account jobs can overlap, including with a navigated view's auto-refresh. The legacy JavaFX Profit timer refreshes materials and recipes only but reports “Auto-refreshed Bank + Materials”; the calculation still reads stored bank and character inventory.

**Inferred risk:** moving a stack from bank to material storage can leave its old bank quantity alongside its refreshed storage quantity in Profit indefinitely until a full account refresh; even full refresh has intermediate mixed snapshots. Concurrent runs can publish older fetches after newer ones. This is an application-created consistency problem, distinct from accepted GW2 API lag (DOMAIN_SPEC §39). **Recommendation:** define coherent account snapshot publication/refresh ownership and accurate freshness reporting; preserve or explicitly decide refresh scope.


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


### KP-19 — Legacy JavaFX crafting reloads race on shared result state (CH-01)


**Observed fact:** every trigger starts a new thread against the same controller/service. There is no cancellation, generation check or serialized publication. Settings controls are read from those workers, rather than captured together on the JavaFX thread. Budget typing can launch a job for each edit. Discovery now uses one combined character/discipline scope; settings and refresh controls can still launch overlapping JavaFX reloads. Service caches are assigned field by field, while the FX row-selection handler also reads them and replaces the result map during lazy enrichment.

**Inferred risk:** redundant database/parallel-planner work, older results overwriting newer selections, mixed settings/caches, and row details belonging to a different reload. This remains after the specific Profit initial-load fix. **Recommendation:** address request ownership, FX input snapshots and coherent result publication together.


### KP-20 — Legacy JavaFX crafting schedulers outlive window closure (CH-02)


**Observed fact:** both views create `Executors.newSingleThreadScheduledExecutor()` with the default non-daemon thread factory. Shutdown occurs only on Back or reopening that same view; `Gw2App` has no `stop()`/window-close cleanup. Reload and TP threads are not tracked or cancelled on departure, and their queued `runLater` success callbacks can still reload the detached view. The static `autoRefreshTask` retains its captured view/controller even after Back.

**Inferred risk:** closing the application from a crafting page can leave the JVM and periodic account synchronization running; leaving a page does not reliably end its work. **Recommendation:** tie scheduler/jobs/callback validity to the view/application lifecycle. No shutdown experiment was run.


### KP-21 — Legacy JavaFX Bank/Materials navigation blocks the UI; Materials conceals load failure (CH-03)


**Observed fact:** navigation calls the view synchronously; before setting the new scene, the view calls its application service through to JDBC connection/query/result loading on the JavaFX Application Thread. The extraction changed ownership, not execution timing. Materials catches the load exception, prints it, and returns `List.of()`, presenting the same empty page as genuinely empty storage.

**Inferred risk:** database connection/query delays freeze navigation and all UI interaction; a storage-load failure looks like missing possessions. **Recommendation:** separately handle background loading and an explicit Materials failure state. This does not reopen the resolved hardcoded-connection issue in §2.2.


### KP-22 — Missing icon data hides occupied slots and cannot always be repaired (CH-10)


**Observed fact:** Bank renders a null `iconPath` using the exact empty-slot branch before displaying count/item identity; Materials likewise returns an empty-looking tile before count rendering. IconSync selects only rows whose stored `icon_path` is NULL/empty: it does not check the existence of already-recorded files. Moving/deleting the icon directory or changing `ICON_CACHE_DIR` leaves these rows excluded from repair. Account sync does not populate metadata/icons for newly seen account-only items; setup's item population is driven by recipe items.

**Inferred risk:** real possessions look absent when metadata/downloads are missing, and rerunning icon sync cannot repair nonempty paths pointing at missing files. **Recommendation:** separate possession display from icon availability and make cache repair account for stale paths.

**Status: partially resolved — the metadata half only.** `STORY-SYNC-004` closed "account sync does not populate metadata for newly seen account-only items": the explicit metadata refresh now discovers referenced ids from the reference families rather than from the `items` table and creates the missing rows, and it was executed against the real user database (see `CURRENT_ARCHITECTURE.md` §5.14 for the behavior and the measured before/after coverage). On the web clients possession display was already separate from icon availability — `STORY-WEB-010`'s `ItemIcon` renders count and identity beside a neutral fallback, and empty slots stay empty — so on that path the symptom this entry describes no longer occurs. **Still open, and deliberately not claimed resolved:** the JavaFX Bank/Materials rendering that treats a null `icon_path` as the empty-slot branch, and the stale-path problem — `syncItemIconsToDisk` still selects on `icon_path IS NULL OR = ''` and does not check whether a recorded file still exists, so a moved or deleted icon directory still leaves those rows out of repair. No evidence was gathered for either; `STORY-SYNC-004` changed neither.


### KP-23 — Legacy JavaFX optional Ecto icons can suppress a successful calculation (CH-11)


**Observed fact:** the worker calculates price scenarios first, then synchronously requests icon metadata before publishing any values. Non-200 icon responses are tolerated, but an IOException, interrupted send or malformed icon response escapes to the shared catch, which reports “Failed to load prices.” The already successful scenarios are discarded. The metadata request has no configured timeout.

**Inferred risk:** a presentation-only failure/delay leaves the price/profit page empty or loading despite valid prices. **Recommendation:** independently publish calculation success and handle optional icon loading/failure.


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

**Observed fact:** `api/Gw2PriceFetch.java` has no production callers since its former caller `Main.java` was deleted. `model/Price.java` is used only by that orphaned helper. `CraftingDiscoveryView#buildSearchBlob` has no production entry call; the live workflow uses `CraftingDiscoveryController.prepareRows -> buildSearchBlob`. `craft.ResolvedNeedMapper#toNode` likewise has no production entry call; the live detail chain uses `RecipeTreeBuilder` instead. The browser-owned Ectoplasm screen also leaves `frontend/src/api/ectoApi.ts` and `frontend/src/ecto/useEctoSalvage.ts` unreferenced after the backend calculation route was removed. The 2026-09-23 production-code audit found no FXML/controller binding, reflective lookup, or service registration for these symbols.

**Inferred risk:** obsolete competing implementations remain available to be mistaken for live behavior or modified without effect.

**Recommendation:** reassess production reachability when this area is next touched and retire confirmed-dead paths, or intentionally reuse the mapper through normal planned work if that is the chosen design.

### KP-26 — Legacy JavaFX API-key Save control is a placeholder

**Observed fact:** the API-key `TextField` / `Save` button in `Gw2App.createHomeScene()` only updates a status label; it does not persist the entered key. `AppConfig.API_KEY` remains the actual source of the key.

**Recommendation:** either implement the intended configuration flow through normal planning or remove/replace the misleading control.


---

## 8. Agent/runtime workflow risks

These findings concern the repository's agent/runtime implementation. They remain recorded as open problems, but this document does not authorize changing orchestrator flow or agent behavior.

### KP-27 — Planner validation does not fully protect or roll back shared state (CH-18)


**Observed fact:** source protection compares sets of `git status --porcelain -- src` lines, not file contents. An already modified source file can change again without changing that line; Python runtime source is outside this check. The planner has workspace-write access. The rollback snapshot covers Markdown and the planning result, not production source. Finally, result parsing/full validation occur *after* `_run_guarded_planner` has returned: a zero-exit planner that writes an invalid story, malformed result or impermissibly resolved request is marked FAILED without reverting its already-written planning changes.

**Inferred risk:** illegal source edits can escape detection, and rejected planning changes remain visible to later runs/users. Synchronous invocation prevents simultaneous model writers but does not make validation a commit boundary (TARGET_ARCHITECTURE §36). **Recommendation:** separately define content-based protected-state checks and a commit/rollback boundary covering validation; preserve pre-existing user changes.


### KP-28 — All nonzero Claude exits are treated as capacity interruptions (CH-19) (resolved)

**Resolved 2026-10-10:** `run_claude_attempt()` classifies each non-zero exit from its output tail, and the pipeline's `implement` step double-checks `/usage`. Only a real capacity signal pauses the story; other failures count against `MAX_CLAUDE_FAILED_RUNS_PER_STORY` and then create a user intervention (`local_bridge/README.md`). The original finding is kept below for history.

**Observed fact (original):** the runner returns only the process exit code. Every nonzero exit sets the story UNFINISHED, defers capacity and loops back to the same prompt, without classifying usage exhaustion versus authentication/CLI/configuration/process failure. This path never consumes the normal retry limit or creates an intervention.

**Inferred risk:** a deterministic invocation failure becomes an indefinite hourly retry cycle masquerading as quota exhaustion, withholding actionable failure handling. **Recommendation:** preserve quota-resume behavior while distinguishing capacity events from terminal/tooling errors. No Claude command was invoked in this audit.


---

## 9. Platform and performance follow-up

### KP-29 — Startup stylesheet lookup has the wrong resource case (CH-20)


**Observed fact:** startup looks up `/styles/dark-scroll.css` and immediately applies `Objects.requireNonNull`, while the tracked resource directory is `Styles`. No resource-renaming configuration was found. Windows directory execution can mask this difference; a case-sensitive classpath/JAR lookup does not.

**Inferred risk:** startup fails at stylesheet loading when resources are resolved case-sensitively. **Recommendation:** verify the packaged resource lookup and align its case in separately authorized work. No packaging/build execution was performed.


### KP-30 — Full-catalog work persists for narrow crafting scopes (CH-21; follow-up required)


**Observed fact:** each reload parses the entire graph cache, constructs its output index, collects all graph item IDs and loads all those items/quotes. The planner constructs another output index and evaluates every global recipe before the controller keeps visible rows; the controller builds another index for search text. Even a selected discipline uses an unrestricted ingredient-table load for its recipe query. These paths were traced, not inferred from method names.

**Inferred risk requiring measurement:** avoidable graph/collection construction and forbidden-root simulation may materially affect narrow scopes and rapid repeated reloads; transitive ingredient/valuation needs mean simply restricting everything to visible rows would be unsafe. **Recommendation:** profile this concrete chain only if pursued, separating necessary dependency closure from redundant roots/indexes. This is not a claim that the accepted seven-second Profit gate has regressed, nor authorization to accelerate the deferred Discovery performance work.


---

## 10. Open domain/UX clarification

### KP-31 - Discovery had two independent character authorities (resolved)

**Resolved:** the Discovery API now accepts only its character/discipline scope and settings. The selected character supplies both candidate eligibility and binding-aware inventory; the page has one selector and no inventory-character fallback. The regression is covered by application, API and frontend tests.

### KP-32 — Global recipe change detection misses edits under unchanged IDs

**Observed fact:** `RecipeSync.syncAllRecipesGlobalSafe()` compares the remote recipe ID set with the local `recipes.recipe_id` set and returns without detail requests when the sets match. Recipe detail fields and ingredients are therefore not checked for in-place changes unless some recipe ID is also added or removed.

**Inferred risk:** an ArenaNet edit to an existing recipe's output, discipline, or ingredients can remain stale locally, and the crafting graph will not rebuild for that edit. The current six-hour job detects catalog membership changes, not full recipe freshness.

**Recommendation:** retain the cheap ID-list gate while a future decision defines an affordable way to detect in-place detail changes; do not describe the current check as detecting every upstream recipe-data edit.
