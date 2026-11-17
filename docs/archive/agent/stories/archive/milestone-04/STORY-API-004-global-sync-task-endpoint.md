## Story ID

STORY-API-004

## Title

Expose global synchronization as an HTTP background task

## Status

DONE

## Milestone

milestone-04

## Goal

Expose the existing global-data refresh use case through a thin asynchronous HTTP endpoint, reusing the task and status boundary established by STORY-API-003.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md supplied Phase 4 section: sync/refresh endpoints, thin controllers, transport DTOs, API tests and additive JavaFX compatibility.
- docs/TARGET_ARCHITECTURE.md sections 9, 22, 23, 30 and 33: HTTP boundary, backend orchestration, decided asynchronous GW2 synchronization policy, framework and performance preservation.
- agent/user-decisions/UD-007-http-long-running-sync-approach.md: resolved synchronization policy.
- agent/stories/STORY-API-003-account-sync-task-endpoint.md: reusable task/status contract and remaining global-sync work.
- docs/CURRENT_ARCHITECTURE.md sections 5.4 and 5.7: existing global refresh and HTTP task boundaries.

## Context

The supplied completed-story evidence establishes application.GlobalDataRefreshService.refreshAll() as the owner of tradeable-item synchronization, global recipe synchronization and crafting-graph rebuild sequencing. Account synchronization already has task admission, transport DTOs and a status route. Global synchronization remains unexposed according to STORY-API-003's Result.

## Acceptance Criteria

1. Add a global-sync trigger, using the section 9 example POST /api/sync/global unless a concrete implementation reason warrants another documented route. It submits exactly one GlobalDataRefreshService.refreshAll() invocation per accepted request and returns 202 with the established task identifier, operation, status URL and Location contract, without implying completion.
2. Reuse the existing task/status facility. The advertised status route distinguishes running, successful and failed global tasks and retains existing unknown-task semantics. Repeated unfinished submissions for the same operation are rejected under the existing admission policy; document the operation key and interactions with other task types.
3. Keep synchronization selection, step order, persistence, graph rebuild and first-failure behavior inside the existing application service. No controller calls individual sync steps or reconstructs the workflow.
4. Validate requests before task admission, following the parameterless account-sync precedent. Invalid fields or malformed bodies trigger no task and no service invocation. Record the concrete HTTP contract.
5. Use transport DTOs and the existing mapped task failures, without serializing domain, persistence or GW2 API models or exposing internal exception details. Failure must not claim rollback or step-level progress the service cannot establish.
6. Preserve account-sync and calculation HTTP contracts and the JavaFX in-process global-sync behavior. Preserve section 33 performance; target any shared-runtime change that could affect calculation timing with relevant verification.

## Required Tests

- HTTP contract checks for accepted requests, malformed/unexpected input, duplicate admission, task lookup and mapped service failures, including no internal-detail disclosure.
- A controlled blocked service proves that the trigger returns and status is queryable before the service completes; assert exactly-once delegation and zero delegation for rejected requests.
- Verify endpoint wiring and relevant existing account-task/calculation contracts affected by shared changes. Reuse global-refresh service and JavaFX global-sync checks to establish preserved in-process behavior.
- Use controlled collaborators for task protocol tests; live GW2 mutation is not required. Record verification conditions and limitations. Reverify real-data calculation performance when shared changes affect that path; never present API timing as full-page timing.

## Constraints

Scope is the global-sync HTTP boundary. Preserve existing service semantics and JavaFX callers. No scheduling, distributed queue, new graph algorithm, first-time setup endpoint, authentication design or later-milestone work. Reuse the decided task approach without inventing duration measurements. Material product/architecture choices discovered during implementation require the normal user-decision workflow.

## Dependencies

STORY-API-001 and STORY-API-003 (DONE).

## Definition of Done

The endpoint, task delegation and targeted checks satisfy the criteria. Result records the concrete contract, evidence and limitations. Update current architecture and testing methodology documentation for implemented changes at their existing owners. Do not declare milestone completion; the Phase 4 health review remains required.

## Result

Done. `POST /api/sync/global` accepts a global-data synchronization as a background task and the
existing `GET /api/sync/tasks/{taskId}` reports its outcome. `application.GlobalDataRefreshService`,
`application.CraftingGraphRebuildService`, `sync.*`, `repo.*` and every JavaFX file are byte-for-byte
unchanged, as is the whole of `web.task.*`.

