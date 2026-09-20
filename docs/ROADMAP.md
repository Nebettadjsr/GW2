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
docs/TARGET_ARCHITECTURE.md    target structure, dependency direction, TBD technology decisions
docs/CURRENT_ARCHITECTURE.md   current structure, as observed in source
docs/KNOWN_PROBLEMS.md         confirmed conflicts, coupling, tech debt (numbered, referenced below)
docs/TEST_STRATEGY.md          testing approach and priorities
docs/CODING_GUIDELINES.md      code-level rules for changes made from here on
```

---

## 2. Guiding Principles for Sequencing

This roadmap follows the migration principle already stated in `TARGET_ARCHITECTURE.md` §27:

```text
protect behavior with tests
  -> isolate domain logic
  -> isolate persistence/API adapters
  -> introduce backend API
  -> introduce web frontend
  -> containerize final runtime
```

Applied here as: **fix behavior before refactoring, refactor before extracting, extract before exposing, expose before replacing the UI.** Concretely:

- Domain conflicts (`KNOWN_PROBLEMS.md` §3) are fixed **before** the domain is decoupled from `repo.*`, so the decoupling step is a mechanical, behavior-preserving refactor (`TEST_STRATEGY.md` §18), not a place where bugs and restructuring get tangled together (`CLAUDE.md` "Working Rules" forbids combining unrelated refactoring with a fix).
- The domain is decoupled from persistence **before** an application-service layer is introduced, so the application layer orchestrates a clean domain instead of relocating the same coupling one level up.
- A backend HTTP API exists **before** frontend work starts, so the frontend has a real contract to build against instead of a guessed one.
- JavaFX is only removed **after** the web frontend has functional parity and a deployable backend exists — per `TARGET_ARCHITECTURE.md` §28 ("Reuse of Existing Code"), the current implementation is not thrown away by default.
- Any technology `TARGET_ARCHITECTURE.md` §30 marks `TBD` (frontend framework/language, backend web framework, DB migration tool, reverse proxy, hosting provider, authentication, long-running-job mechanism) is called out explicitly at the phase where it first becomes a blocking decision. None of these should be silently finalized by whoever executes a story.

---

## 3. Phase Overview

```text
Phase 0  Build & Test Foundation                (in progress)
Phase 1  Domain Stabilization                   (not started)
Phase 2  Domain Isolation / Decoupling          (not started)
Phase 3  Backend / Application-Service Extraction (not started)
Phase 4  Backend HTTP API                       (not started)
Phase 5  Frontend Migration                     (not started)
Phase 6  PostgreSQL / Containerization           (not started)
Phase 7  Deployment / Runtime Configuration      (not started)
Phase 8  Final Cleanup / JavaFX Removal          (not started)
```

Phases are listed in dependency order. A later phase should not be started while an earlier phase has open blocking exit criteria, unless a story explicitly documents why it's safe to jump ahead.

---

## 4. Phase 0 — Build & Test Foundation

**Status:** complete (confirmed 2026-09-19 from completed Phase 0 stories).

### Objective

Give the project a build system and an automated test capability, so later behavior changes can be verified without manual UI testing (`TEST_STRATEGY.md` §1).

### Dependencies

None. This is the foundation everything else assumes.

### Exit Criteria

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
- Add integration tests for character/item sync and the repositories consumed by crafting logic.
- Add captured real GW2 API payload fixtures for character, inventory, recipe, and related parser/sync tests.
- **(Done)** Add optional live GW2 API smoke tests outside the default `mvn test` path (`STORY-SYNC-003`).

---

## 5. Phase 1 — Domain Stabilization

### Objective

Bring the existing crafting/economy domain logic into agreement with `DOMAIN_SPEC.md` while it still lives in its current location, so Phase 2's extraction starts from a correct, well-tested baseline instead of carrying bugs into a new structure.

### Dependencies

Phase 0 (each fix needs a failing test first, per `TEST_STRATEGY.md` §17's bug-fix workflow, and — for a conflict whose behavior depends on real persistence or GW2 API data, such as §3.3/§3.4's character-inventory/binding data — Phase 0's own exit criteria requiring `TEST_STRATEGY.md` Layer 2/3 coverage to already exist).

### Exit Criteria

- All confirmed conflicts in `KNOWN_PROBLEMS.md` §3 are either resolved and covered by a passing test, or converted into an explicit, answered domain question (`DOMAIN_SPEC.md` §51/§53) if resolution requires a decision only the project owner can make. For a conflict whose correct behavior depends on real database/API semantics rather than pure in-memory domain logic (e.g. §3.3's owned-material pool), "covered by a passing test" means the PostgreSQL integration-test coverage Phase 0 already requires (`TEST_STRATEGY.md` Layer 2) — a unit test alone does not close that item.
- `craft.BlockedReason` carries the full set of reasons `DOMAIN_SPEC.md` §42 expects, and nothing in the crafting-profit/discovery flow silently drops a row instead of exposing a blocked/unavailable state.
- The dead legacy craft-vs-buy code path is removed once confirmed unreachable.

### High-Level Stories

- **(Done)** Fix craft-vs-buy cash-vs-effective-cost selection (`KNOWN_PROBLEMS.md` §3.1, `DOMAIN_SPEC.md` §22).
- **(Done)** Implement discipline-aware, cost-based multi-recipe selection (`KNOWN_PROBLEMS.md` §3.2, `DOMAIN_SPEC.md` §30 / DQ-003) (`STORY-DOM-002`).
- **(Done)** Extend the owned-material pool to include character inventories (`KNOWN_PROBLEMS.md` §3.3, `DOMAIN_SPEC.md` §9 / DQ-006) — `STORY-DOM-004` extended `InventoryRepository.loadOwnedInventory()` to sum `character_items`, and `STORY-SYNC-001` made `sync.CharacterSync` actually populate that table from the GW2 API's `bags`/`equipment` data, so the fix now has a real effect.
- **(Done)** Implement account-bound/soulbound material handling (`KNOWN_PROBLEMS.md` §3.4, `DOMAIN_SPEC.md` §11.1 / DQ-007). `UD-001` resolved the "selected character" design; `STORY-DOM-011` implemented the repo + domain layers; `STORY-DOM-012` added the character-selector UI to `CraftingProfitView`/`CraftingDiscoveryView` and wired their controllers to call the binding-aware repository method, giving this rule real-user effect.
- Add the missing `BlockedReason` values (`PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`) and stop silently filtering unresolvable rows at the controller layer (`KNOWN_PROBLEMS.md` §3.5, `DOMAIN_SPEC.md` §21 / §42).
- **(Done)** Resolve the Ectoplasm Salvage fee-model ambiguity as a new, explicitly answered domain question (`KNOWN_PROBLEMS.md` §3.6 — proposed "DQ-011"), then make `EctoView` and `Main.java` agree (`STORY-DOM-010`; full convergence into one implementation happens in Phase 3, once an application-service layer exists to hold it).
- **(Done)** Remove the dead legacy code path in `CraftingPlanner` (`canCraft`/`simulateCraft`/`obtain`/`PlanRun`) once confirmed unused (`KNOWN_PROBLEMS.md` §7.1).
- **(Done)** Remove leftover debug instrumentation (`KNOWN_PROBLEMS.md` §7.2) (`STORY-DOM-006`).
- Decide whether the 250-craft simulation cap is intentional and document it in `DOMAIN_SPEC.md`, or expose a "capped" indicator (`KNOWN_PROBLEMS.md` §7.6).

---

## 6. Phase 2 — Domain Isolation / Decoupling

### Objective

Extract the crafting/economy domain logic into a persistence- and transport-independent form, satisfying `TARGET_ARCHITECTURE.md` §7's Domain Independence Rule, so it can be unit tested and later reused by a backend module without dragging JDBC-shaped types along.

### Dependencies

Phase 1. This must be a behavior-preserving refactor (`TEST_STRATEGY.md` §18) — fixing domain bugs at the same time as restructuring would make it impossible to tell which change caused a test failure.

### Exit Criteria

- `craft.*` no longer imports anything from `repo.*` (`KNOWN_PROBLEMS.md` §4.1).
- Independent domain types exist (e.g. `Recipe`, `Ingredient`, `PriceQuote`) with a mapping boundary living in `repo.*`, per `TARGET_ARCHITECTURE.md` §10.
- The "recipe is unlocked" rule is no longer expressed only as a SQL CTE (`KNOWN_PROBLEMS.md` §4.2); both places that currently determine recipe-known status (`RecipeRepository.loadRecipes` and `loadMissingDiscoverableRecipeIdsForCharacter`) are confirmed to agree.
- `repo.Db` and `sync.Db` are consolidated into one connection helper (`KNOWN_PROBLEMS.md` §4.3).
- All Phase 0/1 domain tests still pass, now exercising only the independent domain types with no database or repository involved.

### High-Level Stories

- Introduce independent domain model types, separate from the `repo.RecipeRepository`/`repo.tp.TpPriceRepository` nested classes currently reused as the domain model.
- Introduce a mapping/adapter layer in `repo.*` converting persistence rows into the new domain model.
- Move "recipe is unlocked" logic out of SQL into an application/domain-level concept; verify Discovery's separate unlock query stays semantically consistent with it.
- Consolidate `repo.Db` and `sync.Db`.
- Port the Phase 0/1 domain tests to construct only the new independent types (no `repo.*` construction inside domain tests).

---

## 7. Phase 3 — Backend / Application-Service Extraction

### Objective

Introduce an Application Layer of named use cases that orchestrate the now-independent domain plus infrastructure, per `TARGET_ARCHITECTURE.md` §8, replacing the current pattern of UI classes and button handlers calling `sync.*`/`repo.*`/`craft.*` directly.

### Dependencies

Phase 2. Building an application layer on top of a still-coupled domain would just relocate the coupling problem rather than remove it.

### Exit Criteria

- Application services exist for at least: crafting profit calculation, crafting discovery candidates, account refresh, global data refresh, Trading Post price refresh, crafting graph rebuild — matching the example use-case list in `TARGET_ARCHITECTURE.md` §8.
- `Gw2App` button handlers, `EctoView`, and `Main.java` no longer call `sync.*`/`repo.*`/`craft.*` directly; they call application services.
- The two disagreeing Ectoplasm Salvage implementations (`KNOWN_PROBLEMS.md` §3.6/§4.4) converge into one application service with one calculation.
- `AccountRefreshService`'s current dead-call situation (`KNOWN_PROBLEMS.md` §8) is resolved — either wired up as the account-refresh use case or removed.

### High-Level Stories

- Define application services wrapping the crafting-profit and crafting-discovery flows currently implemented inside `CraftingProfitController`/`CraftingDiscoveryController`.
- Define application services for the synchronization use cases currently invoked ad hoc from `Gw2App`/`InitialSetupService`/`AccountRefreshService`.
- Migrate the Ectoplasm Salvage calculation into a domain calculation plus application service; retire the inline `EctoView` calculation and `Main.java`.
- Update JavaFX views/controllers to call application services only.

---

## 8. Phase 4 — Backend HTTP API

### Objective

Expose the application services over HTTP per `TARGET_ARCHITECTURE.md` §9, so a future web frontend has a real contract to consume — without removing the JavaFX UI yet.

### Dependencies

Phase 3. The API layer should be a thin translation over existing application services (`TARGET_ARCHITECTURE.md` §9: "Business rules must not be implemented in controllers"), not a new place to re-implement orchestration.

### Exit Criteria

- **Explicit decision made** on the backend web framework (`TARGET_ARCHITECTURE.md` §30 marks this `TBD` — must not be silently finalized).
- HTTP endpoints exist for at least the crafting profit/discovery calculations and the sync/refresh operations sketched in `TARGET_ARCHITECTURE.md` §9.
- Controllers/routes contain no business logic (thin translation only).
- Transport DTOs are distinct from domain objects; GW2 API JSON shapes do not leak into HTTP responses.
- Backend API tests exist per `TEST_STRATEGY.md` §11 (valid/invalid input, mapped domain failures).
- The JavaFX UI continues to work unmodified (still calling application services in-process) — the API is additive at this point.

### High-Level Stories

- Decide and document the backend web framework.
- Stand up crafting-profit and crafting-discovery HTTP endpoints.
- Stand up sync/refresh trigger endpoints, with an explicit decision on the long-running-operation approach (`TARGET_ARCHITECTURE.md` §23 also marks this `TBD`).
- Define request/response DTOs and their mapping to/from domain objects.
- Add backend API contract tests.

---

## 9. Phase 5 — Frontend Migration

### Objective

Build the web frontend against the backend HTTP API, per `TARGET_ARCHITECTURE.md` §4.1/§12, without duplicating authoritative calculations client-side.

### Dependencies

Phase 4. The frontend needs a stable API to build against; building it earlier would mean guessing the contract.

### Exit Criteria

- **Explicit decision made** on frontend framework and language (`TARGET_ARCHITECTURE.md` §30 marks both `TBD`).
- The frontend renders crafting profit/discovery results, the resolution tree, and special domain states (e.g. `UNVALUED_NONTRADEABLE`, `PRICE_UNAVAILABLE`) using only backend-provided values (`TARGET_ARCHITECTURE.md` §13/§14).
- The frontend triggers sync operations via the API instead of reproducing them.
- The frontend does not independently recalculate crafting profit or any other authoritative domain result (`TARGET_ARCHITECTURE.md` §12).
- JavaFX UI and web UI coexist during this phase, both driven by the same backend.

### High-Level Stories

- Decide and document the frontend framework/language.
- Build the crafting profit screen (table + resolution tree view).
- Build the crafting discovery screen.
- Build bank/materials views and the Ectoplasm Salvage calculator screen.
- Build sync-trigger UI with status/progress display, matching the long-running-operation mechanism chosen in Phase 4.
- Add frontend tests focused on rendering/interaction/state (`TEST_STRATEGY.md` §12), not on recalculating domain results.

---

## 10. Phase 6 — PostgreSQL / Containerization

### Objective

Move from a manually-run local PostgreSQL instance and a manually-run desktop JVM to the target three-container model (frontend, backend, PostgreSQL) with reproducible schema management, per `TARGET_ARCHITECTURE.md` §3–4 and §16–18.

### Dependencies

Phase 4 at minimum (a backend process must exist to containerize). Phase 5 if the frontend container ships in the same milestone — the backend/database containers can be validated first while the frontend is still run locally, as a sequencing option, since the frontend does not have to wait on this phase's PostgreSQL specifics.

### Exit Criteria

- **Explicit decision made** on the database migration tool (`TARGET_ARCHITECTURE.md` §30 marks this `TBD`).
- The manually-executed `src/PostgreSQL Query to create DB` script is replaced by versioned, repeatable migrations (`TARGET_ARCHITECTURE.md` §16).
- Backend, frontend, and PostgreSQL each run in their own container.
- `docker compose up` starts all three (`TARGET_ARCHITECTURE.md` §17).
- PostgreSQL is not required to be exposed outside the internal Docker network (`TARGET_ARCHITECTURE.md` §18).
- A fresh environment can create the schema and run the application end-to-end without manually pasting SQL into `psql`.

### High-Level Stories

- Decide and document the database migration tool.
- Convert the manual SQL setup script into versioned migrations.
- Write a backend Dockerfile.
- Write a frontend Dockerfile (once Phase 5 exists).
- Write `docker-compose.yml` wiring frontend/backend/PostgreSQL on an internal network.
- Verify a fresh `docker compose up` reproduces a working environment from nothing.

---

## 11. Phase 7 — Deployment / Runtime Configuration

### Objective

Make the containerized system deployable outside the developer's own machine, per `TARGET_ARCHITECTURE.md` §15 and §19–21.

### Dependencies

Phase 6. Deployment configuration only matters once there is something containerized to deploy.

### Exit Criteria

- All configuration (GW2 API key, database URL/user/password, and any future settings) is supplied via environment variables at the container level — continuing the pattern already established for the desktop app's `.env`/`EnvConfig` (see `CLAUDE.md` § Project-Specific Security Policy), carried into `docker-compose` environment configuration.
- **Explicit decisions made, only when actually needed** (not speculatively) on: reverse proxy, hosting provider, and authentication (`TARGET_ARCHITECTURE.md` §20–21 explicitly warn against adding authentication or multi-tenancy merely because the app is now web-based).
- The application remains scoped to one configured GW2 account per instance unless a future requirement explicitly changes that (`TARGET_ARCHITECTURE.md` §21).

### High-Level Stories

- Carry `.env`-style configuration into container-level environment variables.
- Decide a reverse proxy approach, if the chosen hosting target needs one.
- Decide a hosting provider, only at the point of actually deploying.
- Explicitly decide authentication only if/when the deployment stops being strictly single-user/private; otherwise document "not required" and move on.

---

## 12. Phase 8 — Final Cleanup / JavaFX Removal

### Objective

Once the web frontend has functional parity and a deployable backend exists, retire the JavaFX desktop UI and any code it alone still uses — consistent with `TARGET_ARCHITECTURE.md` §28 (existing code is reused when sound, replaced when superseded, not thrown away speculatively).

### Dependencies

Phase 5 (frontend functional parity) and Phase 7 (a deployable system users can actually switch to).

### Exit Criteria

- Every JavaFX view's functionality is confirmed present in the web frontend (explicit parity checklist, not an assumption).
- JavaFX views, controllers in the default package, and the `Gw2App` entry point are removed.
- `Main.java` (superseded by the unified Ecto application service from Phase 3) is removed.
- JavaFX Maven dependencies and the `javafx-maven-plugin` are removed from `pom.xml`.
- No class remains in the default (unnamed) Java package.
- `CLAUDE.md`, `docs/CURRENT_STATE_SPEC.md`, and `docs/CURRENT_ARCHITECTURE.md` are updated to describe the web-only architecture.

### High-Level Stories

- Build and confirm a feature-parity checklist between JavaFX views and the web frontend (Bank, Materials, Ecto, Crafting Profit, Crafting Discovery, sync triggers).
- Remove JavaFX views/controllers and the `Gw2App` entry point.
- Remove `Main.java` and any other now-fully-superseded legacy code.
- Remove JavaFX dependencies and the `javafx-maven-plugin` from `pom.xml`.
- Update project documentation to reflect the web-only architecture.

---

## 13. Status

This roadmap reflects the repository as of the Java 25 / Maven migration and the craft-vs-buy domain fix (see `docs/KNOWN_PROBLEMS.md` and `docs/CURRENT_ARCHITECTURE.md` for the state it was derived from).

It should be revisited whenever a phase's exit criteria are fully met, or when a new domain/architecture decision materially changes what a later phase requires.
