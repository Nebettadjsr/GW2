## Story ID

STORY-API-007

## Title

Expose bank and material storage reads for the web frontend

## Status

DONE

## Milestone

milestone-05

## Goal

Provide the HTTP data needed by Phase 5's bank/materials views by exposing the existing application-service reads, preserving their results and keeping database access and material grouping in the backend.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md`, supplied Phase 5 objective and bank/materials-view high-level story: the browser consumes the backend HTTP API and coexists with JavaFX.
- `docs/TARGET_ARCHITECTURE.md` sections 8, 9 and 12: application-owned reads, thin HTTP adapters, example bank/materials routes and presentation-only frontend responsibilities.
- `docs/CURRENT_ARCHITECTURE.md` sections 4, 6 and 9: existing `BankContentsService` / `MaterialStorageService`, read-only persistence adapters and shared database configuration; section 5.10: existing read-only HTTP adapter and sanitized failure precedent.

## Context

The documented HTTP routes cover crafting calculations, selector options and synchronization; bank/material storage reads are currently documented through their JavaFX application-service paths. Phase 5 explicitly calls for browser bank/materials views. Exposing these existing reads is a bounded prerequisite independent of the unfinished `STORY-WEB-001` frontend table. Establish the response contract from the existing services rather than inventing new inventory behavior.

## Acceptance Criteria

1. Add read-only `GET /api/account/bank` and `GET /api/account/materials` routes to the existing Spring Boot runtime, following the route examples in target section 9. Each accepted request delegates exactly once to its corresponding existing application service. Neither route synchronizes data, writes inventory, calls GW2 directly or runs crafting calculations.
2. Return explicit transport DTOs containing the service-provided information needed to present the existing bank slots and material groups/items. Preserve identities, quantities, ordering, empty-slot representation and any existing display metadata supplied by the services. Record the actual fields and null/empty semantics in the contract documentation. Do not serialize repository rows or external GW2 models directly.
3. Preserve existing material category grouping, labels, fallback labels and inclusion rules by using the service's result. Do not reimplement filtering, grouping, inventory aggregation or category rules in either HTTP controller. Leave existing persistence queries and domain behavior unchanged.
4. Empty reads return successful empty results distinguishable from failed reads. Map data-store failures and unexpected failures to appropriate HTTP statuses with sanitized error DTOs, following the existing read-adapter convention; do not expose credentials, SQL or exception text. Preserve framework routing errors outside these routes.
5. JavaFX bank/materials flows continue using the same application services in process. Keep HTTP/Spring/transport dependencies outside those services and persistence/domain logic. No frontend code or active-story changes are required.
6. Document the implemented routes, DTO fields, service delegation and failure behavior in `docs/CURRENT_ARCHITECTURE.md`, and record actual verification and limits in this story's Result. This prerequisite does not claim the bank/materials browser views or Phase 5 complete.

## Required Tests

- HTTP contract tests with controlled application-service results: both routes delegate exactly once to the correct service; response fields preserve supplied values, order, empty slots and material groups without leaking persistence types.
- Cover empty success separately from data-store and unexpected failures; assert error bodies exclude sensitive exception details and unrelated routing still returns its framework error.
- Verify runtime wiring and compare representative endpoint results with the same application-service reads against a disposable PostgreSQL fixture or an available real database without mutating real user data. Record the environment and any unavailable verification explicitly.
- Run the relevant existing bank/materials service and persistence checks and JavaFX compatibility checks appropriate to any shared code touched; do not invent test names or claim checks not run.

## Constraints

- Implement only the HTTP prerequisite for Phase 5 bank/materials presentation. No frontend screen, inventory editing, new domain rule, synchronization workflow or broad persistence-boundary refactor.
- Reuse established Spring Boot and transport/error patterns. No new framework or technology decision.
- Secrets remain backend-only. Preserve existing application-service behavior and JavaFX compatibility.
- Do not modify `STORY-WEB-001` or `agent/CURRENT_STORY.md`; no dependency on completion of that active frontend slice.

## Dependencies

None. The existing HTTP runtime and bank/materials application services are established in `docs/CURRENT_ARCHITECTURE.md`.

## Definition of Done

Both read endpoints expose existing service results through documented transport contracts, with meaningful HTTP and service-equivalence evidence and preserved JavaFX compatibility. Result records checks actually performed and remaining limitations. No milestone completion is declared.

## Result

Both routes implemented over the existing application services, with no change to those services, to
`repo.*` or to any JavaFX file.

**Added (5 production files).**

- `web.BankContentsApiController` — `GET /api/account/bank`. One call to
  `BankContentsService.getBankContents()`, then a single `map` into transport records. No repository
  query, no sort, filter, dedup, defaulting or aggregation.
- `web.MaterialStorageApiController` — `GET /api/account/materials`. One call to
  `MaterialStorageService.getMaterialStorage()`, then a nested `map` over the categories the service
  already grouped. The category order, the labels, the `"Category <id>"` fallback and the
  "non-empty stacks only" rule stay in the service and are not restated at the boundary.
- `web.dto.BankContentsResponse` — `slotCount`, `slots[{slot, itemId, count, iconPath, rarity}]`.
  Empty slots are **kept in place** with `itemId`/`count` both `null` (dropping them would shift every
  following item into the wrong cell of the bank grid, which a caller could not undo);
  `iconPath`/`rarity` are `null` when the slot's item has no matching `items` row.
