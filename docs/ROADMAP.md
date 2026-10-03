# GW2 Tool — Roadmap

## 1. Purpose

This document is the realistic path from the repository's **actual current state** to the target architecture defined in `docs/TARGET_ARCHITECTURE.md`.

It is written for two audiences:

- a human maintainer deciding what to work on next,
- a planning/orchestrator AI that selects a story, hands it to Claude Code, and evaluates the result without the full conversation history behind this document.

This document contains **no dates and no time estimates**. Sequencing is based on dependencies and risk, not a schedule.

Grounding sources (do not duplicate their content here — read them when a phase needs detail):

```text
docs/DOMAIN_SPEC.md            authoritative domain rules
docs/TARGET_ARCHITECTURE.md    target structure, dependency direction, decided and open architecture choices
docs/CURRENT_ARCHITECTURE.md   current structure, as observed in source
docs/KNOWN_PROBLEMS.md         confirmed conflicts, coupling, tech debt (numbered, referenced below)
docs/TEST_STRATEGY.md          testing approach and priorities
docs/CODING_GUIDELINES.md      code-level rules for changes made from here on
```

---

## 2. Guiding Principles for Sequencing

This roadmap follows the migration principle already stated in `TARGET_ARCHITECTURE.md` §14:

```text
protect behavior with tests
  -> isolate domain logic
  -> isolate persistence/API adapters
  -> introduce backend API
  -> introduce web frontend
  -> introduce multi-user/account isolation
  -> containerize final runtime
```

Applied here as: **fix behavior before refactoring, refactor before extracting, extract before exposing, expose before replacing the UI.** Concretely:

- Domain conflicts (`KNOWN_PROBLEMS.md` §3) are fixed **before** the domain is decoupled from `repo.*`, so the decoupling step is a mechanical, behavior-preserving refactor (`TEST_STRATEGY.md` §18), not a place where bugs and restructuring get tangled together (`CLAUDE.md` "Working Rules" forbids combining unrelated refactoring with a fix).
- The domain is decoupled from persistence **before** an application-service layer is introduced, so the application layer orchestrates a clean domain instead of relocating the same coupling one level up.
- A backend HTTP API exists **before** frontend work starts, so the frontend has a real contract to build against instead of a guessed one.
- JavaFX removal is **permitted but not required** from Phase 5 onward. The original sequencing held it back until the web frontend had functional parity and a deployable backend existed, per `TARGET_ARCHITECTURE.md` §14 ("Reuse of Existing Code"). The Product Owner has since decided that the browser frontend is the canonical user interface and that the legacy JavaFX UI is obsolete and removable (`TARGET_ARCHITECTURE.md` §1, §13 and its Decided list), so parity against JavaFX is no longer a precondition for removing it. Phase 9 remains the phase that *guarantees* it is finished; no phase requires it to be deferred until then. Shared domain, application and backend code that the browser still uses is never removed with it.
- Any technology or product decision still listed under `TARGET_ARCHITECTURE.md` §16 “To Be Decided” is called out at the phase where it first becomes a blocking decision. None should be silently finalized by whoever executes a story.

---

## 3. Phase Overview

```text
Phase 0  Build & Test Foundation                (complete, 2026-09-19 — see §4)
Phase 1  Domain Stabilization                   (complete — milestone-01 archived)
Phase 2  Domain Isolation / Decoupling          (complete, 2026-09-21 — see §6)
Phase 3  Backend / Application-Service Extraction (complete; milestone-03 archived)
Phase 4  Backend HTTP API                       (complete; milestone-04 archived)
Phase 5  Browser Frontend Completion             (current; browser implementation and acceptance work in progress)
Phase 6  Multi-User / Account Isolation         (not started)
Phase 7  Containerized Runtime and Database Migration (not started; PostgreSQL is already used locally)
Phase 8  Deployment / Runtime Configuration     (not started)
Phase 9  Final Cleanup / JavaFX Removal         (not started; removal is already permitted earlier — see §2)
```

Every new or revised phase must retain the PROJECT HEALTH REVIEW exit requirement, with execution and findings governed by `TARGET_ARCHITECTURE.md` §21. Schedule the review near exit, after milestone implementation; planner/user disposition of blocking findings precedes closure. Phase 1 uses the existing `STORY-QUALITY-001` ("Review Phase 1 project health before milestone completion").

