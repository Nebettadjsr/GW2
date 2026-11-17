## Story ID

STORY-API-005

## Title

Expose Trading Post price refresh through HTTP background tasks

## Status

DONE

## Milestone

milestone-04

## Goal

Expose the existing Profit and Discovery Trading Post price-refresh variants through a thin asynchronous HTTP contract, preserving their application-owned behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md supplied Phase 4 section: sync/refresh endpoints, DTO mapping, thin controllers, contract tests, JavaFX compatibility and performance preservation.
- docs/TARGET_ARCHITECTURE.md sections 9, 22, 23, 30 and 33: price-refresh endpoint example, backend orchestration, asynchronous GW2 synchronization policy, framework and performance requirement.
- agent/user-decisions/UD-007-http-long-running-sync-approach.md: resolved synchronization policy.
- agent/stories/STORY-API-003-account-sync-task-endpoint.md: reusable task/status contract and remaining price-refresh work.
- docs/CURRENT_ARCHITECTURE.md sections 5.4, 5.7 and 6: TradingPostPriceRefreshService variants, task boundary and existing JavaFX callers.

## Context

The supplied completed-story evidence establishes TradingPostPriceRefreshService.refreshForProfit() and refreshForDiscovery() as distinct existing use cases. Neither is a calculation request. They synchronize GW2 prices and therefore fall under the decided asynchronous policy without speculative duration measurement. STORY-API-003 supplies the reusable task mechanism.

## Acceptance Criteria

1. Provide an explicit HTTP way to request each existing refresh variant, using the section 9 POST /api/prices/refresh example with a validated variant or equivalent documented routes. Require an unambiguous variant; do not silently combine the workflows or introduce an implicit all-prices operation.
2. Each accepted request submits exactly one invocation of the corresponding TradingPostPriceRefreshService method. Price/item selection, fetching and persistence remain inside the existing application/infrastructure boundaries; routes contain no synchronization or calculation rules.
3. Return 202 using the established task identifier, operation, status URL and Location contract. Reuse the task/status facility and mapped failures. Acceptance does not imply successful refresh, and failed status does not imply rollback or unsupported progress information.
4. Reject malformed or unsupported input before task admission with no service invocation. Use transport-only DTOs without GW2 JSON, persistence/domain objects or internal exception details leaking into responses.
5. Apply the existing admission policy consistently, document operation keys and concurrency behavior, and assess concrete shared-write interactions before choosing variant keys. Preserve existing workflows; if evidence reveals a material unresolved consistency/product decision, use the user-decision workflow instead of guessing or adding a scheduling system.
6. Keep account/global task status and calculation HTTP behavior compatible. JavaFX price-refresh callers continue working in-process unmodified. Preserve the accepted Crafting Profit performance requirement and verify affected shared calculation paths where warranted.

## Required Tests

- Contract tests covering both variants, accepted response/status location, validation failures and zero admitted work for rejected requests.
- Controlled collaborators prove exactly-once invocation of the selected variant, no invocation of the other variant, asynchronous response while refresh is blocked, successful/failed status, mapped failures and duplicate admission behavior.
- Relevant existing price-refresh application and JavaFX checks, task/status regressions and calculation contracts affected by shared changes. Verify real runtime wiring without requiring live GW2 mutation for protocol tests.
- Target any shared-runtime or calculation-path change with the existing relevant correctness/performance checks; record actual conditions and distinguish backend request timing from the full-page budget.

## Constraints

Limit work to existing price-refresh variants and their HTTP translation. No backend scheduling, new consistency architecture, automatic retries, queue platform, new domain rules, first-time setup endpoint or later-milestone work. Reuse existing task infrastructure and service orchestration. Do not infer operation durations, atomic rollback or test outcomes.

## Dependencies

STORY-API-001 and STORY-API-003 (DONE). Execute after STORY-API-004 to keep shared sync-boundary changes sequential.

## Definition of Done

Both refresh variants are exposed with tested delegation, validation and task reporting. Result records contract, admission choices, verification evidence and limitations; update current architecture and testing methodology at their owners. Leave milestone closure to normal planning after the required bounded Phase 4 health review.

## Result

Done. `POST /api/prices/refresh` with a required `variant` accepts either existing Trading Post
price-refresh variant as a background task, and the existing `GET /api/sync/tasks/{taskId}` reports its
outcome. `application.TradingPostPriceRefreshService`, `sync.*` (including `TpSync` and the relevance
collectors), `repo.*` and every JavaFX file are byte-for-byte unchanged, as is the whole of `web.task.*`.

### 1. The HTTP contract

Prose contract lives in `docs/CURRENT_ARCHITECTURE.md` §5.9 (its owner); summary:

| Request | Result |
|---|---|
| `POST /api/prices/refresh` `{"variant":"PROFIT"}` | **202** `{taskId, operation: "PRICE_REFRESH_PROFIT", statusUrl}` + `Location: /api/sync/tasks/{taskId}` |
| same, `{"variant":"DISCOVERY"}` | **202**, `operation: "PRICE_REFRESH_DISCOVERY"` |
| no body, `{}`, blank/absent variant | **400** `INVALID_REQUEST` — no task created |
| unsupported variant (`ALL`, `profit`, `5`, …) | **400** `INVALID_REQUEST` — no task created |
| any other field (`itemIds`, …) | **400** `INVALID_REQUEST` naming the field — no task created |
| unreadable JSON | **400** `MALFORMED_REQUEST` — no task created |
| same variant while one is unfinished | **409** `SYNC_ALREADY_RUNNING`, message naming the running task |
| `GET /api/sync/tasks/{id}` | unchanged: **200** status body, **404** `TASK_NOT_FOUND` |

The route is §9's own `POST /api/prices/refresh` example; no implementation reason to deviate arose.

**The variant is required and there are exactly two.** `PROFIT` → `refreshForProfit()`,
`DISCOVERY` → `refreshForDiscovery()`. There is deliberately **no combined or implicit all-prices
value**: the two workflows select different item sets for different pages, so a request naming neither
would have to invent a third workflow at the HTTP boundary. `ALL` is rejected by the same rule as a typo,
and that rejection is asserted by a test so the absence is recorded rather than accidental.

**Acceptance is not completion.** The 202 body carries `taskId`/`operation`/`statusUrl` and no outcome
field of any kind — asserted by a test. The outcome is only ever readable from the status route.

**Asynchronous by decision, not by measurement.** `UD-007` / `TARGET_ARCHITECTURE.md` §23: both variants
call the rate-limited GW2 commerce endpoint for synchronization. No duration was measured or assumed, and
none is claimed.

### 2. Reuse, which is again most of this story

The task/status boundary needed no extension to carry a third and fourth operation key. Unchanged, not one
line edited: `web/task/BackgroundTaskService.java`, `TaskState`, `TaskSnapshot`,
`TaskAlreadyRunningException`, `SyncTaskApiController`, `SyncTaskStatusMapper`, `UnknownTaskException`,
`web/dto/SyncTaskAcceptedResponse`, `web/dto/SyncTaskStatusResponse`, `AccountSyncApiController`,
`GlobalSyncApiController`. `SyncApiExceptionHandler` gained one entry in its `assignableTypes` list (and a
javadoc line) — no new handler, no new code. The status route is shared by operation key, so this trigger
adds no status route of its own.

`web.SyncRequestValidation` gained a second method, `rejectUnknownRequestFields(route, request,
allowedField)`, because this is the first sync/refresh route that accepts a field at all. The existing
`rejectAnyRequestField(...)` is untouched and both sync triggers keep using it; the account route's exact
message is still asserted by its own untouched test and was re-confirmed live (§6).

### 3. Delegation to the existing use cases

`PriceRefreshApiController` validates, resolves the variant, submits **one** method reference as the task
body, and returns the identifier. `web.PriceRefreshVariant` owns that mapping, so the controller holds no
`if`-chain over workflows. Item relevance (`sync.tp.relevance.CraftingProfitItemCollector` /
`DiscoveryItemCollector`, including their ten-minute freshness filter), the batching, the quote fetching
and the `tp_prices` persistence all stay where they already were — `web/*` contains no `sync.*` or `repo.*`
import, and no calculation runs on this path.

`TradingPostPriceRefreshService` is wired as one shared bean, for the same reason as the other two use
cases: it keeps no per-call state, so there is nothing for concurrent callers to observe, and constructing
it opens no connection.

### 4. The operation keys and the shared-write assessment

**`PRICE_REFRESH_PROFIT` and `PRICE_REFRESH_DISCOVERY`** — one key per variant, the facility's existing
per-operation rule applied unchanged. A second submission of the *same* variant is 409; the two variants
are independent of each other and of `ACCOUNT_SYNC`/`GLOBAL_SYNC`.

The key choice was made against the concrete shared write rather than by analogy:

- Both variants write **the same table**, `tp_prices`. So two keys is not a claim that they never touch the
  same row.
- The write is `INSERT … ON CONFLICT (item_id) DO UPDATE` (`sync.TpSync.upsertTpPrices`) and **there is no
  `DELETE` anywhere on the path** — verified by inspection. Overlapping runs can therefore only overwrite a
  row with another freshly fetched quote for that same `item_id`; they cannot lose a row, mix two items'
  quotes or leave a partially written one.
- The item sets are selected independently and may overlap.
- **This concurrency is already reachable today without this route**: both JavaFX "Refresh Trade Post
  Prices" buttons exist and neither excludes the other. A single shared key would have been a *new*
  restriction, not a preserved one.
