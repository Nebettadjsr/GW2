## Story ID

STORY-WEB-027

## Title

Remove the tracked temporary account smoke control copy

## Status

DONE

## Milestone

milestone-05

## Goal

Remove the tracked throwaway control copy of the account browser smoke script so it cannot be mistaken for maintained verification code.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-018-fresh-build-browser-smoke-checks.md`, Follow-up Finding F001.
- Supplied `docs/ROADMAP.md` Phase 5, browser smoke coverage and acceptance.

## Context

The finding identifies `frontend/scripts/.tmp-control-unrelated-call.mjs` as a tracked throwaway control copy with no npm script reference. The Phase 5 browser verification scope includes maintaining meaningful smoke checks.

## Acceptance Criteria

1. Remove the tracked temporary control copy.
2. Confirm no maintained smoke command depends on that temporary file.
3. Record the repository change and focused verification.

## Required Tests

- Verify the temporary script is absent and the relevant account smoke check remains runnable.

## Constraints

- Do not modify the maintained account smoke script except where directly required to confirm it is independent.
- Do not broaden into general script cleanup.

## Dependencies

None.

## Definition of Done

The throwaway tracked file is removed, maintained verification remains intact, and evidence is recorded.

## Result

`frontend/scripts/.tmp-control-unrelated-call.mjs` was removed with `git rm`. Nothing else in the
repository was edited: no maintained script, no npm command, no component.

**What the removed file was.** Before deleting it,
`git diff --no-index frontend/scripts/account-browser-smoke.mjs frontend/scripts/.tmp-control-unrelated-call.mjs`
reported `1 file changed, 1 insertion(+)`: the copy differed from the maintained script by exactly one
added line inside the bank-reload window,
`await page.evaluate(() => fetch('/api/account/materials').then(() => undefined))` — the throwaway
control for the "a reload repeats only that one read" assertion (STORY-WEB-018 F001). It was tracked but
invoked by nothing.

**AC1 — removed (verified after the change).**
- `test -e frontend/scripts/.tmp-control-unrelated-call.mjs` → `False` (PowerShell `Test-Path`
  equivalent; the PowerShell tool was unavailable in this invocation).
- `git ls-files frontend/scripts/.tmp-control-unrelated-call.mjs` → no output, i.e. no tracked path.
- `git status --porcelain -- frontend/scripts/` → `D  frontend/scripts/.tmp-control-unrelated-call.mjs`
  (staged deletion) plus the maintainer's pre-existing ` M frontend/scripts/profit-browser-smoke.mjs`.

**AC2 — no maintained command depended on it.**
- `rg -n '\.tmp-control-unrelated-call\.mjs'` over `frontend/` (node_modules excluded) → **no matches**,
  so neither `frontend/package.json` nor any script under `frontend/scripts/` referenced it. A
  repository-wide search for `tmp-control` finds only prose in `tasks/lessons.md`, story files,
  user-intervention records, QA plans and `agent/logs/`.
- `frontend/package.json:24` still reads `"smoke:account": "node scripts/account-browser-smoke.mjs"`.
- `git show HEAD:frontend/scripts/account-browser-smoke.mjs | diff - frontend/scripts/account-browser-smoke.mjs`
  → identical to `HEAD`; `git diff HEAD --stat -- frontend/package.json` → empty. The maintained script
  and the npm command are byte-for-byte what they were before this story.

**AC3 — focused verification run (`npm run smoke:account`, exit 1 for a pre-existing reason).**
Prerequisites: the backend was already listening on `127.0.0.1:8080` and identified itself with a real
`/api/system/status` body (`cachedPriceItems 9457`, `newestPriceFetchedAt 2026-10-03T16:40:22Z`); a Vite
dev server was started for this run on `127.0.0.1:5173` (`npx vite --port 5173 --strictPort --host
127.0.0.1`), its `/api` proxy confirmed with `curl http://127.0.0.1:5173/api/account/bank` → `200` in
0.23 s. Command run from `frontend/`:
`GW2_FRONTEND_URL=http://127.0.0.1:5173 GW2_SMOKE_TIMEOUT_MS=45000 node scripts/account-browser-smoke.mjs`
(the identical `npm run smoke:account` invocation was run first and stalled without output after step 1;
re-running the same file directly produced the result below). Browser:
`C:/Program Files/Google/Chrome/Application/chrome.exe`.

