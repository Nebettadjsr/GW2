## Story ID

STORY-WEB-002

## Title

Add browser synchronization controls and backend task status

## Status

DONE

## Milestone

milestone-05

## Goal

Let browser users trigger the existing account, global-data and Trading Post refresh operations and observe their backend-reported task states without reproducing synchronization orchestration in the frontend.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: sync-trigger UI with status/progress matching the existing long-running-operation mechanism; frontend rendering/interaction tests; shared-backend JavaFX coexistence.
- `docs/TARGET_ARCHITECTURE.md` sections 4.1, 12, 22 and 23: Vue 3 / strict TypeScript, presentation-only frontend, backend-owned synchronization and asynchronous task reporting.
- `docs/CURRENT_ARCHITECTURE.md` sections 5.7–5.9: existing trigger contracts, operation keys, acceptance/status distinction, failure and retention semantics; section 5.11: existing browser client and verification path.
- `agent/stories/STORY-WEB-001-crafting-profit-table.md`: completed frontend foundation and explicitly deferred synchronization controls.

## Context

The browser currently presents Crafting Profit and has no synchronization controls. All required triggers and the shared task-status endpoint are already documented. The backend reports task lifecycle states, not step-level progress or item counts. This independent frontend slice can proceed without the bank/materials read endpoints already queued in STORY-API-007.

## Acceptance Criteria

1. Add reachable controls in the existing Vue frontend for `POST /api/sync/account`, `POST /api/sync/global` and `POST /api/prices/refresh`. Account/global requests have no parameters; price refresh explicitly selects the existing `PROFIT` or `DISCOVERY` variant. Each deliberate trigger submits once; mounting or reloading the page never starts synchronization.
2. Treat HTTP 202 as acceptance only. Track the returned task identity and operation and poll its advertised backend status endpoint. Render `PENDING`, `RUNNING`, `SUCCEEDED` and `FAILED` from the status body; HTTP 200 alone must not imply task success. Show only progress facts the backend provides, without fabricated percentages, steps or counts.
3. Keep operation statuses separate, including the two price variants. Prevent duplicate clicks while submission or the same tracked operation is unfinished. Preserve the backend's independent operation keys rather than adding global mutual exclusion. Display a backend 409 rejection honestly without queueing or automatically resubmitting the operation.
4. Distinguish submission errors, task failures and status-lookup failures. Render sanitized backend failure information, including the existing no-rollback qualification. A missing task or interrupted status request means the outcome cannot currently be established, not that synchronization succeeded or was rolled back. Do not infer eviction versus restart from `TASK_NOT_FOUND`; offer a status retry where meaningful without repeating the POST.
5. Bound polling, avoid overlapping status requests, stop polling terminal or unresolvable tasks and clean up polling when its owning UI is disposed. Late responses must not overwrite another task's status. Do not silently retry a trigger after a transport failure whose admission outcome is unknown.
6. Keep synchronization execution, relevance selection, ordering, persistence and domain calculations behind the API. Preserve the existing Profit controls/results and manual reload path. JavaFX continues using the same application services; no backend contract or JavaFX workflow change is required.
7. Update `docs/CURRENT_ARCHITECTURE.md` for the implemented browser flow and record actual verification and limitations in Result. Document that task records are process-local and that a successful price refresh does not establish a fetched-item count. This slice does not claim full-page performance acceptance or milestone completion.

## Required Tests

- Component/interaction checks with controlled HTTP responses for each trigger's route/body, exactly-once submission, no trigger on mount, acceptance followed by each lifecycle state, and a failed task returned with HTTP 200.
- Exercise independent simultaneous operation states, duplicate-click suppression, 409 rejection, submission/lookup failures, missing task, status retry without POST retry, terminal polling shutdown, disposal and late-response isolation. Use controlled timers/promises rather than live GW2 calls.
- Run strict TypeScript checking, the production frontend build and relevant existing Profit interaction regressions.
- Exercise the controls in a real browser against a controlled API boundary to verify interaction and task-state presentation without mutating real user data. Record explicitly which checks used controlled responses; do not claim a live synchronization completed unless actually observed. Verify application API requests target only the backend.

## Constraints