### 1. The HTTP contract

Prose contract lives in `docs/CURRENT_ARCHITECTURE.md` §5.8 (its owner); summary:

| Request | Result |
|---|---|
| `POST /api/sync/global`, no body or `{}` | **202** `{taskId, operation: "GLOBAL_SYNC", statusUrl}` + `Location: /api/sync/tasks/{taskId}` |
| same, with any request field | **400** `INVALID_REQUEST` — no task created |
| same, unreadable JSON | **400** `MALFORMED_REQUEST` — no task created |
| same, while a global sync is unfinished | **409** `SYNC_ALREADY_RUNNING`, message naming the running task |
| `GET /api/sync/tasks/{id}`, known id | **200** `{taskId, operation, state, submittedAt, startedAt, finishedAt, failure}` |
| `GET /api/sync/tasks/{id}`, unresolvable id | **404** `TASK_NOT_FOUND` |

The route is §9's own `POST /api/sync/global` example; no implementation reason to deviate arose.

**Acceptance is not completion.** The 202 body carries `taskId`/`operation`/`statusUrl` and no outcome
field of any kind — asserted by a test. The outcome is only ever readable from the status route.

**Validation** follows the parameterless account-sync precedent exactly, and now literally shares it:
the body must be absent or an empty JSON object, any field is rejected rather than silently ignored.
Both 400s are raised before `submit(...)`, so a rejected request admits no task and synchronizes
nothing — proven by zero delegations *and* by a following request being accepted rather than 409.

**Asynchronous by decision, not by measurement.** `UD-007` / `TARGET_ARCHITECTURE.md` §23. No duration
was measured or assumed, and none is claimed.

### 2. Reuse, which is most of this story

The task/status boundary needed no extension to carry a second operation. Unchanged, not one line
edited: `web/task/BackgroundTaskService.java`, `TaskState`, `TaskSnapshot`,
`TaskAlreadyRunningException`, `SyncTaskApiController`, `SyncTaskStatusMapper`, `UnknownTaskException`,
`web/dto/SyncTaskAcceptedResponse`, `web/dto/SyncTaskStatusResponse`. `SyncApiExceptionHandler` gained
one entry in its `assignableTypes` list (and a javadoc line) — no new handler, no new code. The status
route was already shared by operation key, so this trigger adds no status route of its own.

The one deliberate change to existing code: the account controller's private
`rejectAnyRequestField(...)` became `web.SyncRequestValidation.rejectAnyRequestField(route, request)`,
shared by both triggers and naming the offending route in its message. Extracted rather than copied so
the two routes cannot drift into answering the same malformed request differently. The account
message is byte-identical to before — `AccountSyncApiControllerTest` asserts that exact string and
passes untouched, which is the evidence the extraction preserved the contract (and it was re-confirmed
live, §6).

### 3. Delegation to the existing use case

`GlobalSyncApiController` validates, submits `globalDataRefreshService::refreshAll` as the task body,
and returns the identifier. The step order (tradeable items → global recipes → one graph rebuild), the
persistence, the rebuild itself and the short-circuit on the first failing step all stay inside
`application.GlobalDataRefreshService`, which this story did not modify. No controller calls an
individual `sync.GlobalDataRefreshGateway` step, `CraftingGraphRebuildService` or
`repo.CraftingGraphCache`, and no part of the sequence is restated at the boundary — `web/*` contains
no `sync.*` or `repo.*` import.

`GlobalDataRefreshService` is wired as one shared bean, for the same reason as
`AccountRefreshService`: it keeps no per-call state, so there is no reload result for two callers to
observe, and overlapping runs are excluded by the admission rule instead. Constructing it opens no
connection, so the bean costs nothing at startup.

### 4. The operation key and its interactions

**`GLOBAL_SYNC`**, reported in both the acceptance and the status body.

- **Against itself:** at most one unfinished task, the existing admission rule unchanged. The reason is
  stronger here than for account sync — the sequence rewrites the account-independent
  `tp_tradeable_items`, `recipes` and `recipe_ingredients` rows and then rebuilds the single
  `crafting_graph_cache.json`, so two concurrent runs would repeat the same rate-limited GW2 API work
  and have two rebuilds writing that one cache file.
