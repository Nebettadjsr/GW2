## Story ID

STORY-WEB-031

## Title

Bound account browser smoke call reporting

## Status

TODO

## Milestone

milestone-05

## Goal

Keep the account browser smoke result concise and useful on live data by avoiding an unbounded dump of every observed backend call after a successful run.

## Authoritative Source Documents / Sections

- Supplied Phase 5 roadmap excerpt: finish browser coverage and acceptance; browser smoke coverage is tracked in canonical stories and `docs/TEST_STRATEGY.md`.
- `agent/stories/STORY-WEB-029-repair-account-ecto-smoke-contracts.md`, Follow-up Finding F003 and Result.
- `docs/TEST_STRATEGY.md` §12.2, browser smoke verification.

## Context

The account live smoke reports concise step evidence, then prints every observed backend call as one line. On live data, the call list includes hundreds of item-icon requests and can produce roughly 60 KB of output, pushing the step evidence out of bounded log views. Preserve useful diagnostics while keeping successful-run output bounded.

## Acceptance Criteria

1. A successful account smoke run does not print the complete per-request call list.
2. Its step summaries and final pass status remain visible and identify the checks performed.
3. Failure reporting retains enough bounded context to diagnose unexpected backend calls without dumping an unbounded list of successful icon requests.
4. The smoke continues to assert the same account workflow behavior and request-origin constraints.

## Required Tests

- Run `npm run smoke:account` against its supported controlled or live application and confirm step summaries and the final status remain visible with bounded output.
- Exercise a controlled unexpected-call failure and confirm the failure output is bounded and informative.
- Review affected frontend coverage; this script-only change does not require new component behavior tests.

## Constraints

Limit changes to account smoke reporting and directly related assertions. Do not weaken workflow assertions or change application behavior.

## Dependencies

- STORY-WEB-029

## Definition of Done

Successful and controlled failure output are bounded and retain useful evidence; account smoke workflow assertions remain intact; relevant coverage reports and unresolved quality gaps are reviewed; the CI gate is green.

## Result

`frontend/scripts/account-browser-smoke.mjs` is the only file changed (61 insertions, 5 deletions).

**What changed.** The completion line printed one entry per observed backend call. It now prints
`summarizeBackendCalls(apiCalls)`: one line per distinct method, call shape and status with how
often it was seen, ranked by count, capped at `CALL_SUMMARY_LIMIT` (15) distinct shapes with the
remainder reported as a count. `callShape()` collapses the only two paths that carry an identifier —
`/api/items/{id}/icon/{hash}` and `/api/sync/tasks/{id}` — so the summary's size no longer follows
the number of items the account holds. `apiCalls` moved to module scope so the failure handler can
report the same summary, which it previously did not print at all. The off-route image assertion now
names the count and the first five sources instead of every `src`, the one other message whose
length followed the page's item count. No assertion, threshold or comparison changed.

**Run environment for all three runs below.** `./mvnw spring-boot:run` (Spring Boot on `:8080`,
`JAVA_HOME` = `openjdk-25.0.1`) and `npm run dev` (Vite on `localhost:5173`, which proxies `/api`),
both started by hand on 10.10.2026 and stopped afterwards by PID. The dev server binds `localhost`
only, so the runs used the script's default `GW2_FRONTEND_URL=http://localhost:5173`. Live local
account, read once both account routes answered 200 rather than `ACCOUNT_DATA_STALE`: 180 bank slots
(166 occupied), 9 material categories, 681 material positions. The artifacts below are the complete
captured stdout+stderr of each run; the trailing `exit=N` line in each was appended by the capturing
shell (`echo "exit=$?"`), everything above it is the run's own output.

**Artifact 1 — successful high-volume run, the shipped command** (AC 1, 2, 4).
`cd frontend && npm run smoke:account > pass.log 2>&1` — **exit 0, 8 steps, 1,760 bytes captured in
26 lines**, of which the whole backend-call report is **373 bytes in 6 lines** while the run observed
**231 backend calls, 223 of them item-icon requests**:

