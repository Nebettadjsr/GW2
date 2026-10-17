## Story ID

STORY-API-006

## Title

Expose crafting selector options for the browser UI

## Status

DONE

## Milestone

milestone-05

## Goal

Let the browser obtain the existing crafting character/discipline options through the backend HTTP boundary, so the Phase 5 Crafting Profit screen can submit supported scopes without direct database access or reproducing eligibility rules.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: frontend against the backend HTTP API; Crafting Profit screen; JavaFX/web coexistence.
- `docs/TARGET_ARCHITECTURE.md` sections 9 and 12: thin HTTP boundary and frontend responsibilities.
- `docs/DOMAIN_SPEC.md` section 2.2.1: Profit's sole Discipline scope selector, All default and character-specific entries.
- `docs/CURRENT_ARCHITECTURE.md` sections 4, 5.1 and 5.2: existing `application.CharacterSelectionService` and its selector read operations.

## Context

The supplied completed API work exposes calculations and synchronization. Current architecture describes crafting selector reads through an existing application service, while the browser needs HTTP access to those facts. Inspect the implementation before adding a route and reuse any equivalent existing route. This is the supporting API slice of the Phase 5 screen migration.

## Acceptance Criteria

1. A documented read-only HTTP contract returns the existing crafting character/discipline/rating options needed to construct Profit's scopes, delegating to `CharacterSelectionService`. Preserve service-provided facts and ordering; do not add eligibility calculations or query repositories from a controller.
2. Return transport DTOs containing only browser-needed data; do not serialize repository objects, JavaFX types or GW2 response models. Document the route, fields, successful empty result and failure responses at the current-architecture owner.
3. A database with no synced characters returns a successful empty character-option collection. All and generic discipline scope choices retain the established semantics; no character is invented as a fallback.
4. Failures use the established sanitized HTTP error conventions without exposing credentials, SQL or exception text. Existing calculation routes and JavaFX selector calls remain compatible.

## Required Tests

- HTTP contract tests with controlled application-service results: multiple character/discipline/rating entries, preserved values/order, empty result and mapped failures.
- Verify delegation and transport isolation; a returned option must contain the facts needed by the existing Profit scope request contract.
- Run relevant existing HTTP and selector-service checks; use a targeted JavaFX regression only if its shared behavior changes.

## Constraints

- No new domain rules, synchronization workflow, authentication design or persistence redesign.
- Keep JavaFX using the same application-service implementation in process.
- Endpoint naming and DTO mapping are routine implementation details within the established HTTP boundary.

## Dependencies

None.

## Definition of Done

The read contract is implemented, tested and documented; the browser can populate Profit's character-specific options without an external-system bypass. Record actual verification and any limitations in Result.

## Result

DONE. `GET /api/crafting/selector-options` returns the crafting scope options over the existing
`application.CharacterSelectionService` — the same read the two JavaFX selectors already use.

**No equivalent existing route.** The five Phase 4 routes were inspected first: the two calculation
routes *consume* a scope and none of the five reads one, so the route is new rather than a reuse.

**Implementation (5 files, all additive).**

- `web.CraftingSelectorOptionsApiController` — one `GET`, no body and no parameter. Calls
  `CharacterSelectionService.getCraftingCharacterOptions()` once and runs a single `map` over the
  rows: no sort, filter, dedup, defaulting or eligibility test, so the service's values and its
  `discipline, rating DESC, name` order arrive verbatim. It queries no repository.
- `web.dto.CraftingSelectorOptionsResponse` — transport record: `defaultScopeKind`, `disciplines`,
  `characterOptionCount`, `characterOptions[{characterName, discipline, rating, active}]`. Primitives
  and strings only; `repo.CharacterRepository.DiscRow` is read to copy it and never serialized, and
  no JavaFX or GW2 response type is reachable from `web.dto`.
