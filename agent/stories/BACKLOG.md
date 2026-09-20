# Story Backlog Index

Index only. No story content is duplicated here — each entry links to its canonical file under `agent/stories/`, which is the single source of truth for that story's ID, title, status, goal, acceptance criteria, references, definition of done, and result/blockers.

The currently active story's file is the one pointed to by `agent/CURRENT_STORY.md`.

## Active

- `STORY-UI-001-javafx-verification-capability.md` - milestone-01; establish repeatable JavaFX verification for Phase 1 crafting views.

## To Do

- `STORY-DOM-013-expose-blocked-crafting-rows.md` — milestone-01; preserves unavailable/blocked results and their reasons in Crafting Profit and Discovery, closing the remaining controller filtering conflict in `docs/KNOWN_PROBLEMS.md` §3.5.
- `STORY-DOM-014-coordinate-all-characters-crafting.md` — milestone-01; implements resolved coordinated account planning for Crafting Profit.

## Blocked

- `STORY-DOM-015-preserve-refresh-state-and-verify-character-results.md` — milestone-01; individual-character selection tracing and refresh-preservation verified with no defect found (see Result); blocked on `STORY-DOM-014` (TODO) to deliver Crafting Profit's resolved `All characters` selector default before this story's remaining acceptance criterion can be met. Tracked as `docs/KNOWN_PROBLEMS.md` §3.7.

## Done