Phases are listed in dependency order. A later phase should not be started while an earlier phase has open blocking exit criteria, unless a story explicitly documents why it's safe to jump ahead.

---

## 4. Phase 0 — Build & Test Foundation

**Status:** complete (confirmed 2026-09-19 from completed Phase 0 stories).

This historical closure predates the recurring review requirement below; no retrospective review is claimed and the archived milestone is not reopened. Relevant inherited gaps are assessed in the current milestone under `TARGET_ARCHITECTURE.md` §21.

### Objective

Give the project a build system and an automated test capability, so later behavior changes can be verified without manual UI testing (`TEST_STRATEGY.md` §1).

### Dependencies

None. This is the foundation everything else assumes.

### Exit Criteria

- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- [x] A standard build system exists (Maven, via `./mvnw`), replacing the manual JAR/`lib/` setup.
- [x] A test framework is wired in (JUnit 5 via Maven/Surefire) and at least one regression test runs (`craft.CraftingResolverCraftVsBuyTest`).
- [x] The highest-priority domain conflicts already identified in `KNOWN_PROBLEMS.md` §3 each have at least one test expressing the *intended* (spec-correct) behavior, per `TEST_STRATEGY.md` §24's priority order. §3.1/§3.2/§3.3/§3.5 are fixed-and-tested; §3.4 gained an intended-behavior test at the repo+domain layer (`STORY-DOM-011`, not yet wired into production — see Phase 1's `STORY-DOM-012`); §3.6 was explicitly decided to need no new automated test (`STORY-DOM-010`, no test seam exists for its live-HTTP-calling JavaFX view) — an accepted decision of that story, not a gap.
- [x] Minimal, hand-built domain test fixtures exist (small recipe/item/price sets, `TEST_STRATEGY.md` §15) rather than relying on the full production `crafting_graph_cache.json` for unit tests (`STORY-TEST-001`, `src/test/java/craft/CraftTestFixtures.java`).
- [x] Persistence-relevant code paths are covered by integration tests against a real PostgreSQL instance, not only mocked/in-memory behavior (`STORY-TEST-002` through `STORY-TEST-008`).
- [x] At least the critical repositories/sync flows used by the crafting domain are verified against the real database schema, constraints, and upsert/delete semantics (`STORY-TEST-003` through `STORY-TEST-008`: character/account/price/recipe sync and crafting repository reads).
- [x] Parser/sync logic for Guild Wars 2 API data is covered by tests using captured real API payloads as fixtures (`STORY-SYNC-002`: `parser.RecipeParserTest` against a captured `/v2/recipes/7319` payload; noted here as a documentation correction — this criterion was already satisfied when `STORY-SYNC-002` reached DONE but the checkbox was not updated at the time).
- [x] Live GW2 API smoke tests exist for selected critical endpoints, but are optional/manual and not part of the default deterministic test suite (`STORY-SYNC-003`: `api.Gw2ApiLiveSmokeIT`, excluded from `./mvnw test` by naming convention, run via `./mvnw test -Dtest=Gw2ApiLiveSmokeIT`).
- [x] Integration tests are isolated from the normal development database and can recreate their required test data from scratch (`STORY-TEST-002` through `STORY-TEST-008`: uniquely named disposable schemas, fresh tables/data, and teardown).

### High-Level Stories

- **(Done)** Migrate manual JAR/build setup to Maven, Java 25, standard `src/main/java`/`src/test/java` layout.
- **(Done)** Regression test + fix for the craft-vs-buy cash-vs-effective-cost conflict (`KNOWN_PROBLEMS.md` §3.1).
- **(Done)** Add a regression/characterization test for multi-recipe selection (`KNOWN_PROBLEMS.md` §3.2) that fails against current behavior, ahead of fixing it in Phase 1 (`STORY-DOM-001`).
- **(Done)** Add a regression/characterization test for the owned-material pool scope (`KNOWN_PROBLEMS.md` §3.3) (`STORY-TEST-002`).
- **(Done)** Add a characterization test for bound-material handling (`KNOWN_PROBLEMS.md` §3.4) — `STORY-DOM-007` added a craft-domain test documenting current (non-compliant) behavior; `STORY-DOM-011` added the intended-behavior counterpart at the repo+domain layer, wired into production controllers by Phase 1's `STORY-DOM-012`.
- **(Done)** Add a regression/characterization test for price-unavailable handling (`KNOWN_PROBLEMS.md` §3.5) (`STORY-DOM-003`).
- **(Done)** Build a small, reusable in-memory domain fixture (a handful of items/recipes/prices) usable across the above tests, instead of each test hand-rolling its own (`STORY-TEST-001`).
- **(Done)** Add PostgreSQL integration-test infrastructure using an isolated disposable test database (`STORY-TEST-002`).
- **(Done)** Add integration tests for character/item sync and the repositories consumed by crafting logic (`STORY-TEST-002` `InventoryRepository`, `STORY-TEST-003` `CharacterSync` incl. `replaceCharacterItems`, `STORY-TEST-004` `RecipeRepository`, `STORY-TEST-005` `TpPriceRepository`); documentation correction made during `STORY-QUALITY-001` — this bullet was left unchecked despite the underlying work being complete.
- Add captured real GW2 API payload fixtures for character, inventory, recipe, and related parser/sync tests.
- **(Done)** Add optional live GW2 API smoke tests outside the default `mvn test` path (`STORY-SYNC-003`).