- Reuse the established frontend and HTTP-client patterns; no new framework, queue, task persistence, cancellation, automatic synchronization scheduler or backend progress mechanism.
- No direct GW2 API access, frontend secrets, client-side synchronization sequence or authoritative calculation.
- Do not invent a combined price-refresh operation, fetched-item count, partial-write details or rollback behavior.
- Keep this a bounded Phase 5 presentation slice. Other screens, resolution-tree work, final performance verification and the milestone health review are outside this story.

## Dependencies

STORY-WEB-001 (DONE). Existing task APIs are established in `docs/CURRENT_ARCHITECTURE.md` sections 5.7–5.9. No dependency on STORY-API-007.

## Definition of Done

Browser controls submit the existing operations and accurately present backend task states, with meaningful interaction/lifecycle evidence, strict typing and a successful build. Documentation and Result state actual verification and its limits. No milestone transition is declared.

## Result

Done. A synchronization panel was added to the existing Vue frontend; no Java file was touched, so
no backend contract or JavaFX workflow changed.

**What was built.** Six new frontend files and four small edits.

- `src/api/syncApi.ts` — the three triggers and the shared status route behind an interface:
  `POST /api/sync/account` and `POST /api/sync/global` with body `{}`, `POST /api/prices/refresh`
  with `{"variant": "PROFIT" | "DISCOVERY"}`, and `readTaskStatus(statusUrl)`. The status location
  is the one the 202 advertised; a location not under the API base path is refused
  (`UNUSABLE_STATUS_URL`) instead of fetched, so the browser stays on its single origin.
- `src/sync/operations.ts` — the four backend operation keys (`ACCOUNT_SYNC`, `GLOBAL_SYNC`,
  `PRICE_REFRESH_PROFIT`, `PRICE_REFRESH_DISCOVERY`) and their trigger calls. No key is combined
  and none is invented.
- `src/sync/useSyncOperations.ts` — per-operation submission and polling state. A 202 is recorded as
  acceptance only; `PENDING`/`RUNNING`/`SUCCEEDED`/`FAILED` are read from the status body's `state`
  and never from the HTTP status. Polling is chained (the next lookup is scheduled only once the
  previous one answered, so two never overlap), bounded (`STATUS_POLL_INTERVAL_MS` 3 s,
  `MAX_STATUS_POLLS` 600 ≈ 30 min), and stopped by a terminal state, any lookup failure or the
  bound. Each tracked task carries a token, so an answer for a task no longer tracked is discarded,
  and `onScopeDispose` clears the timer and invalidates answers in flight.
- `src/sync/SyncControls.vue` — one button and one state line per operation, plus the backend's own
  timestamps, its sanitized failure code/message, and the three failure kinds as separate notices.
- `src/sync/__tests__/syncFixtures.ts`, `src/sync/__tests__/SyncControls.spec.ts`,
  `src/api/__tests__/syncApi.spec.ts` — 23 new tests.
- Edits: `src/api/types.ts` (`SyncTaskAccepted`, `SyncTaskStatus`, `PriceRefreshVariant`),
  `src/api/http.ts` (`API_BASE_PATH` exported), `src/App.vue` (panel above the Profit screen),
  `package.json` (`smoke:sync`).
- `scripts/sync-browser-smoke.mjs` — a real-browser check against a controlled API boundary (below).
  `resolveBrowserPath` was moved out of `scripts/browser-smoke.mjs` into
  `scripts/resolveBrowserPath.mjs` so both checks share one browser-lookup list; that script's
  behavior is otherwise unchanged and it was re-run to confirm it still loads.

**Decisions inside the story's scope.** Duplicate clicks are suppressed per operation only, mirroring
the backend's per-key admission rule — an account sync and a global sync stay independently
triggerable, and no client-side queue or mutual exclusion was added. A 409 is shown as the refusal
it is and never resubmitted. A trigger that failed in transport additionally states that the
admission is unknown, because a silent resubmission could start a second run. A 404
`TASK_NOT_FOUND` stops polling, says the outcome cannot be established here — explicitly neither
success, failure nor rollback — and offers **no** retry, since retention is process-local and
asking again cannot establish more; every other lookup failure offers a retry of the lookup alone,
never of the POST. No percentage, step or item count is rendered, because the backend reports none.

**Verification, and what each check does not establish.**