- What the two runs do contend for is the GW2 commerce rate budget and the database, so running both at
  once makes each slower.

On that evidence no material unresolved consistency decision surfaced, so no user decision was raised and
no cross-operation exclusion, scheduling or ordering was introduced.

### 5. Transport DTOs and failure reporting

The existing `web.dto.SyncTaskAcceptedResponse`/`SyncTaskStatusResponse` and the existing mapped failures,
reused unchanged. No `application.*`, `sync.*`, `repo.*` or GW2-API model is serialized, and `TaskSnapshot`
still never reaches the wire. `SQLException` → the shared `DATA_STORE_UNAVAILABLE`, anything else →
`SYNC_FAILED`; the exception's own text is logged server-side only — tests place a GW2 commerce endpoint
and a JDBC URL into simulated failures and assert both are absent from the response.

Two consequences of the thin delegation are stated in the contract rather than hidden:

- **A succeeded task does not mean prices were fetched.** Each collector skips items whose stored quote is
  younger than ten minutes, so a refresh issued shortly after another can legitimately select no items and
  succeed having called the GW2 API not at all. The service reports no item count, so neither does the
  route.
- **A failed task says nothing about how much was written.** The refresh commits per fetched batch
  (`sync.TpSync`, `con.commit()` inside the batch loop), so earlier batches stay committed — exactly what
  the shared `"steps that had already completed were not rolled back"` wording says, and no more. How many
  items were refreshed before the failure is not something the service exposes; manufacturing a count at
  the boundary would duplicate its orchestration.

### 6. Tests and verification

**Default `./mvnw test` run — 262 tests, 0 failures, 0 errors** (246 pre-existing, all passing unchanged;
16 new). Counts read from `target/surefire-reports`, not from the exit code.

- `web.PriceRefreshApiControllerTest` (16) — 202 body and `Location` per variant under its own operation
  key; acceptance body carries no outcome; **each variant calls its own use-case method exactly once and
  the other one zero times**; variant required, unsupported values (`"ALL"`, `"profit"`, `""`, `5`),
  unexpected field and unreadable body each rejected **with zero delegations of either variant and no task
  admitted**; **the trigger returning while `refreshForProfit()` is still blocked at a latch, with the
  advertised status path already answering `PRICE_REFRESH_PROFIT`/`RUNNING`/no `finishedAt`/no
  `failure`, then reaching `SUCCEEDED` after release**; second same-variant trigger 409 naming the running
  task without a second delegation; **an unfinished profit refresh not blocking the discovery variant**,
  which runs to completion while the profit one is still latched; a new refresh accepted once the previous
  finished, with two delegations across two tasks; `SYNC_FAILED` and `DATA_STORE_UNAVAILABLE` mapping with
  no leaked endpoint/JDBC detail and no completion wording.
- `web.Gw2ApiApplicationBootTest` (3) — the price-refresh trigger and its use-case bean wired into the real
  context alongside the existing ones. Starts no task, so no GW2 API call.

The service is substituted by a controlled subclass of the real `TradingPostPriceRefreshService` through
the controller's constructor seam, with **separate counters and latches per variant** — which is what makes
"the other variant was never refreshed" observable and what lets one variant be held while the other
completes. The real task facility is used rather than a stub, since the asynchrony and the admission rule
are the thing under test. Determinism: latches, not sleeps; terminal states awaited with a bounded poll
that fails naming the task. Methodology added to `TEST_STRATEGY.md` §35.3.

**Existing checks re-run for acceptance criterion 6:**

- `./mvnw test -Dtest=CraftingProfitViewTpRefreshIT,CraftingDiscoveryViewTpRefreshIT` — 4 tests, passing:
  both JavaFX "Refresh Trade Post Prices" buttons still call their variant in process, unmodified.
- `application.TradingPostPriceRefreshServiceTest` (4), `web.AccountSyncApiControllerTest` (9),
  `web.GlobalSyncApiControllerTest` (12), `web.SyncTaskApiControllerTest` (7),
  `web.task.BackgroundTaskServiceTest` (8) — passing unchanged in the default run: the two variants' own
  application-level evidence and the account/global/task contracts intact after the shared-file edits.
- `./mvnw test -Dtest=CraftingProfitApiRealDbEquivalenceIT,CraftingDiscoveryApiRealDbEquivalenceIT` — 4
  tests, passing against the real database. Run because the shared entry point gained a bean and the shared
  advice changed scope.
