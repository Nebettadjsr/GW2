# Story Backlog Index

Index only. Each entry points to its canonical story file under `agent/stories/`, which owns the story's status, goal, acceptance criteria, references, definition of done, and result/blockers.

The active story is the file pointed to by `agent/CURRENT_STORY.md`. `## Active` holds at most one
entry and is empty whenever no story is active; the harness fills it deterministically when it
activates a story.

Entry format is machine-read, not decorative. `agent/runtime/core/story_state.py` parses each row as
`- STORY-ID | <filename>.md | STATUS | milestone-NN | deps: …`, with the filename bare. A markdown
link in the filename field makes the row invisible to story selection, so rows in the queue sections
below stay in the plain form.

## Active


## To Do
- STORY-WEB-030 | STORY-WEB-030-align-layout-discovery-smoke-contracts.md | TODO | milestone-05 | deps: None

## Blocked
- STORY-QUALITY-005 | STORY-QUALITY-005-phase-five-completion-review.md | BLOCKED | milestone-05 | deps: STORY-PERF-002, STORY-WEB-019, STORY-SYNC-005, STORY-WEB-018, STORY-WEB-020, STORY-WEB-021, STORY-WEB-022, STORY-WEB-027, STORY-WEB-028

## Superseded

- STORY-WEB-023 | STORY-WEB-023-backend-ecto-calculation-screen.md | SUPERSEDED | milestone-05 | deps: None
- STORY-UI-003 | STORY-UI-003-signed-monetary-presentation.md | SUPERSEDED | milestone-05 | deps: None
- STORY-WEB-025 | STORY-WEB-025-backend-total-sell-value-display.md | SUPERSEDED | milestone-05 | deps: None
- STORY-WEB-026 | STORY-WEB-026-account-smoke-bank-reload-contract.md | SUPERSEDED | milestone-05 | deps: None

## Done

Completed current-milestone story details remain in their canonical files.

