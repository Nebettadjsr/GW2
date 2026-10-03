## Story ID

STORY-WEB-018

## Title

Reject stale frontend bundles in browser smoke checks

## Status

DONE

## Milestone

milestone-05

## Goal

Ensure browser smoke scripts exercise a frontend bundle built from the current source instead of silently using stale output.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-DOM-024-ectoplasm-fee-policy-alignment.md` Follow-up Findings F002: smoke scripts check only that `frontend/dist/index.html` exists before launching Chrome.

## Context

The finding identifies the Ectoplasm browser smoke script and other `smoke:*` scripts sharing the same existence-only guard. A stale bundle can therefore produce misleading browser evidence after additive source changes.

## Acceptance Criteria

1. Each affected browser smoke script verifies that its frontend bundle reflects the current source before launching Chrome, or performs the required build itself.
2. A missing or stale bundle fails with a clear actionable message; an up-to-date bundle proceeds.
3. Apply the guard consistently to all smoke scripts that share the identified existence-only pattern without weakening their browser/API assertions.
4. Record the affected scripts and verification evidence in Result.

## Required Tests

- Targeted checks demonstrating that affected smoke scripts reject missing and stale output and accept current output.
- Run the affected browser smoke scripts against a current build and record results.

## Constraints

- Keep the work limited to validating/building the frontend bundle for browser smoke checks.
- Preserve existing smoke assertions and avoid unrelated script cleanup.

## Dependencies

None.

## Definition of Done

Affected browser smoke scripts reliably detect stale output, targeted checks and browser smoke evidence are recorded, and the story revision passes the CI gate.

## Result

**DONE.** One new module, one new spec, one config line and the seven dist-serving smoke scripts.

### Affected scripts (AC 3)

The existence-only guard `check(existsSync(…index.html), 'frontend/dist is missing …')` appeared in
exactly the seven scripts that serve `frontend/dist/` through `scripts/stubOrigin.mjs`. Each now
calls the shared guard instead and prints what it compared:

| Script | npm script | Guard call |
| --- | --- | --- |
| `scripts/sync-browser-smoke.mjs` | `smoke:sync` | `requireFreshBundle()` at `run()`'s first line |
| `scripts/layout-browser-smoke.mjs` | `smoke:layout` | same |
| `scripts/profit-browser-smoke.mjs` | `smoke:profit` | same |
| `scripts/icon-browser-smoke.mjs` | `smoke:icons` | same |
| `scripts/discovery-browser-smoke.mjs` | `smoke:discovery` | same |
| `scripts/ecto-browser-smoke.mjs` | `smoke:ecto` | same |
| `scripts/favicon-browser-smoke.mjs` | `smoke:favicon` | same, its three favicon file checks kept |

Not affected, and deliberately unchanged: `smoke:browser`, `smoke:account`, `smoke:profit:live`,
`smoke:discovery:live`, `smoke:ecto:live`, `check:icons:live` and `perf:profit-page` never read
`frontend/dist`. They open an origin the operator started (`GW2_FRONTEND_URL`, `GW2_LIVE_PAGE_URL`,
`GW2_PERF_PAGE_URL`), so they hold no bundle to validate and had no existence-only guard to fix.

### How the guard decides (AC 1, AC 2)

`frontend/scripts/bundleFreshness.mjs` — `requireFreshBundle()` throws unless `dist/` can stand as
evidence about the current source, and otherwise returns the comparison for the caller to print:

1. missing output — `dist/index.html` absent;
2. incomplete output — the emitted document references a same-origin file `dist/` does not contain;
3. stale output — any build input (`index.html`, `package.json`, `package-lock.json`, `public/`,
   `src/` recursively, `tsconfig.json`, `vite.config.ts`) has an mtime newer than `dist/index.html`.

Every message names the file and both timestamps and ends in ``run `npm run build` in frontend/
first``. The guard refuses rather than building: a build inside the check would create the very
output the check is meant to report on. No browser or API assertion was touched; the only other
change to the seven scripts is one `Bundle  : …` header line, so step numbering and counts are
unchanged.

### Tests run (real output, AC 4)

From `frontend/`, real Chrome, stub origins on `127.0.0.1` in each script's own process.

- `npx vitest run scripts/__tests__/bundleFreshness.spec.mjs` — `Test Files 1 passed (1) | Tests 7
  passed (7)`: missing, stale (naming the nested file and both ISO timestamps), incomplete, current,
  same-millisecond, no-input, and `requireFreshBundle` throwing versus returning. Each case builds a
  throwaway `frontend/`-shaped tree in the OS temp directory with explicit `utimesSync` times.
- `npm test` — `Test Files 22 passed (22) | Tests 302 passed (302)`, run because
  `vite.config.ts`'s `test.include` now also collects `scripts/**/*.spec.mjs`.
- `npm run build` — clean, `✓ built in 442ms`.
- Stale control on the shipped files, by touching `src/App.vue`'s mtime only (`git status` clean
  afterwards, no content change): `node scripts/ecto-browser-smoke.mjs` exit **1**, reporting
  `FAILED after 0 step(s)` with `frontend/dist is stale: src/App.vue was modified
  2026-10-03T07:48:36.150Z, after dist/index.html was built 2026-10-03T07:48:31.654Z` and the
  build instruction; `npm run smoke:layout` refused with the same message.
- Missing-output control, with `dist/` moved aside: `node scripts/favicon-browser-smoke.mjs` exit
  **1**, reporting `FAILED after 0 step(s)` with
  `frontend/dist/index.html is missing` and the build instruction (output restored by
  `npm run build`).
- After the rebuild every script accepted the bundle and printed
  `Bundle  : dist/index.html built 2026-10-03T07:49:03.548Z; newest build input src/App.vue
  2026-10-03T07:48:36.150Z`:
  `npm run smoke:ecto` **PASSED (6 steps)**, `npm run smoke:favicon` **PASSED (7 steps)**,
  `npm run smoke:sync` **PASSED (8 steps)**, `npm run smoke:icons` **PASSED (10 steps)**.
- Three scripts proceeded past the guard and then failed on pre-existing, unrelated stale selectors
  and expectations — recorded as F002–F004 and left unfixed, as this story's constraints forbid
  unrelated script cleanup: `smoke:layout` `FAILED after 2 step(s)` waiting for
  `[data-test="sync-controls"]`, `smoke:profit` `FAILED after 6 step(s): The detail region did not
  contain "For one craft".`, `smoke:discovery` `FAILED after 3 step(s): The controls are not grouped
  the way the corrected Profit pattern asks: ` (empty legend list). Each was reproduced on the
  committed file via a throwaway `git show HEAD:…` copy (`scripts/.tmp-head-layout.mjs`,
  `.tmp-head-profit.mjs`, `.tmp-head-discovery.mjs`, all deleted; `git status` lists none of them),
  which failed identically — so the guard is not their cause.

No Java or TestFX suite was run: nothing outside `frontend/scripts/`, `frontend/vite.config.ts` and
`docs/TEST_STRATEGY.md` changed, and `git status` showed no maintainer edit to product code in the
tree (commit `8bb8126` had just taken the earlier documentation changes). None of these browser
scripts runs in CI (`TEST_STRATEGY.md` §12.2); GitHub Actions remains the full-regression gate and
will run the new spec through `npm test`.

### Documentation

`docs/TEST_STRATEGY.md` §12.2 gained the methodology rule it owns: a check that serves the
production build must establish that the build is the current source, and require the build rather
than perform it.

### Remaining uncertainty

Modification times are the signal, so a build input rewritten with an older timestamp would pass,
and `npm run build`'s own type-check means a spec edit also marks the bundle stale (a rebuild, not a
false failure). Three of the seven scripts cannot currently reach a full pass for reasons outside
this story.

## Blockers

None.

## Follow-up Findings

F001: `frontend/scripts/.tmp-control-unrelated-call.mjs` is tracked in git — a throwaway control copy
of `account-browser-smoke.mjs` committed by an earlier story. No npm script references it.

F002: `layout-browser-smoke.mjs:47` waits for `[data-test="sync-controls"]` as the `synchronization`
area's readiness selector, but no such hook exists in `src/` any more (`SyncScreen.vue` exposes
`sync-state-*`, `account-status`, `global-refresh-status`, …), so `npm run smoke:layout` cannot get
past step 3 on the current tree and every later layout, contrast and label assertion is unreachable.

F003: `profit-browser-smoke.mjs:650` requires the selected-result detail to contain `For one craft`,
while `src/crafting/__tests__/SelectedResultDetail.spec.ts:104` asserts the detail must **not**
contain that phrase. The two contradict each other and `npm run smoke:profit` fails at step 7.

F004: `discovery-browser-smoke.mjs:393` reads legends from
`[data-test="discovery-calculation-controls"] > legend` and `…discovery-display-controls"] > legend`,
but `CraftingDiscoveryScreen.vue:179,186` render those hooks as `div`s with no `legend` child, so the
comparison is against an empty list and `npm run smoke:discovery` fails at step 4.

F005: `agent/stories/BACKLOG.md` holds this story's `backlog entry: …` summary line as the first line
of `## To Do` while its row sits under `## Active` — the summary line was left behind when the row was
moved.