```
  ok   page loaded — no account read before a screen is opened
  ok   bank read by the browser — HTTP 200

Account browser smoke FAILED after 2 step(s): The rendered item identities or counts do not match the response.
  rendered: ["null null","null 3","null null", …]
  response: ["#48233 × 1","#92923 × 3","#45188 × 1", …]
EXIT=1
```

The script loads, drives a real browser, reaches the backend and fails legibly at its third step — it is
runnable and it is independent of the deleted file, but it does **not** pass against the current tree.
The cause is a screen/script contract drift that pre-dates this story: step 3 asserts the
`InventoryItem.vue` contract (`[data-test="item-identity"]` and a `× <count>` label), while
`BankScreen` now renders `BankSlotTile` → `InventoryTile.vue` (maintainer commit `c05080f`, "Implement
material storage grid and account freshness"), which has no `item-identity` hook, prints a bare
`count.toLocaleString('en-US')`, and omits the quantity entirely for `count === 1` — exactly the
`"null null"` / `"null 3"` rows above. Nothing in this story touched that script, that component or
`package.json`, and the maintained script is proven identical to `HEAD`, so the failure cannot be an
effect of the removal. It is recorded as F001 rather than repaired here, because this story's
constraints forbid modifying the maintained account smoke script and broadening into script cleanup.

**QA plan deviation, preserved for independent review.** `agent/qa-plans/QA-STORY-WEB-027-…json`
(`NO_TESTS_NEEDED`) requires `npm run smoke:account` to exit 0. That expectation is left unchanged and
unmet: the exit code is 1 for the pre-existing contract drift documented above. The evidence that the
removal itself is complete and inert is the three command groups above.

**Other work this commit will carry.** The working tree already held maintainer changes to
`agent/runtime/**` (core, QA agent, support, their tests) and to docs. Since the harness commits the
whole tree, the CI-gated harness suite was run locally:
`python -m pytest agent/runtime/tests -q --import-mode=importlib -o consider_namespace_packages=true`
→ `404 passed, 52 subtests passed in 14.60s`. The package guard in `agent/runtime/tests/__init__.py`
(model runners, git push, GitHub API, `agent/logs/` writes and `ATTEMPT_STATE.json` all redirected or
refused) was read and confirmed in place before that run. No frontend source file is modified in the
tree, so no Vitest or Maven suite covers anything in this commit; `npm run smoke:profit` was not run,
as the maintainer's `profit-browser-smoke.mjs` edit is outside this story and is not CI-gated.

The Vite dev server started for the smoke run was stopped afterwards and `curl http://127.0.0.1:5173/`
confirmed to refuse the connection; stopping the wrapper left the `vite` process listening, so its
process tree was killed by PID before that probe.

## Blockers

None.

## Follow-up Findings

F001: `frontend/scripts/account-browser-smoke.mjs` fails at step 3 against the current Bank screen —
it asserts the superseded `InventoryItem.vue` contract (`[data-test="item-identity"]`, `× <count>`),
while `BankScreen` renders `InventoryTile.vue`, which has no identity hook, prints a bare localized
count and hides the quantity when `count === 1`; the Materials comparison asserts the same two hooks,
so it is likely drifted as well. `npm run smoke:account` therefore cannot pass until the comparison is
retargeted at the hooks the screens actually render.

F002: throwaway control copies are not ignored by git — `git check-ignore -v
frontend/scripts/.tmp-control-unrelated-call.mjs` exits 1 and `.gitignore` contains no `tmp` pattern —
so the accidental tracking this story cleaned up can recur the next time a control copy is generated
next to a smoke script, as `tasks/lessons.md` instructs.