- STORY-API-006 | STORY-API-006-crafting-selector-options.md | DONE | milestone-05 | Expose crafting selector options for the browser UI
- STORY-API-007 | STORY-API-007-bank-materials-read-endpoints.md | DONE | milestone-05 | Expose bank and material storage reads for the web frontend
- STORY-API-008 | STORY-API-008-crafting-resolution-endpoints.md | DONE | milestone-05 | Expose request-local crafting resolution through the decided HTTP contract
- STORY-API-009 | STORY-API-009-web-item-icon-metadata.md | DONE | milestone-05 | Expose item icon metadata and persistent backend image delivery
- STORY-APP-011 | STORY-APP-011-display-authoritative-total-profit.md | DONE | milestone-03 | Display and sort by the authoritative crafting total profit
- STORY-APP-012 | STORY-APP-012-request-local-resolution-detail.md | DONE | milestone-05 | Coordinate resolution detail from one request-local set of calculation inputs
- STORY-APP-013 | STORY-APP-013-carry-authoritative-total-sell-value.md | DONE | milestone-05 | Carry authoritative total sell value into the JavaFX Profit view
- STORY-DOM-020 | STORY-DOM-020-semantic-resolution-trace.md | DONE | milestone-05 | Produce a semantic single-craft trace from authoritative resolution
- STORY-DOM-021 | STORY-DOM-021-profit-non-tp-material-control.md | DONE | milestone-05 | Implement the decided Profit non-Trading-Post material calculation option
- STORY-DOM-022 | STORY-DOM-022-trading-post-sale-fee-calculation.md | DONE | milestone-05 | Establish a shared copper-accurate Trading Post sale fee calculation
- STORY-DOM-023 | STORY-DOM-023-crafting-profit-fee-integration.md | DONE | milestone-05 | Apply the decided Trading Post fee model to crafting profits and presentation
- STORY-DOM-024 | STORY-DOM-024-ectoplasm-fee-policy-alignment.md | DONE | milestone-05 | Align Ectoplasm expected-value economics and labels with the decided fee policy
- STORY-DOM-025 | STORY-DOM-025-remove-stale-cost-evaluator-profit.md | DONE | milestone-05 | Remove the stale pre-fee profit calculation from CostEvaluator
- STORY-PERF-002 | STORY-PERF-002-phase-five-crafting-profit-page-budget.md | DONE | milestone-05 | Verify Phase 5 Crafting Profit page performance on the real user database
- STORY-QUALITY-002 | STORY-QUALITY-002-phase-two-completion-review.md | DONE | milestone-02 | Review Phase 2 project health before milestone completion
- STORY-QUALITY-004 | STORY-QUALITY-004-phase-four-completion-review.md | DONE | milestone-04 | Review Phase 4 project health before milestone completion
- STORY-SYNC-004 | STORY-SYNC-004-complete-referenced-item-metadata.md | DONE | milestone-05 | Repair referenced-item metadata gaps for real item icons
- STORY-SYNC-005 | STORY-SYNC-005-stabilize-bank-reload-smoke-check.md | DONE | milestone-05 | Stabilize the Bank reload browser smoke check (satisfied by the maintainer implementation in commit `0fcdbb4`)
- STORY-TEST-008 | STORY-TEST-008-recipe-sync-write-path-integration-test.md | DONE | milestone-00 | Layer 2 PostgreSQL integration test: `sync.RecipeSync`'s `recipes`/`recipe_ingredients` upsert write path
- STORY-TEST-009 | STORY-TEST-009-character-inventory-api-fixture.md | DONE | milestone-01 | Verify character inventory and binding parsing with captured GW2 API fixtures
- STORY-WEB-001 | STORY-WEB-001-crafting-profit-table.md | DONE | milestone-05 | Build the Vue Crafting Profit table against the backend API
- STORY-WEB-002 | STORY-WEB-002-sync-task-controls.md | DONE | milestone-05 | Add browser synchronization controls and backend task status
- STORY-WEB-003 | STORY-WEB-003-bank-materials-views.md | DONE | milestone-05 | Render bank and material storage in the browser
- STORY-WEB-004 | STORY-WEB-004-application-navigation-layout.md | DONE | milestone-05 | Restructure the existing frontend around application navigation and shared layouts
- STORY-WEB-005 | STORY-WEB-005-crafting-profit-information-hierarchy.md | DONE | milestone-05 | Separate Crafting Profit comparison results, calculation controls and selected details
- STORY-WEB-006 | STORY-WEB-006-profit-result-display-controls.md | DONE | milestone-05 | Add Crafting Profit display filters and whole-row selection
- STORY-WEB-007 | STORY-WEB-007-profit-resolution-detail-view.md | DONE | milestone-05 | Render authoritative Crafting Profit resolution in sticky selected-result details
- STORY-WEB-008 | STORY-WEB-008-profit-economic-columns.md | DONE | milestone-05 | Expose backend total sell value and complete Profit comparison columns
- STORY-WEB-009 | STORY-WEB-009-replaceable-favicon.md | DONE | milestone-05 | Add replaceable browser favicon support
- STORY-WEB-010 | STORY-WEB-010-shared-item-icon-presentation.md | DONE | milestone-05 | Render backend-supplied icons through one reusable frontend component
- STORY-WEB-011 | STORY-WEB-011-profit-controls-text-cleanup.md | DONE | milestone-05 | Complete Crafting Profit control grouping and concise presentation
- STORY-WEB-012 | STORY-WEB-012-crafting-discovery-page.md | DONE | milestone-05 | Build the Crafting Discovery browser workflow over existing APIs
- STORY-WEB-013 | STORY-WEB-013-ectoplasm-salvage-page.md | DONE | milestone-05 | Expose the existing Ectoplasm calculation through HTTP and a browser screen
- STORY-WEB-014 | STORY-WEB-014-profit-live-contract-repairs.md | DONE | milestone-05 | Repair reported Profit value, resolution and calculation-control integration failures
- STORY-WEB-015 | STORY-WEB-015-profit-purchase-and-blocking-details.md | DONE | milestone-05 | Simplify Profit purchase details and identify concrete blocking materials
- STORY-WEB-016 | STORY-WEB-016-compact-crafting-resolution-tree.md | DONE | milestone-05 | Make Crafting Resolution compact with collapsed ingredient groups
- STORY-WEB-017 | STORY-WEB-017-layout-smoke-intro-contrast-sample.md | DONE | milestone-05 | Keep browser layout smoke contrast sampling valid after Profit intro removal
- STORY-WEB-019 | STORY-WEB-019-layout-smoke-navigation-coverage.md | DONE | milestone-05 | Align layout browser smoke navigation coverage with current destinations
- STORY-WEB-022 | STORY-WEB-022-align-ecto-page-label.md | DONE | milestone-05 | Align the Ecto Salvage page label and browser checks
- STORY-WEB-018 | STORY-WEB-018-fresh-build-browser-smoke-checks.md | DONE | milestone-05 | Reject stale frontend bundles in browser smoke checks
- STORY-WEB-024 | STORY-WEB-024-repair-fresh-build-smoke-contracts.md | DONE | milestone-05 | Repair browser smoke assertions against current workflow contracts
- STORY-WEB-027 | STORY-WEB-027-remove-tracked-smoke-control-copy.md | DONE | milestone-05 | Remove the tracked temporary account smoke control copy
- STORY-WEB-021 | STORY-WEB-021-ecto-content-test-hooks.md | DONE | milestone-05 | Give Ectoplasm results stable content hooks for browser checks
- STORY-WEB-028 | STORY-WEB-028-align-browser-workflow-contracts.md | DONE | milestone-05 | Align remaining browser workflow contracts and coverage
- STORY-WEB-020 | STORY-WEB-020-layout-smoke-total-sell-value.md | DONE | milestone-05 | Exercise backend total sell value in the shared layout smoke fixture
- STORY-WEB-029 | STORY-WEB-029-repair-account-ecto-smoke-contracts.md | DONE | milestone-05 | Repair stale account and Ectoplasm browser smoke contracts