- `./mvnw test -Dtest=CraftingProfitApiRealDbPerfIT` — passing; real database, 3151 rows, 1,305,944-byte
  response. FIRST 1794 ms (cold JVM/JIT and caches), REPEAT 1060 / 921 / 762 ms, **maximum 1794 ms** — in
  the same band as `STORY-API-004`'s 1672 ms and `STORY-API-003`'s 2344 ms on the same check, and far
  inside §33's 7000 ms budget. Nothing in the Profit request path was modified; the run differs only by
  beans a Profit request never touches. As in all three predecessors, this is **backend request time only
  and is not proof of the §33 full-page budget.**

**Live verification against a really running server** (`GW2_API_PORT=18083 ./mvnw spring-boot:run`,
`Tomcat started on port 18083` / `Started Gw2ApiApplication in 1.327 seconds`), all non-mutating:

| Request | Observed |
|---|---|
| `POST /api/prices/refresh`, no body | 400 `INVALID_REQUEST`, "variant is required: … one of PROFIT, DISCOVERY must be named" |
| `POST /api/prices/refresh` `{"variant":"ALL"}` | 400 `INVALID_REQUEST`, "variant must be one of PROFIT, DISCOVERY" |
| `POST /api/prices/refresh` `{"variant":"profit"}` | 400 `INVALID_REQUEST` |
| `POST /api/prices/refresh` `{"variant":"PROFIT","itemIds":[1,2]}` | 400 `INVALID_REQUEST`, "accepts only the variant field; unexpected: itemIds" |
| `POST /api/prices/refresh` `{not json` | 400 `MALFORMED_REQUEST` |
| `GET /api/prices/refresh` | 405 (framework; the advice did not swallow it) |
| `POST /api/prices/nope` | 404 (framework) |
| `POST /api/sync/account` `{"scope":"ALL"}` / `POST /api/sync/global` `{"rebuildGraph":false}` | 400 `INVALID_REQUEST`, messages byte-identical to their stories' — unaffected |
| `GET /api/sync/tasks/7b21e4c0-…` | 404 `TASK_NOT_FOUND` |
| `POST /api/crafting/profit` | 200, 1,305,944 bytes — existing route unaffected |

The accepted path was deliberately **not** triggered live: it would fetch from the GW2 commerce API and
upsert the user's real `tp_prices`.

### 7. Files

New: `src/main/java/web/{PriceRefreshApiController,PriceRefreshVariant}.java`,
`src/test/java/web/PriceRefreshApiControllerTest.java`.

Modified: `src/main/java/web/Gw2ApiApplication.java` (one bean),
`src/main/java/web/SyncRequestValidation.java` (one added method; existing rule untouched),
`src/main/java/web/SyncApiExceptionHandler.java` (advice scope + javadoc),
`src/test/java/web/Gw2ApiApplicationBootTest.java`.
Docs: `docs/CURRENT_ARCHITECTURE.md` (§2 layout, §3 responsibilities, §4 dependency direction, new §5.9),
`docs/TEST_STRATEGY.md` (§35.3, three added methodology bullets), `docs/KNOWN_PROBLEMS.md` (§7.10 widened
to the third and fourth write-triggering route). `pom.xml` needed no change; `TARGET_ARCHITECTURE.md` was
deliberately not edited — §9's route name and §23's policy are unchanged by this story, and the implemented
contract is current-state fact owned by `CURRENT_ARCHITECTURE.md`.

### Limitations

- **No item-level progress or count.** A `SUCCEEDED` refresh does not say how many items were refreshed, or
  whether any were — the freshness filter can legitimately select none. The application service exposes no
  such signal and inventing one at the boundary was out of scope.
- **The accepted path was not exercised against the live GW2 API**, by choice — it would fetch commerce
  quotes and upsert the user's real `tp_prices`. Acceptance, the blocked-call asynchrony, per-variant
  delegation, every state and every error are proven by contract tests with a controlled service;
  rejection/404/405 were additionally confirmed against a running server. What remains unverified
  end-to-end is one real `refreshForProfit()`/`refreshForDiscovery()` executing inside a task — the same
  calls the JavaFX buttons exercise, invoked through a method reference.
- **Concurrent profit + discovery refresh is permitted but never executed for real.** The independence is
  proven at the task level with controlled services; the `tp_prices` argument above rests on the SQL and the
  absence of a `DELETE`, not on an executed concurrent run against the live API.
- **No task cancellation, no progress percentage, no retry, no persistence or distributed execution, no
  scheduling** — none asked for, none built; consequences are `STORY-API-003`'s documented ones, unchanged.
- **Unauthenticated**, now with four independently startable write operations. Recorded in
  `KNOWN_PROBLEMS.md` §7.10; authentication stays an undecided architecture question
  (`TARGET_ARCHITECTURE.md` §20) and was deliberately not introduced here.
- **Milestone not declared complete.** The bounded Phase 4 PROJECT HEALTH REVIEW
  (`STORY-QUALITY-004`) remains open, and closure is planning's call.

## Blockers

None.