- `web.CraftingSelectorOptionsApiExceptionHandler` — `SQLException` → 503 `DATA_STORE_UNAVAILABLE`
  with the *same* code and message as the calculation routes (it means the same thing); anything else
  → 500 `SELECTOR_OPTIONS_FAILED`. Both messages fixed, detail logged server-side. A separate advice
  for the reason `SyncApiExceptionHandler` already records — `ApiExceptionHandler`'s 500 says a
  *calculation* failed, which is wrong for a selector read, and the calculation routes' established
  contract was not reworded. Scoped with `assignableTypes`, so framework 404/405 is unaffected.
- `web.Gw2ApiApplication` — one `CharacterSelectionService` bean, shared rather than per-request:
  it keeps no per-call state, so there is no reload result for concurrent callers to observe.

**Two deliberate judgment calls, both recorded in `CURRENT_ARCHITECTURE.md` §5.10.**

1. *The nine generic disciplines are in the response*, sourced from
   `CraftingSelectorOptionsApiController.GENERIC_DISCIPLINES`. Without them a browser could only
   offer disciplines someone happens to have synced, i.e. it would have to reproduce the list itself
   — the thing AC 1/3 exist to prevent. The list is the JavaFX view's presentation list, not a fact
   any application service reports, so it is mirrored at the boundary exactly as STORY-API-001
   mirrored that view's setting defaults. `defaultScopeKind` is read from
   `CraftingProfitApiMapper.DEFAULT_SCOPE_KIND`, so the two routes cannot disagree about `ALL`.
2. *`active` is reported*, not dropped. It is a service-provided fact the caller cannot re-derive;
   no rule is applied to it. The other three fields are exactly a `CHARACTER_DISCIPLINE` scope.

**Verification.**

- New `web.CraftingSelectorOptionsApiControllerTest`, 7 tests, application service stubbed:
  multi-entry read with values/order/count preserved and the full discipline list asserted;
  option JSON field names asserted to be exactly the four documented ones (a serialized repository
  row would fail); a returned option fed through `CraftingProfitApiMapper.toEffective(...)` proving
  it yields `DiscChoice.CHAR_DISCIPLINE` with the same discipline/character/rating — i.e. it carries
  what the existing Profit scope contract needs; empty database → 200, count 0, empty list, `ALL` and
  the nine disciplines intact, no invented character; `SQLException` → 503 with the body asserted
  free of `jdbc` and of a credential; `IllegalStateException` → 500 with the body asserted free of
  SQL and of exception text; unknown sub-path still 404.
- `Gw2ApiApplicationBootTest` gained one test: the route and the service bean are wired in the real
  Spring context (4 tests pass; the service is never invoked, so no connection is opened).
- `./mvnw test` on the final tree: **270 tests, 0 failures, 0 errors, `BUILD SUCCESS`**. The web
  package plus `application.CharacterSelectionServiceTest` were also run targeted (97 pass).
- Compatibility: no existing main or test file was modified except `Gw2ApiApplication` (bean added)
  and `Gw2ApiApplicationBootTest` (assertion added). `CharacterSelectionService`,
  `CraftingProfitView`, `CraftingDiscoveryView` and the calculation routes are untouched, and the
  existing Profit/Discovery contract tests (17 + 20) still pass unchanged.

**Limitations.** The route was never exercised against the real database or a running server — the
tests stub the application service, as the other contract tests do. No `*RealDbIT` was added: the
read is a single existing `SELECT` already covered by the JavaFX ITs, and §23/§33 timing is not at
issue for it. The JavaFX selector ITs (`CraftingProfitViewSelectorIT`,
`CraftingDiscoveryViewSelectorIT`) were not run — they need a desktop session and Postgres, and no
file they touch changed. If the JavaFX view's base discipline list is ever edited,
`GENERIC_DISCIPLINES` must be edited with it; nothing enforces that today, which is the cost of
mirroring rather than relocating a JavaFX presentation constant.

## Blockers

None.