## Archived

Completed milestone details and evidence remain in the archived canonical story files.

### Milestone 00

- `STORY-DOM-001` | [STORY-DOM-001-multiple-recipe-selection-test.md](archive/milestone-00/STORY-DOM-001-multiple-recipe-selection-test.md) | DONE | Add a failing regression test for multiple-recipe selection priority
- `STORY-DOM-002` | [STORY-DOM-002-multiple-recipe-selection-fix.md](archive/milestone-00/STORY-DOM-002-multiple-recipe-selection-fix.md) | DONE | Implement discipline-aware, cost-based multiple-recipe selection
- `STORY-DOM-003` | [STORY-DOM-003-price-unavailable-blocked-reason.md](archive/milestone-00/STORY-DOM-003-price-unavailable-blocked-reason.md) | DONE | Expose PRICE_UNAVAILABLE instead of silently filtering unresolvable rows
- `STORY-DOM-004` | [STORY-DOM-004-owned-material-pool-character-inventories.md](archive/milestone-00/STORY-DOM-004-owned-material-pool-character-inventories.md) | DONE | Extend owned-material pool to include character inventories
- `STORY-DOM-005` | [STORY-DOM-005-remove-dead-legacy-craft-planner-code.md](archive/milestone-00/STORY-DOM-005-remove-dead-legacy-craft-planner-code.md) | DONE | Remove dead legacy craft-vs-buy code path in `CraftingPlanner`
- `STORY-DOM-006` | [STORY-DOM-006-remove-debug-instrumentation.md](archive/milestone-00/STORY-DOM-006-remove-debug-instrumentation.md) | DONE | Remove leftover debug instrumentation from `CraftingPlanner`
- `STORY-DOM-007` | [STORY-DOM-007-bound-material-characterization-test.md](archive/milestone-00/STORY-DOM-007-bound-material-characterization-test.md) | DONE | Characterization test: bound/soulbound materials are not distinguished from normal owned inventory
- `STORY-DOM-008` | [STORY-DOM-008-recipe-not-allowed-and-budget-blocked-reasons.md](archive/milestone-00/STORY-DOM-008-recipe-not-allowed-and-budget-blocked-reasons.md) | DONE | Add the remaining missing BlockedReason values: RECIPE_NOT_ALLOWED and INSUFFICIENT_BUDGET
- `STORY-DOM-009` | [STORY-DOM-009-heuristic-skip-characterization-test.md](archive/milestone-00/STORY-DOM-009-heuristic-skip-characterization-test.md) | DONE | Characterization test: direct-price heuristic skip under useOwnMats=false/allowBuying=false
- `STORY-DOM-010` | [STORY-DOM-010-ectoplasm-salvage-fee-model-resolution.md](archive/milestone-00/STORY-DOM-010-ectoplasm-salvage-fee-model-resolution.md) | DONE | Resolve Ectoplasm Salvage fee-model ambiguity (DQ-011)
- `STORY-DOM-011` | [STORY-DOM-011-bound-material-domain-rule.md](archive/milestone-00/STORY-DOM-011-bound-material-domain-rule.md) | DONE | Implement account-bound/soulbound material domain rule using the resolved selected-character concept (domain + repo layers)
- `STORY-INFRA-001` | [STORY-INFRA-001-icon-cache-path-config.md](archive/milestone-00/STORY-INFRA-001-icon-cache-path-config.md) | DONE | Move the hardcoded icon-cache filesystem path into configuration
- `STORY-INFRA-002` | [STORY-INFRA-002-crafting-graph-cache-auto-rebuild.md](archive/milestone-00/STORY-INFRA-002-crafting-graph-cache-auto-rebuild.md) | DONE | Auto-rebuild the crafting graph cache when missing, then stop tracking it in git
- `STORY-SYNC-001` | [STORY-SYNC-001-character-inventory-sync.md](archive/milestone-00/STORY-SYNC-001-character-inventory-sync.md) | DONE | Sync character bag and equipment inventory into `character_items`
- `STORY-SYNC-002` | [STORY-SYNC-002-recipe-parser-fixture-test.md](archive/milestone-00/STORY-SYNC-002-recipe-parser-fixture-test.md) | DONE | Recipe parser test using a captured real GW2 API payload fixture (Layer 3)
- `STORY-SYNC-003` | [STORY-SYNC-003-live-api-smoke-tests.md](archive/milestone-00/STORY-SYNC-003-live-api-smoke-tests.md) | DONE | Optional Layer 4 live GW2 API smoke tests (excluded from default `mvn test`)
- `STORY-TEST-001` | [STORY-TEST-001-shared-domain-fixture.md](archive/milestone-00/STORY-TEST-001-shared-domain-fixture.md) | DONE | Shared in-memory domain test fixture for `craft.*` tests
- `STORY-TEST-002` | [STORY-TEST-002-owned-pool-character-inventory-repository-test.md](archive/milestone-00/STORY-TEST-002-owned-pool-character-inventory-repository-test.md) | DONE | Repository-level intended-behavior test: owned-material pool includes character inventories
- `STORY-TEST-003` | [STORY-TEST-003-character-sync-repository-test.md](archive/milestone-00/STORY-TEST-003-character-sync-repository-test.md) | DONE | Layer 2 PostgreSQL integration test: `sync.CharacterSync` upsert + stale-row deletion semantics
- `STORY-TEST-004` | [STORY-TEST-004-recipe-repository-integration-test.md](archive/milestone-00/STORY-TEST-004-recipe-repository-integration-test.md) | DONE | Layer 2 PostgreSQL integration test: `repo.RecipeRepository` unlock/discipline/ingredient read semantics
- `STORY-TEST-005` | [STORY-TEST-005-tp-price-repository-integration-test.md](archive/milestone-00/STORY-TEST-005-tp-price-repository-integration-test.md) | DONE | Layer 2 PostgreSQL integration test: `repo.tp.TpPriceRepository` NULL-price and batch-lookup semantics
- `STORY-TEST-006` | [STORY-TEST-006-tp-sync-write-path-integration-test.md](archive/milestone-00/STORY-TEST-006-tp-sync-write-path-integration-test.md) | DONE | Layer 2 PostgreSQL integration test: `sync.TpSync`'s `tp_prices` upsert write path (NULL-price preservation, upsert-on-conflict)
- `STORY-TEST-007` | [STORY-TEST-007-account-sync-write-path-integration-test.md](archive/milestone-00/STORY-TEST-007-account-sync-write-path-integration-test.md) | DONE | Layer 2 PostgreSQL integration test: `sync.AccountSync`'s `account_bank`/`account_materials`/`account_recipes` upsert + stale-row-deletion write paths