---

## 5. Phase 1 — Domain Stabilization

### Objective

Bring the existing crafting/economy domain logic into agreement with `DOMAIN_SPEC.md` while it still lives in its current location, so Phase 2's extraction starts from a correct, well-tested baseline instead of carrying bugs into a new structure.

### Dependencies

Phase 0 (each fix needs a failing test first, per `TEST_STRATEGY.md` §17's bug-fix workflow, and — for a conflict whose behavior depends on real persistence or GW2 API data, such as §3.3/§3.4's character-inventory/binding data — Phase 0's own exit criteria requiring `TEST_STRATEGY.md` Layer 2/3 coverage to already exist).

### Exit Criteria

- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- All confirmed conflicts in `KNOWN_PROBLEMS.md` §3 are either resolved and covered by a passing test, or converted into an explicit, answered domain question (`DOMAIN_SPEC.md` §51/§53) if resolution requires a decision only the project owner can make. For a conflict whose correct behavior depends on real database/API semantics rather than pure in-memory domain logic (e.g. §3.3's owned-material pool), "covered by a passing test" means the PostgreSQL integration-test coverage Phase 0 already requires (`TEST_STRATEGY.md` Layer 2) — a unit test alone does not close that item.
- `craft.BlockedReason` carries the full set of reasons `DOMAIN_SPEC.md` §42 expects, and nothing in the crafting-profit/discovery flow silently drops a row instead of exposing a blocked/unavailable state.
- The dead legacy craft-vs-buy code path is removed once confirmed unreachable.

### High-Level Stories

- **(Done)** Fix craft-vs-buy cash-vs-effective-cost selection (`KNOWN_PROBLEMS.md` §3.1, `DOMAIN_SPEC.md` §22).
- **(Done)** Implement discipline-aware, cost-based multi-recipe selection (`KNOWN_PROBLEMS.md` §3.2, `DOMAIN_SPEC.md` §30 / DQ-003) (`STORY-DOM-002`).
- **(Done)** Extend the owned-material pool to include character inventories (`KNOWN_PROBLEMS.md` §3.3, `DOMAIN_SPEC.md` §9 / DQ-006) — `STORY-DOM-004` extended `InventoryRepository.loadOwnedInventory()` to sum `character_items`, and `STORY-SYNC-001` made `sync.CharacterSync` actually populate that table from the GW2 API's `bags`/`equipment` data, so the fix now has a real effect.
- **(Done)** Implement account-bound/soulbound material handling (`KNOWN_PROBLEMS.md` §3.4, `DOMAIN_SPEC.md` §11.1 / DQ-007). `UD-001` resolved the "selected character" design; `STORY-DOM-011` implemented the repo + domain layers; `STORY-DOM-012` added the character-selector UI to `CraftingProfitView`/`CraftingDiscoveryView` and wired their controllers to call the binding-aware repository method, giving this rule real-user effect.
- **(Done)** Add the missing `BlockedReason` values (`PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`) and stop silently filtering unresolvable rows at the controller layer (`KNOWN_PROBLEMS.md` §3.5, `DOMAIN_SPEC.md` §21 / §42) (`STORY-DOM-008`, `STORY-DOM-013`); documentation correction made during `STORY-QUALITY-001` — this bullet was left unchecked despite the underlying work being complete.
- **(Done)** Resolve the Ectoplasm Salvage fee-model ambiguity as a new, explicitly answered domain question (`UD-002` and `DOMAIN_SPEC.md` sections 45–47), then make `EctoView` and `Main.java` agree (`STORY-DOM-010`; full convergence into one implementation happens in Phase 3, once an application-service layer exists to hold it).
- **(Done)** Remove the dead legacy code path in `CraftingPlanner` (`canCraft`/`simulateCraft`/`obtain`/`PlanRun`) once confirmed unused (`KNOWN_PROBLEMS.md` §7.1).
- **(Done)** Remove leftover debug instrumentation (`KNOWN_PROBLEMS.md` §7.2) (`STORY-DOM-006`).
- **(Done)** Decide whether the 250-craft simulation cap is intentional and document it in `DOMAIN_SPEC.md`, or expose a "capped" indicator (`KNOWN_PROBLEMS.md` §7.6) — resolved by `agent/user-decisions/UD-003-craft-simulation-cap.md`, recorded in `DOMAIN_SPEC.md` §28; documentation correction made during `STORY-QUALITY-001` — this bullet was left unchecked despite the underlying decision being resolved.

---

## 6. Phase 2 — Domain Isolation / Decoupling

Status: Complete (2026-09-21). Planner closure assessment accepted `STORY-QUALITY-002`'s evidence: all implementation exit criteria are satisfied, no blocking findings remain, and no criterion transfer is needed. The disclosed cross-character Discovery live-view verification gap is non-blocking for this milestone's domain/persistence scope; no additional story is required for closure.

### Objective

Extract the crafting/economy domain logic into a persistence- and transport-independent form, satisfying `TARGET_ARCHITECTURE.md` §7's Domain Independence Rule, so it can be unit tested and later reused by a backend module without dragging JDBC-shaped types along.

### Dependencies

Phase 1. This must be a behavior-preserving refactor (`TEST_STRATEGY.md` §18) — fixing domain bugs at the same time as restructuring would make it impossible to tell which change caused a test failure.

### Exit Criteria

- **(Done — `STORY-QUALITY-002`; planner disposition 2026-09-21)** Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- **(Done — `STORY-DOM-017`)** `craft.*` no longer imports anything from `repo.*` (`KNOWN_PROBLEMS.md` §4.1).
- **(Done — `STORY-DOM-017`)** Independent domain types exist (e.g. `Recipe`, `Ingredient`, `PriceQuote`) with a mapping boundary living in `repo.*`, per `TARGET_ARCHITECTURE.md` §10.
- **(Done — `STORY-DOM-018`/`STORY-DOM-019`)** The "recipe is unlocked" rule is no longer expressed as a SQL CTE (`KNOWN_PROBLEMS.md` §4.2): `craft.RecipeKnowledgePolicy.isKnownAccountWide` is the single pure domain policy shared by `RecipeRepository.loadRecipes`, `loadRecipesForCharacter`, and `loadMissingDiscoverableRecipeIdsForCharacter`. `STORY-DOM-018` extracted the policy but left the character-scoped entry points checking only the selected character's own unlocks; `STORY-DOM-019` corrected them to agree with `loadRecipes`'s account-wide (any-character) knowledge decision, matching `DOMAIN_SPEC.md` §34/35 and decided `DQ-010`.
- **(Done — `STORY-INFRA-003`)** `repo.Db` and `sync.Db` are consolidated into one connection helper (`KNOWN_PROBLEMS.md` §4.3).
- **(Done — `STORY-DOM-017`)** All Phase 0/1 domain tests still pass, now exercising only the independent domain types with no database or repository involved.

### High-Level Stories

- **(Done — `STORY-DOM-017`)** Introduce independent domain model types, separate from the `repo.RecipeRepository`/`repo.tp.TpPriceRepository` nested classes currently reused as the domain model.
- **(Done — `STORY-DOM-017`)** Introduce a mapping/adapter layer in `repo.*` converting persistence rows into the new domain model.
- **(Done — `STORY-DOM-018`/`STORY-DOM-019`)** Move "recipe is unlocked" logic out of SQL into an application/domain-level concept (`craft.RecipeKnowledgePolicy`); `STORY-DOM-018` found Discovery's separate unlock query was **not** semantically consistent with it for cross-character knowledge (`KNOWN_PROBLEMS.md` §4.2) and reported it rather than fixing it mid-refactor; `STORY-DOM-019` implemented the already-decided (`DQ-010`) account-wide correction.
- **(Done — `STORY-INFRA-003`)** Consolidate `repo.Db` and `sync.Db`.
- **(Done — `STORY-DOM-017`)** Port the Phase 0/1 domain tests to construct only the new independent types (no `repo.*` construction inside domain tests).

---

## 7. Phase 3 — Backend / Application-Service Extraction

**Status: Complete — 2026-09-23.** All exit criteria are satisfied. Review findings are explicitly dispositioned in `STORY-QUALITY-003`'s Result; `STORY-APP-010` and `STORY-APP-011` complete its two scoped follow-ups. The documented current phase advances through `agent/PROJECT_STATE.md`; no next-phase stories are planned in this pass.

**Blocking performance gate (PO requirement, 2026-09-22) — satisfied 2026-09-23:** this phase could not close or advance to Phase 4 until the Crafting Profit performance requirement in `TARGET_ARCHITECTURE.md` §17 passed real-user-database measurement and received explicit subsequent user acceptance. Both happened: the measurements and the Product Owner's dated acceptance are recorded in `STORY-PERF-001`'s Result. This gate no longer blocks the phase; the remaining exit criteria below still govern whether Phase 3 closes.

### Objective

Introduce an Application Layer of named use cases that orchestrate the now-independent domain plus infrastructure, per `TARGET_ARCHITECTURE.md` §8, replacing the current pattern of UI classes and button handlers calling `sync.*`/`repo.*`/`craft.*` directly.

### Dependencies

Phase 2. Building an application layer on top of a still-coupled domain would just relocate the coupling problem rather than remove it.

### Exit Criteria

- **(Done)** Crafting Profit meets the complete-page, at-most-7-second real-user-database requirement in `TARGET_ARCHITECTURE.md` §17, with measurement evidence and explicit dated user acceptance recorded in `STORY-PERF-001`.
- **(Done)** Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- **(Done)** Application services exist for at least: crafting profit calculation, crafting discovery candidates, account refresh, global data refresh, Trading Post price refresh, crafting graph rebuild — matching the example use-case list in `TARGET_ARCHITECTURE.md` §8.
- **(Done)** `Gw2App` button handlers, `EctoView`, and `Main.java` no longer call `sync.*`/`repo.*`/`craft.*` directly; they call application services.
- **(Done)** The two disagreeing Ectoplasm Salvage implementations (`STORY-DOM-010` and `STORY-APP-003`) converge into one application service with one calculation.
- **(Done)** `AccountRefreshService`'s current dead-call situation (`KNOWN_PROBLEMS.md` §8) is resolved — either wired up as the account-refresh use case or removed.

### High-Level Stories

- **(Done)** Measure and resolve Crafting Profit page-load latency on the current real user database; obtain explicit user acceptance (`STORY-PERF-001`).
- **(Done)** Define application services wrapping the crafting-profit and crafting-discovery flows currently implemented inside `CraftingProfitController`/`CraftingDiscoveryController`.
- **(Done)** Define application services for the synchronization use cases currently invoked ad hoc from `Gw2App`/`InitialSetupService`/`AccountRefreshService`.
- **(Done)** Migrate the Ectoplasm Salvage calculation into a domain calculation plus application service; retire the inline `EctoView` calculation and `Main.java`.
- **(Done)** Update JavaFX views/controllers to call application services only.

---

## 8. Phase 4 — Backend HTTP API

**Status: COMPLETE — milestone-04 closed by planning on 2026-09-24.** All eight exit criteria below are satisfied on the recorded evidence in `agent/stories/STORY-QUALITY-004-phase-four-completion-review.md`; its Result records the subsequent planner disposition of all eight non-blocking findings. No criterion is transferred. API timing remains boundary-only evidence, not a new full-page measurement. The documented current-phase pointer is maintained in `agent/PROJECT_STATE.md`.

### Objective

Expose the application services over HTTP per `TARGET_ARCHITECTURE.md` §9, so a future web frontend has a real contract to consume — without removing the JavaFX UI yet.

### Dependencies

Phase 3. The API layer should be a thin translation over existing application services (`TARGET_ARCHITECTURE.md` §9: "Business rules must not be implemented in controllers"), not a new place to re-implement orchestration.

### Exit Criteria

- **(Done)** Preserve and reverify the accepted Crafting Profit performance requirement at the backend/API boundary (`TARGET_ARCHITECTURE.md` §17); API timing is only one part of the full page-load budget.
- **(Done)** Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- **(Done)** **Spring Boot is the selected backend framework (resolved `UD-006`).
- **(Done)** HTTP endpoints exist for at least the crafting profit/discovery calculations and the sync/refresh operations sketched in `TARGET_ARCHITECTURE.md` §9.
- **(Done)** Controllers/routes contain no business logic (thin translation only).
- **(Done)** Transport DTOs are distinct from domain objects; GW2 API JSON shapes do not leak into HTTP responses.
- **(Done)** Backend API tests exist per `TEST_STRATEGY.md` §11 (valid/invalid input, mapped domain failures).
- **(Done)** The JavaFX UI continues to work unmodified (still calling application services in-process) — the API is additive at this point.

### High-Level Stories

- **(Done)** Decide and document the backend web framework.
- **(Done)** Stand up crafting-profit and crafting-discovery HTTP endpoints.
- **(Done)** Stand up sync/refresh trigger endpoints, using the resolved mixed synchronous/asynchronous approach in `UD-007`.
- **(Done)** Define request/response DTOs and their mapping to/from domain objects.
- **(Done)** Add backend API contract tests.

---

## 9. Phase 5 — Browser Frontend Completion

### Objective

Complete and verify browser feature workflows over the backend HTTP API. JavaFX is obsolete and may be removed from this phase onward, but removing it is not a Phase 5 exit criterion; Phase 9 is where any remainder is finished. Authoritative crafting-profit/discovery and other backend-owned domain results remain backend-provided. The Ecto Salvage calculator is an explicit exception: its small feature-specific calculation is frontend-owned and operates from the required metadata, Trading Post prices, and account Luck data.

### Dependencies

Phase 4. The frontend needs stable backend APIs for backend-owned workflows. Small presentation-local calculations may remain in the frontend when explicitly decided, as with Ecto Salvage.

### Exit Criteria

- **(Done)** Verify the full browser-navigation-to-complete-Crafting-Profit-page performance requirement against the real user database; see `STORY-PERF-002` and `TEST_STRATEGY.md` §34.
- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- **(Done)** Vue 3 and TypeScript are selected for the browser frontend (ADR-001).
- **(Done)** The browser renders Crafting Profit, Discovery, resolution details and domain states using backend-provided authoritative values (`TARGET_ARCHITECTURE.md` §10.2–10.3).
- **(Done)** Normal feature workflows refresh their needed account and price data; System Status is reserved for diagnostics and explicit maintenance refreshes.
- **(Done)** Crafting Profit, Discovery, and other backend-owned domain results are not independently recalculated in the browser.
- **(Done)** Ecto Salvage is frontend-owned and calculates locally from item metadata, Trading Post prices, account Luck, and user-selected inputs; the former backend calculation route is absent.
- **(Done)** The former backend-owned Ecto calculation route is absent. Unreferenced legacy browser API types/hooks are recorded in `KNOWN_PROBLEMS.md` KP-25; their cleanup is not required for feature behavior.

### High-Level Stories

- (Done) Vue 3 and TypeScript are selected; see ADR-001.
- (Done) Crafting Profit and backend-authoritative resolution details are available in the browser.
- (Done) Crafting Discovery is available in the browser.
- (Done) Bank, Materials, and frontend-owned Ectoplasm Salvage workflows are available in the browser.
- (Done) The shared refresh workflow and System Status page are implemented; remaining Phase 5 stories are listed in `agent/stories/BACKLOG.md`.
- (Done and continuing) Frontend component and browser smoke coverage is tracked in the canonical stories and `TEST_STRATEGY.md`.
- (Done) The obsolete backend-owned Ectoplasm calculation route was removed; the browser calculation is canonical.
- Finish browser coverage and acceptance. JavaFX removal is permitted from this phase onward but is not required to close it; Phase 9 still owns completing it.

---

## 10. Phase 6 — Multi-User / Account Isolation

### Objective

Convert the web application from the current single-account-per-instance assumption to a shared multi-user deployment model before containerization/deployment hardens the runtime around the old assumption. One application instance and one PostgreSQL database should support multiple GW2 accounts while preserving strict account-data isolation and sharing genuinely global GW2 data.

### Dependencies

Phase 5. The browser/backend contract and web frontend should exist before introducing browser-held per-user GW2 credentials and multi-user request scoping. This phase must complete before containerization/deployment so the production runtime is built around the intended public multi-user model rather than retrofitted afterward.

### Exit Criteria

- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- Replace the single configured GW2-account-per-instance assumption with an explicit multi-user/account model.
- Use one shared PostgreSQL database; do not create one database per user. Global GW2 reference/economy data is shared, while account-specific data is scoped to a stable GW2 account identity.
- Resolve a presented GW2 API key through the GW2 API to the stable account identity and use that identity to find/reuse the account's existing persisted data. Replacing/recreating a GW2 API key must not create a duplicate account dataset.
- Keep the user's GW2 API key out of persistent server/database storage. The browser retains the key and supplies it to the backend only when required for account-specific GW2 API operations; the backend treats it as transient request/operation input. The exact browser storage mechanism must be explicitly decided during this phase.
- Account-specific persistence (including characters, inventories/material storage, unlock/discovery state and other synchronized account data) cannot be read, modified, synchronized or used in calculations under another account's scope. Add persistence/API/integration tests proving this isolation.
- Explicitly decide whether possession/validation of the GW2 API key and resolved stable GW2 account identity is sufficient for application identity, or whether separate application authentication is required. Do not add username/password infrastructure by default without that decision.
- Classify synchronization/data ownership as either global or account-specific. Shared/global refreshes must not be independently triggered by every browser user when that would duplicate expensive work or external API traffic.
- Profit and Discovery use one globally shared Trading Post cache. Feature calculations refresh only their required missing or stale IDs on demand; fresh quotes are reused for ten minutes. No full-catalog timer runs. Multi-user requests must continue to share this cache.
- Apply the same multi-user concurrency review to other global refresh/synchronization operations: coalesce, schedule, cache or otherwise centralize work where many users could otherwise trigger the same global operation at once. Account-specific refreshes remain scoped to the requesting account.
- Track account activity (for example `last_seen_at` and/or `last_successful_sync_at`) so inactive account-specific data can be identified. Define a retention/cleanup policy for stale account data, but only introduce automatic deletion/cleaner execution when measured storage/database impact makes cleanup worthwhile. The concrete inactivity period remains an explicit decision at that point; cleanup must never delete shared global data.
- Define a migration path for the existing single-user database/account data into the new account-scoped model without discarding valid existing data.
- Existing authoritative domain calculations remain account-agnostic where possible; user/account scoping belongs at the API/application/persistence boundaries and must not duplicate domain rules.

### High-Level Stories

- Decide and document the stable account identity, browser-side GW2 API-key handling, and whether separate application authentication is required.
- Separate persistence into shared/global data and account-scoped data, adding stable account ownership keys/relations where required.
- Migrate the existing single-user data into the account-scoped schema.
- Make account sync resolve the supplied GW2 API key to the stable GW2 account identity and reuse existing data for replacement keys.
- Add cross-account isolation tests covering repositories, application services and HTTP endpoints.
- Keep Trading Post prices in a shared on-demand cache, refreshing only missing/stale required IDs; no full-catalog timer is planned while this remains a single backend instance.
- Review other global refresh operations for duplicate multi-user triggering and centralize/coalesce them where appropriate.
- Add account activity tracking and document the deferred evidence-based stale-account cleanup policy.

---

## 11. Phase 7 — Containerized Runtime and Database Migration

### Objective

Move from manually-run local services to the target three-container model (frontend, backend, PostgreSQL) with reproducible schema management, per `TARGET_ARCHITECTURE.md` §3, §7, and §12.

### Dependencies

Phase 6. Containerization should package the intended multi-user/account-isolated runtime rather than harden the superseded single-account-per-instance model. The backend and web frontend already exist from Phases 4–5.

### Exit Criteria

- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- **Explicit decision made** on the database migration tool (`TARGET_ARCHITECTURE.md` §16 lists it as to be decided).
- The manually-executed `src/PostgreSQL Query to create DB` script is replaced by versioned, repeatable migrations (`TARGET_ARCHITECTURE.md` §16).
- Backend, frontend, and PostgreSQL each run in their own container.
- `docker compose up` starts all three (`TARGET_ARCHITECTURE.md` §12).
- PostgreSQL is not required to be exposed outside the internal Docker network (`TARGET_ARCHITECTURE.md` §12).
- A fresh environment can create the schema and run the application end-to-end without manually pasting SQL into `psql`.

### High-Level Stories

- Decide and document the database migration tool.
- Convert the manual SQL setup script into versioned migrations.
- Write a backend Dockerfile.
- Write a frontend Dockerfile (once Phase 5 exists).
- Write `docker-compose.yml` wiring frontend/backend/PostgreSQL on an internal network.
- Verify a fresh `docker compose up` reproduces a working environment from nothing.

---

## 12. Phase 8 — Deployment / Runtime Configuration

### Objective

Make the containerized system deployable outside the developer's own machine, per `TARGET_ARCHITECTURE.md` §11–12 and §16.

### Dependencies

Phase 7. Deployment configuration only matters once there is something containerized to deploy.

### Exit Criteria

- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- Server-owned configuration (database URL/user/password and any future deployment settings) is supplied via environment variables or equivalent container/runtime configuration. Per-user GW2 API keys are not deployment environment variables in the multi-user model; they remain user/browser-held and are supplied transiently to the backend as defined in Phase 6.
- **Explicit decisions made, only when actually needed** on reverse proxy and hosting provider. Authentication/account identity follows the Phase 6 decision rather than being deferred to deployment.
- The deployed application supports the Phase 6 shared multi-user model: one application instance can serve multiple isolated GW2 accounts using shared global data and one PostgreSQL database.

### High-Level Stories

- Carry server-owned `.env`-style configuration into container-level environment variables; do not move per-user GW2 API keys into deployment configuration.
- Decide a reverse proxy approach, if the chosen hosting target needs one.
- Decide a hosting provider only at the point of actually deploying. Oracle Cloud and Cloudflare-related hosting are possible candidates only. Evaluate current capabilities and limits when deployment is reached; neither is selected, and provider portability remains a constraint.
- Apply the authentication/account-identity decision already made in Phase 6; deployment must not silently replace it with provider-specific identity infrastructure.

---

## 13. Phase 9 — Final Cleanup / JavaFX Removal

### Objective

Retire whatever remains of the JavaFX desktop UI, and any code it alone still uses — consistent with `TARGET_ARCHITECTURE.md` §14 (existing code is reused when sound, replaced when superseded, not thrown away speculatively).

The JavaFX UI is already superseded by the browser frontend, so removal work may happen in any phase from Phase 5 onward (see §2). This phase is not a gate holding that work back; it is where the remaining items below are confirmed finished, and it closes immediately if earlier phases already did them.

### Dependencies

Phase 5 (frontend functional parity) and Phase 8 (a deployable system users can actually switch to) for *completing* this phase. Individual removal steps have no such dependency: the Product Owner has declared the JavaFX UI obsolete, so any of them may be done earlier as long as shared domain/application/backend code the browser still uses is preserved.

### Exit Criteria

- Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (`TARGET_ARCHITECTURE.md` §21).
- Every JavaFX view's functionality is confirmed present in the web frontend (explicit parity checklist, not an assumption).
- JavaFX views, controllers in the default package, and the `Gw2App` entry point are removed.
- `Main.java` (superseded by the unified Ecto application service from Phase 3) is removed.
- JavaFX Maven dependencies and the `javafx-maven-plugin` are removed from `pom.xml`.
- No class remains in the default (unnamed) Java package.
- `CLAUDE.md`, `docs/CURRENT_STATE_SPEC.md`, and `docs/CURRENT_ARCHITECTURE.md` are updated to describe the web-only architecture.

### High-Level Stories

- Build and confirm a feature-parity checklist between JavaFX views and the web frontend (Bank, Materials, Ecto, Crafting Profit, Crafting Discovery, System Status and workflow-driven refresh).
- Remove JavaFX views/controllers and the `Gw2App` entry point.
- Remove `Main.java` and any other now-fully-superseded legacy code.
- Remove JavaFX dependencies and the `javafx-maven-plugin` from `pom.xml`.
- Update project documentation to reflect the web-only architecture.

---

## 14. Status

This roadmap reflects the repository state inspected on 2026-09-30. Phase 5 is current; Phases 0–4 are complete. `agent/PROJECT_STATE.md` is the continuity pointer for planning runs.

It should be revisited whenever a phase's exit criteria are fully met, or when a new domain/architecture decision materially changes what a later phase requires.