```
> gw2-tool-frontend@0.1.0 smoke:account
> node scripts/account-browser-smoke.mjs

Browser : C:/Program Files/Google/Chrome/Application/chrome.exe
Page    : http://localhost:5173

  ok   page loaded — no account read before a screen is opened
  ok   bank read by the browser — HTTP 200
  ok   bank rendering matches the response — 180 slots (slotCount 180), 14 empty, 166 occupied, in the supplied order; 70 painted quantities and 96 deliberately omitted for a single held item
  ok   bank reload repeats only that read — 1 × /api/account/bank, plus 0 image request(s) on the shared icon route and 0 task-status poll(s) for a refresh the backend already had running; 89 call(s) already in flight excluded — [] plus 89 image request(s) on the shared icon route
  ok   materials read by the browser — HTTP 200
  ok   materials rendering matches the response — 9 categories (categoryCount 9), 681 positions, labels and order as supplied; 510 owned with a painted quantity, 171 unowned positions kept in place and greyed out
  ok   images come only from this application — 681 on /api/items/{id}/icon/…, all no-referrer; 0 entries on the fallback
  ok   nothing synchronized, no page error, no non-backend call — 500 browser requests, all to http://localhost:5173; 3 read-only task-status poll(s) and no start of any kind

Account browser smoke PASSED (8 steps).
Backend calls observed: 231 backend call(s) in 6 distinct shape(s):
   223 × GET /api/items/{id}/icon/{hash} → HTTP 200
     3 × GET /api/sync/tasks/{id} → HTTP 200
     2 × GET /api/account/bank → HTTP 200
     1 × GET /api/account/materials → HTTP 200
     1 × GET /api/crafting/selector-options → HTTP 200
     1 × POST /api/crafting/profit → HTTP 503
exit=0
```

No per-request call list appears anywhere in it (AC 1), all eight step summaries and the final
`Account browser smoke PASSED (8 steps).` are present and each names what it checked (AC 2), and the
step lines are the unchanged workflow assertions: the slot-by-slot bank comparison, the reload
restricted to that one read, the 681-position materials comparison, images only on the icon route
and all `no-referrer`, and nothing synchronized with every request on the page origin (AC 4).

**Artifact 2 — the previous reporting, measured on the same application** (invariant: successful-run
output must stay bounded as icon requests grow). The pre-change file was taken straight from the
commit under change, so the control is the previous implementation itself and nothing else:
`git show HEAD:frontend/scripts/account-browser-smoke.mjs > frontend/scripts/.tmp-control-head-reporting.mjs`,
then `node scripts/.tmp-control-head-reporting.mjs`. **Exit 0, 8 steps, 24,520 bytes captured in 15
lines** — the same run, 13.9 × the output — because line 14 alone was **23,194 bytes**:

```
14	23194 bytes	Backend calls observed: /api/crafting/selector-options 200, /api/account/bank 200, /api/items/48233/icon/3e061ef25efdc3d…[truncated for this listing]
```

That one line is the per-request list the shipped code replaced with the 373-byte, 6-line summary in
Artifact 1; its length is set by the icon-request count, the summary's by the number of distinct
routes.

**Artifact 3 — controlled unexpected-call failure** (AC 3). The control differs from the shipped file
by exactly one injected line, and was produced reproducibly from it:

```
cd frontend
sed "s|^    await page.click('\[data-test=\"bank-reload\"\]')$|&\n    await page.evaluate(() => { void fetch('/api/account/materials') })|" \
  scripts/account-browser-smoke.mjs > scripts/.tmp-control-unexpected-call.mjs
diff scripts/account-browser-smoke.mjs scripts/.tmp-control-unexpected-call.mjs
# 280a281
# >     await page.evaluate(() => { void fetch('/api/account/materials') })
node scripts/.tmp-control-unexpected-call.mjs
```

The injected stimulus is one extra backend call the bank reload must not make; every line of
reporting exercised below is the shipped code. **Exit 1 after 3 steps, 1,355 bytes captured**, of
which the backend-call report is **276 bytes in 5 lines** although 156 icon requests had already been
observed:

```
Browser : C:/Program Files/Google/Chrome/Application/chrome.exe
Page    : http://localhost:5173

  ok   page loaded — no account read before a screen is opened
  ok   bank read by the browser — HTTP 200
  ok   bank rendering matches the response — 180 slots (slotCount 180), 14 empty, 166 occupied, in the supplied order; 70 painted quantities and 96 deliberately omitted for a single held item

Account browser smoke FAILED after 3 step(s): Reloading the bank requested something other than that one read, its item images and the stale-data recovery for that read.
  issued by the reload: ["/api/account/bank","/api/account/materials"]
  expected            : 1 × "/api/account/bank" and nothing else
  plus 0 image request(s) on the shared icon route and 0 task-status poll(s)
  continuations of calls already on the wire: []
  already in flight when the reload was clicked, so not attributed to it: ["/api/crafting/profit"] plus 79 image request(s) on the shared icon route
Backend calls observed: 160 backend call(s) in 4 distinct shape(s):
   156 × GET /api/items/{id}/icon/{hash} → HTTP 200
     2 × GET /api/account/bank → HTTP 200
     1 × GET /api/account/materials → HTTP 200
     1 × GET /api/crafting/selector-options → HTTP 200
Are both the backend (./mvnw spring-boot:run) and the dev server (npm run dev) running?
exit=1
```

The diagnostic names the unexpected call by path, the expectation it violated, the two kinds of
accompanying request that are counted rather than blamed, and the calls excluded as already in
flight — while the 156 successful icon requests contribute one line rather than 156 (AC 3). Both
control copies were deleted afterwards; `git status --porcelain` shows no `.tmp-` file and no file
under `frontend/` other than the one changed script.

**Tests and coverage.** No automated test covers this script: nothing imports it, `scripts/__tests__`
holds only `bundleFreshness.spec.mjs`, and `vite.config.ts` scopes coverage to `src/**/*.{ts,vue}`,
so `frontend/scripts/` is outside the measured area and this change moves no coverage figure. No
tooling or test asserts the script's output format (searched for `Backend calls observed` and
`account-browser-smoke` across the repository; only story and QA-plan prose refers to it). The
shipped file's syntax and behavior are established by Artifact 1, which executed it end to end. Per
`CLAUDE.md`, no broad suite was run: the working tree's other changes are harness/planner Markdown
and logs, with no product source file modified besides this script.

**Agreement with the QA plan.** All four acceptance checks and all three invariants were exercised as
specified, each against one of the three captured artifacts above; no QA expectation was changed,
and the plan declared no prepared test paths, so no prepared test was skipped or weakened.

**CI.** Not run from here: the pipeline commits and pushes the accepted story and waits for that
commit's gate, and this change touches no file the gate executes — `frontend/scripts/` holds no test,
nothing imports the script, and the gate cannot drive a browser (`TEST_STRATEGY.md` §12.2, §36.4).
The Definition of Done's green gate is therefore still outstanding and belongs to that push.

**Uncertainty.** The captured successful run shows `POST /api/crafting/profit → HTTP 503` in its
summary: the application's default screen issues that call while the backend is still warming, and
the script neither drives nor asserts that screen — the only reason it appears at all is that this
reporting now names every route the browser touched. It is not a finding of this story. Earlier runs
of the same script showed `/api/crafting/selector-options` at 503 for the same reason; in this run it
answered 200.

Operational note on process cleanup: four processes were started here and all four were stopped by
the PIDs read out of a `WMIC` listing — the Spring application (28536), the Maven wrapper (15616),
Vite (22520) and the `npm` wrapper (27516); the last two of those had already exited with their
parent, which the same listing confirmed. The two surviving `node` processes in that listing
(a Codex CLI from 08.10.2026 and an Adobe helper from 09.10.2026) are the maintainer's and were left
alone. No command-line pattern filter was used, per `tasks/lessons.md`.

## Follow-up Findings

F001: `account-browser-smoke.mjs`'s `foreignCalls` check (`Non-backend API calls observed`) filters
`apiCalls` for paths that do not start with `/api/`, but the `response` handler only ever pushes
`/api/` paths into `apiCalls`, so the check is unreachable and its message implies a guarantee it
cannot provide. The actual origin constraint is enforced by the neighbouring `otherOrigins` check
over `browserRequests`. `frontend/scripts/browser-smoke.mjs:267-268` contains the identical dead
check.

F002: `frontend/scripts/browser-smoke.mjs:273` still prints one entry per observed backend call in
the same format this story replaced, so its successful-run output grows with request count on any
page it drives that loads item icons.

## Blockers

None.