### Milestone 01

- `STORY-DOC-001` | [STORY-DOC-001-explain-crafting-calculation.md](archive/milestone-01/STORY-DOC-001-explain-crafting-calculation.md) | DONE | Document the crafting calculation for readers and link development documentation
- `STORY-DOM-012` | [STORY-DOM-012-selected-character-ui-wiring.md](archive/milestone-01/STORY-DOM-012-selected-character-ui-wiring.md) | DONE | Add character selector UI and wire the selected character into Crafting Profit / Discovery flows
- `STORY-DOM-013` | [STORY-DOM-013-expose-blocked-crafting-rows.md](archive/milestone-01/STORY-DOM-013-expose-blocked-crafting-rows.md) | DONE | Preserve unavailable and blocked results in Crafting Profit and Discovery
- `STORY-DOM-014` | [STORY-DOM-014-coordinate-all-characters-crafting.md](archive/milestone-01/STORY-DOM-014-coordinate-all-characters-crafting.md) | DONE | Coordinate Crafting Profit plans across eligible characters
- `STORY-DOM-015` | [STORY-DOM-015-preserve-refresh-state-and-verify-character-results.md](archive/milestone-01/STORY-DOM-015-preserve-refresh-state-and-verify-character-results.md) | DONE | Preserve crafting view selections and verify character-sensitive results
- `STORY-DOM-016` | [STORY-DOM-016-ecto-salvage-net-sale-proceeds.md](archive/milestone-01/STORY-DOM-016-ecto-salvage-net-sale-proceeds.md) | DONE | Include Trading Post selling fees in Ectoplasm Salvage results
- `STORY-QUALITY-001` | [STORY-QUALITY-001-phase-one-completion-review.md](archive/milestone-01/STORY-QUALITY-001-phase-one-completion-review.md) | DONE | Review Phase 1 project health before milestone completion
- `STORY-UI-001` | [STORY-UI-001-javafx-verification-capability.md](archive/milestone-01/STORY-UI-001-javafx-verification-capability.md) | DONE | Establish repeatable JavaFX verification for Phase 1 crafting views
- `STORY-UI-002` | [STORY-UI-002-remove-crafting-profit-debug-text.md](archive/milestone-01/STORY-UI-002-remove-crafting-profit-debug-text.md) | DONE | Remove technical status text below the Crafting Profit title