- **Against `ACCOUNT_SYNC`:** different keys, so the facility's per-key rule leaves them independent —
  both may be accepted and run at once, each refusing only a second submission of its own operation,
  and both stay separately identifiable through the one status route. This is the existing policy
  applied, **not a new judgement that the two are safe together.** What is observable and recorded:
  they write disjoint tables (global as above, account `account_*`/`character*`), and they share the
  GW2 API rate budget and the database, so running both at once makes each slower. No cross-operation
  exclusion was introduced; introducing one would be a product decision this story does not own.

### 5. Transport DTOs and failure reporting

The existing `web.dto.SyncTaskAcceptedResponse`/`SyncTaskStatusResponse` and the existing mapped
failures, reused unchanged. No `application.*`, `sync.*`, `repo.*` or GW2-API model is serialized, and
`TaskSnapshot` still never reaches the wire. `SQLException` → the shared `DATA_STORE_UNAVAILABLE`,
anything else → `SYNC_FAILED`; the exception's own text is logged server-side only — tests place a GW2
endpoint/host and a JDBC URL into simulated failures and assert both are absent from the response.

The failure message is the existing `"Synchronization failed; steps that had already completed were not
rolled back"`. That is exactly as true for this sequence as for the account one, and no more: a global
failure can leave refreshed item/recipe rows behind a graph cache still reflecting the previous data.
**What the route cannot say is which of the three steps completed** — the service exposes no
step-level progress and manufacturing one at the boundary would duplicate its orchestration, which
criterion 3 forbids. Recorded as a limitation rather than invented.

### 6. Tests and verification

**Default `./mvnw test` run — 246 tests, 0 failures, 0 errors** (234 pre-existing, all passing
unchanged; 12 new):

- `web.GlobalSyncApiControllerTest` (12) — 202 body and `Location`; `{}` accepted; acceptance body
  carries no outcome; unexpected field and unreadable body each rejected **with zero delegations and no
  task admitted**; **the trigger returning while `refreshAll()` is still blocked, with the advertised
  status path already answering `GLOBAL_SYNC`/`RUNNING`/no `finishedAt`/no `failure`, then reaching
  `SUCCEEDED` after release**; exactly-once delegation per accepted task across two successive tasks
  with distinct identifiers; second trigger 409 naming the running task without a second delegation; a
  new sync accepted after the previous finished; **an unfinished global sync not blocking the account
  trigger**, with both tasks separately identifiable and the account one completing while the global
  one is still latched; `SYNC_FAILED` and `DATA_STORE_UNAVAILABLE` mapping with no leaked endpoint/JDBC
  detail and no completion wording.
- `web.Gw2ApiApplicationBootTest` (3) — the global trigger and its use-case bean wired into the real
  context alongside the existing ones. Starts no task, so no GW2 API call.

Determinism: latches, not sleeps; terminal states awaited with a bounded poll that fails naming the
task. The cross-operation test awaits the account task's own terminal state rather than reading its
delegation count straight after the 202, which would have been a race. Methodology added to
`TEST_STRATEGY.md` §35.3.

**Existing checks re-run for acceptance criterion 6:**

- `application.GlobalDataRefreshServiceTest` (4) and `application.AccountRefreshServiceTest` (7) — in
  the default run, passing unchanged; still the owners of the step-order and short-circuit evidence,
  deliberately not restated at the HTTP boundary.
- `web.AccountSyncApiControllerTest` (9), `web.SyncTaskApiControllerTest` (7),
  `web.task.BackgroundTaskServiceTest` (8) — passing unchanged, which is the account contract and the
  facility's documented properties shown intact after the shared-file edits.
- `./mvnw test -Dtest=Gw2AppSyncGlobalDataIT,Gw2AppSyncAccountIT` — 2 tests, passing: the JavaFX "Sync
  ALL tradeable Items…" and "Sync Account" buttons still call their services in process.
- `./mvnw test -Dtest=CraftingProfitApiRealDbEquivalenceIT,CraftingDiscoveryApiRealDbEquivalenceIT` —
  4 tests, passing against the real database. Run because the shared entry point gained a bean and the
  shared advice changed scope (`TEST_STRATEGY.md` §35's rule that a shared boundary change needs the
  value-level check, not only the contract tests).