- `STORY-DOM-012-selected-character-ui-wiring.md` — added a character selector (`ComboBox<String>`, populated via `CharacterRepository.loadAllCharacterNames()`) to `CraftingProfitView`/`CraftingDiscoveryView`, defaulting to the first synced character; wired `CraftingProfitController`/`CraftingDiscoveryController` to call `InventoryRepository.loadOwnedInventoryForCharacter(...)` (falling back to unfiltered `loadOwnedInventory()` when no character is synced) and threaded the resulting sellable/bound split through a new `CraftingPlanner.evaluateAll(...)` overload into `PlanState`. Closes `docs/KNOWN_PROBLEMS.md` §3.4 and `docs/ROADMAP.md` Phase 1's bound-material item.
- `STORY-TEST-008-recipe-sync-write-path-integration-test.md` — first Layer 2 PostgreSQL integration test for `sync.RecipeSync`'s `recipes`/`recipe_ingredients` upsert write path, the write-side counterpart to `STORY-TEST-004`'s already-tested `RecipeRepository` read side. Extracted package-private static `upsertRecipes`/`upsertRecipeIngredients` seams (mirroring `STORY-TEST-006`/`STORY-TEST-007`'s precedent) — no SQL or upsert semantics changed. `docs/ROADMAP.md` §4.

## Archived



### Milestone 00

- `STORY-TEST-007-account-sync-write-path-integration-test.md` — first Layer 2 PostgreSQL integration test for `sync.AccountSync`'s `account_bank`/`account_materials`/`account_recipes` upsert + stale-row-deletion write paths, the write-side counterpart to the already-tested `InventoryRepository` owned-pool reads (`STORY-TEST-002`/`STORY-DOM-011`) and `RecipeRepository`'s unlock-CTE read (`STORY-TEST-004`). Extracted package-private static `upsertAccountBank`/`upsertAccountMaterials`/`upsertAccountRecipes` seams (each doing upsert + stale-delete, mirroring `STORY-TEST-003`'s `CharacterSync` precedent) — no SQL or upsert semantics changed. `docs/ROADMAP.md` §4.
- `STORY-TEST-006-tp-sync-write-path-integration-test.md` — first Layer 2 PostgreSQL integration test for `sync.TpSync`'s `tp_prices` upsert write path: proves a `TpPrice` with market data upserts all four numeric columns with their given non-null values, a `TpPrice` with no market data upserts an all-NULL row (not zero-coerced), and re-upserting the same `item_id` updates in place. Extracted a package-private static `upsertTpPrices(Connection, List<TpPrice>, Timestamp)` seam from `syncTpPrices`, mirroring `STORY-TEST-003`'s `CharacterSync` write-path precedent — no SQL or upsert semantics changed. Write-side counterpart to `STORY-TEST-005`, closing the `tp_prices` NULL-price pipeline invariant end-to-end. `docs/TEST_STRATEGY.md` §6.11/§9/§31.2, `docs/ROADMAP.md` §4.
- `STORY-TEST-005-tp-price-repository-integration-test.md` — first Layer 2 PostgreSQL integration test for `repo.tp.TpPriceRepository.loadTpQuotes(...)`, proving NULL buy/sell prices are preserved (never coerced to zero), missing items are simply absent from the result, and batched lookups are correctly keyed. Added a `Connection`-parameter overload as a test seam, matching `InventoryRepository`/`RecipeRepository`'s existing precedent, plus widened `EnvConfig` to public — no SQL or existing signature changed. `docs/TEST_STRATEGY.md` §6.11/§31.2, `docs/ROADMAP.md` §4.
- `STORY-TEST-004-recipe-repository-integration-test.md` — first Layer 2 PostgreSQL integration test for `repo.RecipeRepository` (unlock-CTE, discipline filter, ingredient mapping, NULL/empty disciplines handling) — the repository whose `Recipe`/`Ingredient` types are reused directly as the crafting domain's working data model. Added minimal `Connection`-parameter overloads (`loadRecipes`, `loadRecipesForCharacter`, `loadAllRecipes`) as a test seam, matching `InventoryRepository`'s existing precedent — no SQL or existing signature changed. `docs/TEST_STRATEGY.md` §31.2, `docs/ROADMAP.md` §4.
- `STORY-TEST-003-character-sync-repository-test.md` — first Layer 2 PostgreSQL integration test for `sync.CharacterSync`'s write logic: `upsertCharacter`, `replaceCharacterCrafting`, and `replaceCharacterItems`, each proving insert / upsert-on-conflict / stale-row-deletion against real Postgres on a disposable schema. Closes the "critical repositories/sync flows... verified against real DB schema/constraints/upsert-delete semantics" Phase 0 exit criterion. `docs/TEST_STRATEGY.md` §31.2, `docs/ROADMAP.md` §4.
- `STORY-SYNC-003-live-api-smoke-tests.md` — first Layer 4 optional live GW2 API smoke tests: `api.Gw2ApiLiveSmokeIT` (item detail, recipe detail, TP price lookups against public endpoints), named with an "IT" suffix so Surefire's default `test` goal never selects it; run on demand via `./mvnw test -Dtest=Gw2ApiLiveSmokeIT`. Closes the last unchecked Phase 0 exit criterion. `docs/TEST_STRATEGY.md` §31.4, `docs/ROADMAP.md` §4.
- `STORY-DOM-011-bound-material-domain-rule.md` — implemented the `DOMAIN_SPEC.md` §11.1/DQ-007 soulbound/account-bound material rule in the repo + domain layers using the RESOLVED `UD-001` selected-character design: `InventoryRepository.loadOwnedInventoryForCharacter(...)` (new), `PlanState.boundInventory`/`consumeInventoryWithBinding(...)` (new), `CraftingResolver.resolveNeed(...)` now skips TP opportunity cost on the bound portion. Purely additive - no production controller call site changed. `docs/KNOWN_PROBLEMS.md` §3.4.
- `STORY-DOM-010-ectoplasm-salvage-fee-model-resolution.md` — applied the RESOLVED `UD-002` decision: `EctoView` shows a one-time TP-fee notice, the disconnected legacy `Main.java` was deleted, and `docs/DOMAIN_SPEC.md` gained `DQ-011` (with §46/§47 reconciled to it). `docs/KNOWN_PROBLEMS.md` §3.6.
- `STORY-INFRA-002-crafting-graph-cache-auto-rebuild.md` — `CraftingGraphCache.load()` now auto-rebuilds when the cache file is missing; `crafting_graph_cache.json` untracked from git and gitignored. `docs/KNOWN_PROBLEMS.md` §7.5.
- `STORY-SYNC-002-recipe-parser-fixture-test.md` — first Layer 3 (captured real GW2 API payload) parser test, covering `parser.RecipeParser`; closes the previously fully-uncovered Phase 0 exit criterion for parser/sync fixture testing. `docs/TEST_STRATEGY.md` §31.3, `docs/ROADMAP.md` §4.
- `STORY-DOM-008-recipe-not-allowed-and-budget-blocked-reasons.md` — added the remaining missing `BlockedReason` values `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET`, completing `docs/DOMAIN_SPEC.md` §42's set. `docs/KNOWN_PROBLEMS.md` §3.5.
- `STORY-DOM-009-heuristic-skip-characterization-test.md` — characterization test for the direct-price heuristic that may skip simulating a recipe under `useOwnMats=false`/`allowBuying=false` even when recursive crafting would be cheaper. `docs/KNOWN_PROBLEMS.md` §7.4.
- `STORY-TEST-002-owned-pool-character-inventory-repository-test.md` — added the project's first Layer 2 PostgreSQL integration test, automating the §3.3 owned-pool intended-behavior check against a disposable schema on the local Postgres server (no Testcontainers/Docker needed). `docs/KNOWN_PROBLEMS.md` §3.3, `docs/ROADMAP.md` §4 exit criterion 3.
- `STORY-TEST-001-shared-domain-fixture.md`
- `STORY-DOM-001-multiple-recipe-selection-test.md`
- `STORY-DOM-003-price-unavailable-blocked-reason.md`
- `STORY-DOM-002-multiple-recipe-selection-fix.md`
- `STORY-DOM-005-remove-dead-legacy-craft-planner-code.md`
- `STORY-DOM-004-owned-material-pool-character-inventories.md` — fix has no observable effect yet; still depends on `STORY-SYNC-001` to populate real data.
- `STORY-SYNC-001-character-inventory-sync.md` — syncs character bag/equipment items (incl. binding/bound_to) into `character_items`; `STORY-DOM-004`'s owned-pool fix now has real data to act on.
- `STORY-DOM-007-bound-material-characterization-test.md` — characterization test documenting that bound/soulbound materials are consumed exactly like ordinary owned inventory today, with no rejection or distinct `BlockedReason`.
- `STORY-DOM-006-remove-debug-instrumentation.md` — removed the hardcoded debug `System.out.println` in `CraftingPlanner.evaluateOneRecipeNew`. `docs/KNOWN_PROBLEMS.md` §7.2.
- `STORY-INFRA-001-icon-cache-path-config.md` — replaced the hardcoded Windows-specific icon-cache path with an `ICON_CACHE_DIR` env var (portable default), reusing `EnvConfig`'s pattern via a new `optional(...)` method. `docs/KNOWN_PROBLEMS.md` §7.3.

## Not Yet Written (no file exists)

_(none — the two former entries here, bound/soulbound material handling and the Ectoplasm Salvage
fee-model ambiguity, are now covered by `STORY-DOM-010`/`STORY-DOM-011`/`STORY-DOM-012` above, since
`UD-001` and `UD-002` are now both RESOLVED.)_

## Numbering Convention

`STORY-<AREA>-<NUMBER>-short-name.md`. Numbers are sequential **per area**, not global.

Area codes in use: `DOM` (domain-rule stabilization), `TEST` (test infrastructure not tied to one domain rule), `SYNC` (GW2 API / database sync infrastructure), `INFRA` (general technical-debt/infrastructure cleanup not tied to one domain rule or sync flow). New areas start their own counter at 001.

## Archiving

Once a roadmap milestone (`docs/ROADMAP.md` Phase N) is confirmed complete, its DONE stories move from `agent/stories/` into `agent/stories/archive/milestone-NN/` (never deleted) and their entries move here from "Done" to "Archived" under that milestone's own `### Milestone N` heading. This only happens as a deterministic side effect of a confirmed milestone transition during PROJECT PLANNING MODE (see `agent/PLANNER_INSTRUCTIONS.md`), never when an individual story reaches DONE. Every current story is tagged `milestone-00` (Phase 0, the only phase this project has had so far).
- `STORY-TEST-010-character-item-sync-integration-test.md`