### Milestone 02

- `STORY-DOM-017` | [STORY-DOM-017-independent-crafting-domain-types.md](archive/milestone-02/STORY-DOM-017-independent-crafting-domain-types.md) | DONE | Separate crafting domain types from repository types
- `STORY-DOM-018` | [STORY-DOM-018-extract-recipe-knowledge-policy.md](archive/milestone-02/STORY-DOM-018-extract-recipe-knowledge-policy.md) | DONE | Extract recipe knowledge policy and verify Discovery consistency
- `STORY-DOM-019` | [STORY-DOM-019-align-account-wide-recipe-knowledge.md](archive/milestone-02/STORY-DOM-019-align-account-wide-recipe-knowledge.md) | DONE | Align recipe loading and Discovery with decided account-wide knowledge
- `STORY-INFRA-003` | [STORY-INFRA-003-consolidate-database-connection-helper.md](archive/milestone-02/STORY-INFRA-003-consolidate-database-connection-helper.md) | DONE | Consolidate repository and synchronization connection helpers

### Milestone 03

- `STORY-APP-001` | [STORY-APP-001-extract-crafting-profit-use-case.md](archive/milestone-03/STORY-APP-001-extract-crafting-profit-use-case.md) | DONE | Extract the crafting-profit application use case
- `STORY-APP-002` | [STORY-APP-002-extract-crafting-discovery-use-case.md](archive/milestone-03/STORY-APP-002-extract-crafting-discovery-use-case.md) | DONE | Extract the crafting-discovery application use case
- `STORY-APP-003` | [STORY-APP-003-extract-ectoplasm-use-case.md](archive/milestone-03/STORY-APP-003-extract-ectoplasm-use-case.md) | DONE | Route Ectoplasm Salvage through an application service
- `STORY-APP-004` | [STORY-APP-004-extract-account-refresh-use-case.md](archive/milestone-03/STORY-APP-004-extract-account-refresh-use-case.md) | DONE | Route account refresh through an application service
- `STORY-APP-005` | [STORY-APP-005-extract-global-refresh-and-graph-use-cases.md](archive/milestone-03/STORY-APP-005-extract-global-refresh-and-graph-use-cases.md) | DONE | Extract global data refresh and crafting graph rebuild application services
- `STORY-APP-006` | [STORY-APP-006-extract-trading-post-price-refresh.md](archive/milestone-03/STORY-APP-006-extract-trading-post-price-refresh.md) | DONE | Extract the Trading Post price refresh application use case
- `STORY-APP-007` | [STORY-APP-007-extract-initial-setup-use-case.md](archive/milestone-03/STORY-APP-007-extract-initial-setup-use-case.md) | DONE | Extract the first-time setup application use case
- `STORY-APP-008` | [STORY-APP-008-route-crafting-auto-refresh-through-application.md](archive/milestone-03/STORY-APP-008-route-crafting-auto-refresh-through-application.md) | DONE | Route crafting auto-refresh through application services
- `STORY-APP-009` | [STORY-APP-009-extract-bank-and-material-storage-use-cases.md](archive/milestone-03/STORY-APP-009-extract-bank-and-material-storage-use-cases.md) | DONE | Extract bank and material-storage read use cases
- `STORY-APP-010` | [STORY-APP-010-route-crafting-selectors-through-application.md](archive/milestone-03/STORY-APP-010-route-crafting-selectors-through-application.md) | DONE | Route crafting selector reads through the application layer
- `STORY-PERF-001` | [STORY-PERF-001-crafting-profit-page-load-budget.md](archive/milestone-03/STORY-PERF-001-crafting-profit-page-load-budget.md) | DONE | Resolve Crafting Profit page-load latency on the real user database
- `STORY-QUALITY-003` | [STORY-QUALITY-003-phase-three-completion-review.md](archive/milestone-03/STORY-QUALITY-003-phase-three-completion-review.md) | DONE | Review Phase 3 project health before milestone completion

