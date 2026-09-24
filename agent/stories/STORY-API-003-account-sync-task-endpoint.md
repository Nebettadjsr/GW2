## Story ID

STORY-API-003

## Title

Expose account synchronization as an asynchronous HTTP task

## Status

DONE

## Milestone

milestone-04

## Goal

Expose the existing account-refresh application use case through a thin HTTP trigger and task-status contract, establishing the simplest practical asynchronous boundary required for GW2 API synchronization.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` section 8, Phase 4 sync/refresh endpoints, explicit long-running-operation approach, thin controllers, transport DTOs, API tests and additive JavaFX requirements.
- `docs/TARGET_ARCHITECTURE.md` sections 8, 9, 22, 23 and 30.
- `agent/user-decisions/UD-007-http-long-running-sync-approach.md`, resolved decision requiring asynchronous GW2 synchronization with status reporting.
- `agent/stories/STORY-API-001-spring-boot-crafting-profit-endpoint.md`, established Spring Boot boundary and API conventions.
- `docs/TEST_STRATEGY.md` section 11, referenced by the Phase 4 API-test exit criterion.

## Context

The supplied backlog records `application.AccountRefreshService.refreshAll()` as the existing owner of account bank, materials, recipes and character synchronization, with ordered execution and failure short-circuiting. The existing Phase 4 stories cover Profit and Discovery calculations, not sync triggers. Section 9 illustrates an account-sync route; its spelling is not a fixed contract. UD-007 already decides asynchronous execution for operations calling the GW2 API, so no speculative duration estimate or new technology decision is needed to plan this slice.

## Acceptance Criteria

1. Add and document an account-sync HTTP trigger using the established Spring Boot runtime. An accepted request returns a task identifier and a way to query status without waiting for GW2 synchronization to finish. Document request validation and HTTP status/error behavior, distinguishing acceptance from successful completion.
2. Execute the existing account-refresh application use case in the backend task. Controllers translate requests and responses only; preserve the service's existing ordering, persistence behavior and failure short-circuiting without duplicating orchestration or calling individual sync steps from routes.
3. Provide a status endpoint that distinguishes pending/running work from successful and failed completion. Report failure honestly, including when earlier service steps have already completed; do not imply rollback or successful completion after failure. Define the response for an unknown task identifier.
4. Use explicit transport DTOs for acceptance, status and errors, distinct from domain, persistence and GW2 API JSON objects. Keep HTTP/Spring/task transport concerns outside existing application/domain behavior. Apply the existing API error-disclosure conventions.
5. Implement a simple task facility suitable for reuse by subsequent sync/refresh endpoints. Document its actual task lifetime, restart behavior, execution/admission limits and handling of overlapping account-sync requests. Routine implementation choices remain with the implementer; no durable or distributed job platform is required.
6. Contract tests demonstrate validation before scheduling, asynchronous acceptance, exactly-once application delegation for each accepted task, independently identifiable task states, successful completion, failed completion and unknown-task responses. A controlled blocked service call must prove the trigger returns and status remains queryable before work finishes.
7. JavaFX retains its existing in-process account-refresh call without UI changes. Relevant existing application and JavaFX checks continue to verify that behavior; existing calculation API contracts remain intact when shared web infrastructure changes.

## Required Tests

- HTTP tests with controlled application collaborators for trigger validation, acceptance DTO/status, task lookup, unknown identifiers and mapped failures.
- Deterministic task lifecycle tests using controlled completion/failure, including a blocked call to demonstrate nonblocking acceptance and distinguishable task state; verify the documented overlapping-request and admission behavior.
- Relevant existing account-refresh service and JavaFX account-sync checks. Run existing calculation HTTP contract checks affected by shared boundary changes.
- Do not require live account mutation merely to test the task protocol; record actual verification conditions and limitations.

## Constraints

Scope is account synchronization and the minimal reusable task/status boundary. Do not add global-sync or price-refresh endpoints, automatic scheduling, new domain rules, or a distributed queue. Preserve existing service behavior and JavaFX integration. Do not infer atomic rollback, task persistence, operation durations or measured performance. If implementation reveals a material undecided product/architecture trade-off, use the normal user-decision workflow. Preserve the accepted Crafting Profit performance requirement; shared runtime changes that affect it require targeted verification rather than an assumed pass.

## Dependencies

STORY-API-001 (DONE). Execute after the already queued STORY-API-002 to keep the current calculation-contract work first.

## Definition of Done

The trigger, task execution, status DTOs and targeted tests satisfy the acceptance criteria. Record the concrete HTTP contract, task lifecycle choices, delegation evidence, tests and limitations in Result. Update current architecture and testing documentation only for implemented facts or reusable methodology. Do not declare milestone completion; remaining sync/refresh endpoints and the bounded Phase 4 health review still require planning.

## Result

Done. `POST /api/sync/account` accepts an account synchronization as a background task and
`GET /api/sync/tasks/{taskId}` reports its outcome, both on the Spring Boot runtime `STORY-API-001`
established. `application.AccountRefreshService` and every JavaFX file are byte-for-byte unchanged.

### 1. The HTTP contract

Prose contract lives in `docs/CURRENT_ARCHITECTURE.md` §5.7 (its owner); summary:

| Request | Result |
|---|---|
| `POST /api/sync/account`, no body or `{}` | **202** `{taskId, operation: "ACCOUNT_SYNC", statusUrl}` + `Location: /api/sync/tasks/{taskId}` |
| same, with any request field | **400** `INVALID_REQUEST` — no task created |
| same, unreadable JSON | **400** `MALFORMED_REQUEST` — no task created |
| same, while an account sync is unfinished | **409** `SYNC_ALREADY_RUNNING`, message naming the running task |
| `GET /api/sync/tasks/{id}`, known id | **200** `{taskId, operation, state, submittedAt, startedAt, finishedAt, failure}` |
| `GET /api/sync/tasks/{id}`, unresolvable id | **404** `TASK_NOT_FOUND` |

**Acceptance is not completion.** The 202 body carries `taskId`/`operation`/`statusUrl` and
deliberately no outcome field of any kind, so nothing in it can be misread as "the synchronization
succeeded" — asserted by a test. The outcome is only ever readable from the status route.

**Validation.** The use case takes no parameters, so neither does the route: the body must be absent
or an empty JSON object, and any field is rejected rather than silently ignored, so a caller that
believes it is parameterising the sync finds out. Both 400s are raised before `submit(...)` is
reached, so a rejected request synchronizes nothing.

**Asynchronous by decision, not by measurement.** `UD-007` / `TARGET_ARCHITECTURE.md` §23 require GW2
API synchronization to run as a backend task with status reporting. No duration was measured or
assumed to reach that choice, and none is claimed anywhere in this story.

### 2. Delegation to the existing use case

`AccountSyncApiController` validates, submits `accountRefreshService::refreshAll` as the task body,
and returns the identifier. The step order (account bank → account materials → account recipes →
character crafting/recipes), the persistence and the short-circuit on the first failing step all stay
inside `application.AccountRefreshService`, which this story did not modify. No individual
`sync.AccountRefreshGateway` step is reachable from any route, and no part of the sequence is
restated at the boundary — verified by reading: `web/*` contains no `sync.*` import.

`AccountRefreshService` is wired as one shared bean rather than a per-request factory. The reason the
calculation controllers need factories does not apply: it keeps no per-call state, holding only its
gateway, so there is no reload result for two callers to observe. Overlapping runs are excluded by the
task facility's admission rule instead, which a per-request instance could not do.

### 3. The task facility and its documented behavior

`web.task.BackgroundTaskService` (plus `TaskState`, `TaskSnapshot`, `TaskAlreadyRunningException`) —
~150 lines, imports only `java.*`, no Spring/HTTP/domain type, so a later sync/refresh route reuses it
by passing its own operation key. Every property below is asserted by a test, because
`CURRENT_ARCHITECTURE.md` §5.7 states them as facts:

- **Admission:** at most one unfinished task per operation key. A second submission while the first is
  `PENDING`/`RUNNING` is refused with the existing identifier, **not queued behind it**. Different
  operation keys are independent and run concurrently. For account sync this is deliberate: the
  sequence writes account-wide rows and is GW2-API rate limited, so a concurrent second run would
  duplicate the same work against the same data.
- **Execution:** one virtual thread per task (`Executors.newVirtualThreadPerTaskExecutor()`,
  `CODING_GUIDELINES.md` §7 for I/O-bound work). Concurrency is bounded by the admission rule, not by
  a pool size.
- **Lifetime:** in-memory only, most recent **100** records retained; older *terminal* records are
  evicted on submission and an unfinished record is never evicted. An evicted identifier is
  indistinguishable from one never issued.
- **Restart:** nothing persisted. A restart loses every record including a running task's, and
  shutdown (`close()`, called by Spring since the bean is `AutoCloseable`) interrupts a task in flight.
  Work already committed to the database stays committed.

Thread safety is one monitor over the task table; bodies run outside it. `TaskSnapshot` is copied under
that lock, so a reader never observes a half-updated task.

### 4. Status semantics, and the honest failure report

Four states, not two: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, with `startedAt` set from `RUNNING`
on and `finishedAt` only once terminal. A known identifier is **always 200** — a failed task is a
successful *lookup* — so the outcome lives in `state`, never in the HTTP status.

`refreshAll()` stops at its first failing step and has never had partial-completion handling, so a
failure genuinely can leave earlier steps' writes in place. The failure message says exactly that:
`"Synchronization failed; steps that had already completed were not rolled back"`. No rollback is
implied and no success is implied. **What the route cannot say is which steps completed** — the
service exposes no step-level progress, and manufacturing one would mean duplicating its orchestration
at the boundary, which acceptance criterion 2 forbids. Recorded as a limitation below rather than
invented.

### 5. Transport DTOs and error disclosure

`web.dto.SyncTaskAcceptedResponse` and `web.dto.SyncTaskStatusResponse` — records used nowhere but the
boundary. No `application.*`, `sync.*`, `repo.*` or GW2-API JSON type is serialized; the task
facility's own `TaskSnapshot` also never reaches the wire (`SyncTaskStatusMapper` copies it, converting
`Instant` to an explicit ISO-8601 string rather than relying on serializer configuration). The failure
body reuses the existing `ApiErrorResponse`, so the codes read the same on both boundaries:
`SQLException` → the shared `DATA_STORE_UNAVAILABLE`, anything else → `SYNC_FAILED`. The exception's own
text is logged in full server-side and never returned — tests assert that a GW2 endpoint/host and a JDBC
URL placed in the simulated failures are absent from the response. The 404 likewise does not echo the
caller-supplied identifier.

`SyncApiExceptionHandler` is a **second** advice rather than an extension of `ApiExceptionHandler`,
because that one's messages and its 500 `CALCULATION_FAILED` describe a crafting calculation — the wrong
thing to tell a caller about a synchronization — and the calculation routes' established contract must
not be reworded to make room for this one. `ApiValidationException` is reused unchanged. Both advices are
scoped with `assignableTypes`, so neither swallows the framework's own routing responses (404/405 both
verified live). These two routes have no calculation-style 500 path of their own: they only accept work
and look it up, and a failure of the work belongs to the task, not to the request that scheduled it.

### 6. Tests

**Default `./mvnw test` run — 234 tests, 0 failures, 0 errors** (209 pre-existing, all passing
unchanged; 25 new):

- `web.task.BackgroundTaskServiceTest` (8) — non-blocking submit with a latch-blocked body and the
  task queryable as `RUNNING` mid-flight; failure recorded with its own cause; body runs exactly once
  per accepted task; overlapping submission refused and naming the running task, with the refused body
  never run; the same operation admitted again after completion; two operation keys concurrent and
  independently identifiable; unknown identifier empty; the oldest terminal record evicted at the
  documented retention bound (101 submissions).
- `web.AccountSyncApiControllerTest` (9) — 202 body and `Location`; `{}` accepted; acceptance body
  carries no outcome; unexpected field and unreadable body each rejected **with zero delegations and no
  task admitted**; **the trigger returning while `refreshAll()` is still blocked, with the advertised
  status path already answering `RUNNING`/no `finishedAt`/no `failure`, then reaching `SUCCEEDED` after
  release**; exactly-once delegation per accepted task across two successive tasks with distinct
  identifiers; second trigger 409 without a second delegation; a new sync accepted after the previous
  finished.
- `web.SyncTaskApiControllerTest` (7) — running/succeeded/failed bodies each reported with the right
  timestamps and no failure where there is none; `SYNC_FAILED` and `DATA_STORE_UNAVAILABLE` mapping;
  no leaked failure detail and no completion wording in a failure body; two tasks independently
  identifiable; 404 for an unresolvable identifier; identifier not echoed back.
- `web.Gw2ApiApplicationBootTest` (+1 test, 3 total) — the trigger, the status route, the facility and
  the use-case bean all wired into the real context. Starts no task, so no GW2 API call.

Determinism: latches, not sleeps. A body signals when entered and blocks until released, so
"still running" is observed at a point the test chose; terminal states are awaited with a bounded poll
that fails with the task id named. Methodology recorded in `TEST_STRATEGY.md` §35.3.

**Existing checks re-run for acceptance criterion 7:**

- `application.AccountRefreshServiceTest` (7) — in the default run, passing unchanged; still the owner
  of the step-order and short-circuit evidence, which is deliberately not restated at the HTTP boundary.
- `./mvnw test -Dtest=Gw2AppSyncAccountIT` — 1 test, passing: the JavaFX "Sync Account" button still
  calls `AccountRefreshService` in process.
- `./mvnw test -Dtest=CraftingProfitViewAutoRefreshIT,CraftingDiscoveryViewAutoRefreshIT` — 4 tests,
  passing: both views' auto-refresh timers still call their account-refresh sequences in process.
- `./mvnw test -Dtest=CraftingProfitApiRealDbEquivalenceIT,CraftingDiscoveryApiRealDbEquivalenceIT` —
  4 tests, passing against the real database. Run because the shared entry point gained beans
  (`TEST_STRATEGY.md` §35's rule that a shared boundary change needs the value-level check, not only the
  contract tests), and because criterion 7 requires the calculation contracts to be shown intact rather
  than assumed.
- `./mvnw test -Dtest=CraftingProfitApiRealDbPerfIT` — passing; real database, 3151 rows, 1,305,944-byte
  response. FIRST 2344 ms (cold JVM/JIT and caches), REPEAT 1072 / 1052 / 1114 ms, **maximum 2344 ms**.
  `STORY-API-001` measured maximum 1981 ms on the same check. Both are far inside §33's 7000 ms budget
  and the difference is run-to-run variation on a cold first request, not a change on the Profit path:
  nothing in the request path was modified, only additional beans a Profit request never touches. Run
  because the story's constraint requires targeted verification rather than an assumed pass — and, as in
  `STORY-API-001`, this is backend request time only and is **not** proof of the §33 full-page budget.

**Live verification against a really running server** (`GW2_API_PORT=18081 ./mvnw spring-boot:run`,
`Tomcat started on port 18081` / `Started Gw2ApiApplication in 1.49 seconds`), all non-mutating:

| Request | Observed |
|---|---|
| `GET /api/sync/tasks/3f7c1a90-…` | 404 `{"error":"TASK_NOT_FOUND",…}` |
| `POST /api/sync/account` `{"scope":"ALL"}` | 400 `{"error":"INVALID_REQUEST",…}` |
| `POST /api/sync/account` `{not json` | 400 `{"error":"MALFORMED_REQUEST",…}` |
| `POST /api/sync/nope` | 404 (framework; the advice did not swallow it) |
| `GET /api/sync/account` | 405 (framework) |
| `POST /api/crafting/profit` DISCIPLINE=Chef | 200, 176,995 bytes — existing route unaffected |

### 7. Files

New: `src/main/java/web/{AccountSyncApiController,SyncTaskApiController,SyncTaskStatusMapper,SyncApiExceptionHandler,UnknownTaskException}.java`,
`src/main/java/web/task/{BackgroundTaskService,TaskState,TaskSnapshot,TaskAlreadyRunningException}.java`,
`src/main/java/web/dto/{SyncTaskAcceptedResponse,SyncTaskStatusResponse}.java`,
`src/test/java/web/{AccountSyncApiControllerTest,SyncTaskApiControllerTest}.java`,
`src/test/java/web/task/BackgroundTaskServiceTest.java`.

Modified: `src/main/java/web/Gw2ApiApplication.java` (two beans), `src/test/java/web/Gw2ApiApplicationBootTest.java`.
Docs: `docs/CURRENT_ARCHITECTURE.md` (§2 layout, §3 responsibilities, §4 dependency direction, new §5.7),
`docs/TEST_STRATEGY.md` (new §35.3), `docs/KNOWN_PROBLEMS.md` (new §7.10).
`pom.xml` needed no change; `TARGET_ARCHITECTURE.md` was deliberately not edited — §23's policy and §9's
illustrative route names are unchanged by this story, and the implemented contract is current-state fact
owned by `CURRENT_ARCHITECTURE.md`.

One incidental detail worth recording: `@PathVariable` needs its name spelled explicitly
(`@PathVariable("taskId")`) because this build does not compile with `-parameters`. The first run failed
with Spring's "parameter name information not available" error; the name was spelled out rather than
changing the shared compiler configuration for one route.

### Limitations

- **No step-level progress.** `FAILED` does not say which of the four steps completed, because the
  application service exposes no such signal and adding one at the boundary would duplicate its
  orchestration. A caller learns "it failed, and nothing was undone", which is the honest answer
  available today.
- **The accepted path was not exercised against the live GW2 API.** Doing so would run a real account
  synchronization and write to the user's real database; the story explicitly does not require live
  account mutation to test the task protocol. Acceptance, the blocked-call asynchrony, delegation,
  every state and every error are proven by the contract tests with a controlled service, and the
  rejection/404/405 paths were additionally confirmed against a really running server. What remains
  unverified end-to-end is one real `refreshAll()` executing inside a task — the same call the JavaFX
  path exercises, invoked through a method reference.
- **No task cancellation, no progress percentage, no retry** — none was asked for and none was built.
- **No persistence or distributed execution**, as the story scopes; the consequence (restart loses
  records, task lifetime is process-bound) is documented rather than worked around.
- **Unauthenticated, and now write-triggering.** This is the first route on the API that causes a
  write. Recorded as `KNOWN_PROBLEMS.md` §7.10; authentication stays an undecided architecture question
  (`TARGET_ARCHITECTURE.md` §20) and was deliberately not introduced here.
- **Milestone not declared complete.** Global-sync and price-refresh triggers, and the bounded Phase 4
  PROJECT HEALTH REVIEW, remain open.

## Blockers

None.