- `web.dto.MaterialStorageResponse` — `categoryCount`,
  `categories[{name, materials[{category, itemId, count, iconPath, rarity}]}]`. `count` is always
  present, since the service's read excludes empty stacks; `itemId` is `null` when the stored row
  carries none. The numeric `category` id is reported deliberately: it is the only way a caller can
  see which id produced a fallback label.
- `web.AccountReadApiExceptionHandler` — `SQLException` → 503 `DATA_STORE_UNAVAILABLE` (the same code
  and message as the calculation and selector routes, since it means the same thing); anything else →
  500 `ACCOUNT_READ_FAILED`. Both messages fixed, detail logged server-side only,
  `assignableTypes`-scoped to the two controllers so framework 404/405 elsewhere is unaffected. One
  advice for both routes rather than one each, because they fail the same way for the same reasons and
  a caller already knows which of them it called.

**Modified (2 Java files, no behavior change elsewhere).** `web.Gw2ApiApplication` gained two shared
beans (`BankContentsService`, `MaterialStorageService` — neither keeps per-call state and each
repository opens its connection inside the call, so no per-request factory is needed and no connection
opens at startup); `web.Gw2ApiApplicationBootTest` gained one wiring test.

**Verification actually run** (`./mvnw`, evidence lines quoted, not exit codes):

- `BankContentsApiControllerTest` (7) + `MaterialStorageApiControllerTest` (8) — **15 tests, 0
  failures**. Both stub the service by subclassing it (its constructor is inert) and cover:
  exactly-once delegation; values, ordering and display metadata preserved; **JSON field names
  asserted to be exactly the documented sets**, so a serialized repository row would fail; an empty
  slot's `itemId`/`count` asserted to be JSON null rather than `0`; a stack with no item id likewise;
  the service's category order and fallback label passing through unchanged; empty success (`0` count,
  empty list, no invented slot or category) separately from failures; `SQLException` → 503 with the
  body asserted free of `jdbc` and of a credential; `IllegalStateException` → 500 with the body
  asserted free of SQL and of exception text; an unknown sub-path still 404 with the service **not**
  called.
- `Gw2ApiApplicationBootTest` (5) + `application.BankContentsServiceTest` (3) +
  `application.MaterialStorageServiceTest` (5) — **13 tests, 0 failures**. The context boots, binds a
  port and exposes both controllers and both service beans; the services are never invoked there, so
  no connection opens.
- `AccountReadApiRealDbEquivalenceIT` (new, `*IT` so the default goal excludes it) — **2 tests, 0
  failures** against the **user's real local PostgreSQL** on a random port, both paths read-only with
  no synchronization in between. Printed evidence: **`180 bank slots (17 empty) equivalent between
  HTTP and in-process calls`** and **`9 material categories / 505 stacks equivalent between HTTP and
  in-process calls`**, compared field by field including null-vs-null (nulls are read through a
  null-returning helper, since `asInt()` on a JSON null would return `0` and silently agree with a
  substituted zero). The 17 real empty slots mean the empty-slot contract was exercised on real data,
  not only on fixtures.
- Full default suite on the final tree — **286 tests, 0 failures, `BUILD SUCCESS`**.
- JavaFX/persistence compatibility — `BankViewIT` (2), `MaterialsViewIT` (2),
  `repo.BankRepositoryTest` (4), `repo.MaterialStorageRepositoryTest` (5): **13 tests, 0 failures**,
  run explicitly on the final tree. Both views still call the same services in process; a grep over
  `src/main/java` confirms no class outside `web/` imports Spring, a servlet type or `web.*`.

**Limitations.**

- Equivalence was verified against the **real local database, read-only** — no disposable-PostgreSQL
  fixture variant of it was added. The story allowed either; the real database is what the running
  application reads, so it is the stronger of the two, but it also means the numbers above describe
  one developer's data and an empty database was only covered by stubbed contract tests.
- The 503 and 500 paths were exercised only through stubbed failures. No real failing database or
  forced runtime failure was driven through a running server.
- No frontend consumes the routes; no browser view, pagination or caching was built, and the payload
  is unpaged (180 slots / 505 stacks here). Response timing was not measured, and no
  `TARGET_ARCHITECTURE.md` §33 claim is made for these routes.
- The DTOs carry no item **name** — neither service supplies one, and inventing a lookup at the
  boundary would have been the reimplementation criterion 3 forbids. A browser view will need either
  an item-detail route or a service change.
- These are unauthenticated, like every existing route (`TARGET_ARCHITECTURE.md` §20 TBD). They add no
  write, but they are the first routes returning stored account inventory itself rather than a
  calculation over it; recorded as a factual widening of `docs/KNOWN_PROBLEMS.md` §7.10, not as a new
  decision.

**Documentation.** `docs/CURRENT_ARCHITECTURE.md` §2, §3, §4 and new **§5.12** (routes, DTO fields,
null/empty semantics, service delegation, status mapping, JavaFX unchanged); `docs/KNOWN_PROBLEMS.md`
§7.10 (read-surface widening); `docs/TEST_STRATEGY.md` §35.2 (one methodology bullet: compare a
nullable field's JSON null against the source null explicitly, and print how many rows carried one).
No bank/materials browser view and no Phase 5 exit criterion, milestone transition or phase completion
is claimed.

## Blockers

None.