### Milestone 04

- `STORY-API-001` | [STORY-API-001-spring-boot-crafting-profit-endpoint.md](archive/milestone-04/STORY-API-001-spring-boot-crafting-profit-endpoint.md) | DONE | Add a Spring Boot HTTP boundary for Crafting Profit
- `STORY-API-002` | [STORY-API-002-crafting-discovery-endpoint.md](archive/milestone-04/STORY-API-002-crafting-discovery-endpoint.md) | DONE | Expose Crafting Discovery through the HTTP API
- `STORY-API-003` | [STORY-API-003-account-sync-task-endpoint.md](archive/milestone-04/STORY-API-003-account-sync-task-endpoint.md) | DONE | Expose account synchronization as an asynchronous HTTP task
- `STORY-API-004` | [STORY-API-004-global-sync-task-endpoint.md](archive/milestone-04/STORY-API-004-global-sync-task-endpoint.md) | DONE | Expose global synchronization as an HTTP background task
- `STORY-API-005` | [STORY-API-005-price-refresh-task-endpoint.md](archive/milestone-04/STORY-API-005-price-refresh-task-endpoint.md) | DONE | Expose Trading Post price refresh through HTTP background tasks

## Not Yet Written (no file exists)

_(none â€” the two former entries here, bound/soulbound material handling and the Ectoplasm Salvage
fee-model ambiguity, are now covered by `STORY-DOM-010`/`STORY-DOM-011`/`STORY-DOM-012` above, since
`UD-001` and `UD-002` are now both RESOLVED.)_

## Numbering Convention

`STORY-<AREA>-<NUMBER>-short-name.md`. Numbers are sequential **per area**, not global.

Area codes in use: `API` (HTTP contracts), `APP` (application services), `DOM` (domain behavior), `INFRA` (infrastructure), `PERF` (performance), `QUALITY` (milestone health review), `SYNC` (game-data/account refresh), `TEST` (test infrastructure), `UI` (JavaFX presentation), and `WEB` (browser UI). Numbers are sequential within each area.

## Archiving

Once a roadmap milestone (`docs/ROADMAP.md` Phase N) is confirmed complete, its DONE stories move from `agent/stories/` into `agent/stories/archive/milestone-NN/` (never deleted) and their entries move here from "Done" to "Archived" under that milestone's own `### Milestone N` heading. This only happens as a deterministic side effect of a confirmed milestone transition during PROJECT PLANNING MODE (see `agent/PLANNER_INSTRUCTIONS.md`), never when an individual story reaches DONE. Phases 0â€“4 are complete and their archived stories are indexed below. `STORY-QUALITY-004` is still at the non-archive path and remains under Done pending the planner-owned archive step; current implementation stories are tagged `milestone-05` (Phase 5).