- `npm run type-check` (inside `npm run build`): clean, strict, components included.
- `npm test`: **49 passed / 49** (26 pre-existing, 23 new), all with controlled responses, faked
  timers and hand-resolved promises. The new tests cover: nothing triggered on mount; each trigger's
  route and body against a stubbed `fetch`; exactly-once submission; the full lifecycle
  `PENDING → RUNNING → SUCCEEDED`; a `FAILED` task returned with HTTP 200 rendering as a failure
  with the "were not rolled back" wording; the four operation states kept apart, price variants
  included; duplicate-click suppression while submitting and while tracking; an independent
  operation triggered while another runs; a 409 shown without resubmitting; an unanswered trigger
  reported as an unknown admission and never retried; a lookup failure distinguished from a task
  failure and offering a retry; a status retry that does not repeat the POST; a missing task that
  claims no outcome and offers no retry; no overlapping status requests; out-of-order answers
  staying in their own operation; polling stopped on unmount; and the poll bound reporting an
  unestablished outcome. These establish frontend behavior against a stand-in, not backend
  behavior.
- `npm run build`: OK — `dist/assets/index-Cca_F0ad.js` 81.20 kB (30.68 kB gzip), CSS 3.29 kB.
- `npm run smoke:sync`: **PASSED, 8 steps**, real Chrome via `playwright-core`, against a
  controlled API boundary the script runs itself (it serves `dist/` and answers the trigger and
  status routes with scripted lifecycles). Observed in the browser: nothing requested on load; four
  triggers submitted with the documented bodies (`/api/sync/account {}`, `/api/sync/global {}`,
  `{"variant":"PROFIT"}`, `{"variant":"DISCOVERY"}`); each button disabled while its task was
  unfinished; the account task tracked through 3 lookups to `SUCCEEDED`; a `FAILED` task rendered
  from an HTTP 200 status with `SYNC_FAILED` and the no-rollback wording; `PRICE_REFRESH_PROFIT`
  `SUCCEEDED` while `PRICE_REFRESH_DISCOVERY` was reported as an unresolvable identifier with no
  retry offered; 8 status lookups in total and none after every task was terminal or unresolvable;
  no page errors and no call outside `/api/` and the page assets. **No real synchronization was
  run and no user data was touched** — this establishes interaction and task-state presentation
  only. A live GW2 synchronization through these buttons was deliberately **not** performed.
- `./mvnw test`: exit 0 with **286 tests, 0 failures, 0 errors** across the 53 report files this run
  produced. This story changed no Java file, so it is a confirmation that the backend and JavaFX are
  untouched rather than a test of new behavior.
- `npm run smoke:browser` (the STORY-WEB-001 check against the real backend) was **not** re-run; it
  needs the backend and the dev server started by hand. Its script was changed only by the
  `resolveBrowserPath` extraction, which was verified by running it and seeing it reach its first
  step before failing on the intentionally absent server.

**Limitations.** Task records remain process-local and unpersisted, so a backend restart makes a
tracked identifier unresolvable and the panel reports exactly that; the panel keeps no record of its
own and a page reload forgets every tracked task. A rendered `SUCCEEDED` for a price refresh does
**not** establish that any item was fetched — the ten-minute freshness filter can select nothing and
succeed — and no item count is shown because none is reported. The poll interval and bound are
fixed constants, not configuration. No 409 was exercised in a real browser (the button that would
produce one is disabled while its own task is unfinished); the 409 path is covered by controlled
responses only. No cancellation, no persistence, no scheduler and no progress mechanism was added.
Full-page performance (§33) is not claimed, and no Phase 5 exit criterion or milestone transition is
declared.

**Documentation.** `docs/CURRENT_ARCHITECTURE.md` §5.11 (retitled for both web stories; file layout,
a new synchronization-panel flow block, the command table and the "not built yet" note);
`docs/TEST_STRATEGY.md` §12.2 one table row and new §12.3 (methodology for browser-checking controls
that would mutate data, and faked-timer polling tests); `README.md` browser section and command
table. `ROADMAP.md`, `DOMAIN_SPEC.md`, `TARGET_ARCHITECTURE.md` and `KNOWN_PROBLEMS.md` were not
touched — this slice changed no domain rule, no intended architecture and introduced no known
defect.

## Blockers

None.