- `./mvnw test -Dtest=CraftingProfitApiRealDbPerfIT` — passing; real database, 3151 rows,
  1,305,944-byte response. FIRST 1672 ms (cold JVM/JIT and caches), REPEAT 877 / 743 / 910 ms,
  **maximum 1672 ms** — below `STORY-API-003`'s 2344 ms and `STORY-API-001`'s 1981 ms on the same
  check, and far inside §33's 7000 ms budget. Nothing in the Profit request path was modified; the run
  differs only by beans a Profit request never touches. As in both predecessors, this is backend
  request time only and is **not** proof of the §33 full-page budget.

**Live verification against a really running server** (`GW2_API_PORT=18082 ./mvnw spring-boot:run`,
`Tomcat started on port 18082` / `Started Gw2ApiApplication in 1.308 seconds`), all non-mutating:

| Request | Observed |
|---|---|
| `POST /api/sync/global` `{"rebuildGraph":false}` | 400 `INVALID_REQUEST`, message naming `/api/sync/global` |
| `POST /api/sync/global` `{not json` | 400 `MALFORMED_REQUEST` |
| `GET /api/sync/tasks/7b21e4c0-…` | 404 `TASK_NOT_FOUND` |
| `GET /api/sync/global` | 405 (framework; the advice did not swallow it) |
| `POST /api/sync/nope` | 404 (framework) |
| `POST /api/sync/account` `{"scope":"ALL"}` | 400 `INVALID_REQUEST`, message byte-identical to `STORY-API-003`'s — the extraction preserved it |
| `POST /api/crafting/profit` | 200, 1,305,944 bytes — existing route unaffected |

The accepted path was deliberately **not** triggered live: it would rewrite the real global tables and
the multi-MB graph cache against the user's database.

### 7. Files

New: `src/main/java/web/{GlobalSyncApiController,SyncRequestValidation}.java`,
`src/test/java/web/GlobalSyncApiControllerTest.java`.

Modified: `src/main/java/web/Gw2ApiApplication.java` (one bean),
`src/main/java/web/AccountSyncApiController.java` (uses the extracted rule; behavior identical),
`src/main/java/web/SyncApiExceptionHandler.java` (advice scope + javadoc),
`src/test/java/web/Gw2ApiApplicationBootTest.java`.
Docs: `docs/CURRENT_ARCHITECTURE.md` (§2 layout, §3 responsibilities, §4 dependency direction, new
§5.8), `docs/TEST_STRATEGY.md` (§35.3, three added methodology bullets),
`docs/KNOWN_PROBLEMS.md` (§7.10 widened — that section's own recommendation asked to be revisited
before a further write-triggering route, and this is one). `pom.xml` needed no change;
`TARGET_ARCHITECTURE.md` was deliberately not edited — §9's route name and §23's policy are unchanged
by this story, and the implemented contract is current-state fact owned by `CURRENT_ARCHITECTURE.md`.

### Limitations

- **No step-level progress.** `FAILED` does not say whether tradeable items, global recipes or the
  graph rebuild failed, for the same reason as `STORY-API-003`: the application service exposes no such
  signal. A caller learns "it failed, and nothing was undone".
- **The accepted path was not exercised against the live GW2 API**, by choice — doing so would rewrite
  the real `tp_tradeable_items`/`recipes`/`recipe_ingredients` rows and `crafting_graph_cache.json`,
  and the story does not require live mutation to test the task protocol. Acceptance, the blocked-call
  asynchrony, delegation, every state and every error are proven by contract tests with a controlled
  service; rejection/404/405 were additionally confirmed against a running server. What remains
  unverified end-to-end is one real `refreshAll()` executing inside a task — the same call the JavaFX
  path exercises, invoked through a method reference.
- **Concurrent global + account sync is permitted but never executed for real.** The independence is
  proven at the task level with controlled services; that the two live sequences interleave well
  against the real database and the GW2 rate limiter is *not* claimed, only that they write disjoint
  tables and share the API budget.
- **No task cancellation, no progress percentage, no retry, no persistence or distributed execution** —
  none asked for, none built; the consequences are `STORY-API-003`'s documented ones, unchanged.
- **Unauthenticated, and the second write-triggering route**, now with the larger write footprint.
  Recorded in `KNOWN_PROBLEMS.md` §7.10; authentication stays an undecided architecture question
  (`TARGET_ARCHITECTURE.md` §20) and was deliberately not introduced here.
- **Milestone not declared complete.** The price-refresh trigger (`STORY-API-005`) and the bounded
  Phase 4 PROJECT HEALTH REVIEW remain open.

## Blockers

None.
