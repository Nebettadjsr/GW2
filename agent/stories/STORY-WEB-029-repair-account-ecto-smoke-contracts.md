## Story ID

STORY-WEB-029

## Title

Repair stale account and Ectoplasm browser smoke contracts

## Status

TODO

## Milestone

milestone-05

## Goal

Restore the account and Ectoplasm live browser smoke checks so they verify the current browser-owned workflows and rendered contracts.

## Authoritative Source Documents / Sections

- Supplied Phase 5 roadmap excerpt: complete and verify browser feature workflows; browser smoke coverage is tracked in canonical stories and TEST_STRATEGY.md.
- `docs/DOMAIN_SPEC.md` ?2.3.1, Ecto Salvage browser-owned calculation inputs and behavior.
- `docs/TEST_STRATEGY.md` browser smoke testing section.
- `agent/stories/STORY-WEB-021-ecto-content-test-hooks.md`, Follow-up Findings F002.
- `agent/stories/STORY-WEB-027-remove-tracked-smoke-control-copy.md`, Follow-up Findings F001.

## Context

The current account smoke script queries the superseded `InventoryItem.vue` identity and quantity hooks although the Bank and Materials screens use `InventoryTile.vue`. The Ectoplasm live smoke script calls the removed backend calculation route and waits for a scenario table from the former backend-owned screen. These checks therefore do not verify the current workflows.

## Acceptance Criteria

1. `npm run smoke:account` uses rendered hooks and quantity behavior that the current Bank and Materials screens actually provide.
2. `npm run smoke:ecto:live` exercises the frontend-owned Ectoplasm calculation using its current inputs and rendered result hooks; it makes no request to the removed backend calculation route.
3. Both scripts retain meaningful assertions for their workflows and report failures when expected content is absent.

## Required Tests

- Run `npm run smoke:account` against the current browser application and verify its Bank and Materials checks pass.
- Run `npm run smoke:ecto:live` against the current browser application and backend inputs and verify its Ectoplasm assertions pass.
- Run the relevant frontend component tests and review affected coverage; these scripts validate browser integration behavior not covered by component tests.

## Constraints

Preserve existing product behavior and the frontend-owned Ectoplasm calculation. Do not restore the removed backend route or change domain calculations.

## Dependencies

None.

## Definition of Done

Both named smoke checks pass against the current application, their assertions correspond to rendered workflow behavior, and relevant coverage reports and unresolved quality gaps are reviewed. The CI gate is green.

## Result

Both live checks were retargeted at the workflows the current screens own, and both pass against the
running application. No `src/**` file was changed — only the two scripts.

**`frontend/scripts/account-browser-smoke.mjs` (AC 1).** The Bank and Materials comparisons queried
the superseded `InventoryItem.vue` hooks (`[data-test="item-identity"]`, `× <count>`,
`[data-test="material-stack-category"]`), none of which the screens render. They now compare what
`BankSlotTile.vue` / `MaterialsScreen.vue` + `InventoryTile.vue` actually render, field for field
against the response:

- bank slot order and empty-slot positions (unchanged);
- each slot's stated identity and owned count, read from the slot's own `title`
  (`Item #<id> · <count> in slot #<slot>` / `Empty slot #<slot>`), since there is no identity hook;
- the painted `[data-test="item-count"]` badge **including where the tile omits it** — a new shared
  `quantityBadge(count, { showOne })` helper mirrors `InventoryTile.vue`: a bank slot holding one item
  shows no badge, a material position shows one down to a single unit and none at all when unowned,
  and the digits are grouped `en-US` by the component so this is browser-locale independent;
- each material position's `title` and its greyed `inventory-tile--empty` treatment.

Two further assertions in the same script had expired against current behavior and are repaired in
the same pass, because the command cannot reach its own last step otherwise. When a read answers
`ACCOUNT_DATA_STALE`, `frontend/src/api/http.ts` polls `/api/sync/tasks/{id}` until the refresh the
backend already had running finishes and then repeats the read once. The first run after retargeting
failed on exactly that (`["/api/sync/tasks/2726523e-…","/api/account/bank"]`). So:

- the reload window now separates icon requests, task-status polls and the continuation of any call
  that was already on the wire at click time, and requires every remaining request to be
  `/api/account/bank` — once, or twice when a poll occurred;
- the "no synchronization" step no longer forbids the `/api/sync` prefix outright. It forbids anything
  that *starts* work — any `/api/prices` call and any `/api/sync` call that is not a `GET` of
  `/api/sync/tasks/{id}` — and reports the read-only poll count instead.

Three assertions now also fail rather than pass vacuously: an empty bank read, a bank whose every
rendered slot is empty, and a materials read with no stacks are each reported as "this run would
compare nothing", not as a pass.

**`frontend/scripts/ecto-live-smoke.mjs` (AC 2).** Rewritten for the browser-owned calculation. It no
longer calls `/api/ecto/salvage` or waits for `[data-test="ecto-scenario-table"]`; it asserts over the
whole run that the browser requested that route zero times, and that the page asked the backend for
nothing beyond its three inputs and its item images. What it establishes:

1. measured runtime of the three live input reads (`/api/items/metadata`, `/api/items/prices`,
   `/api/account/luck`) at the backend, replacing the removed route's measurement for
   `TARGET_ARCHITECTURE.md` 23. A refresh the backend already has running is waited out *before*
   sampling, so a stale account read model is a precondition rather than a measurement artifact;
2. readiness taken from `[data-test="ecto-result"]` and `[data-test="ecto-account-luck"]`, and the
   live item names rendered in the two price blocks;
3. the rendered result **derived here** from the quotes in the very responses the browser received
   and the yields the page states for the selected tool, straight from `DOMAIN_SPEC.md` 46/47:
   expected Luck and Dust, Ecto value consumed, net Dust value with the 15% fee deducted once,
   effective cost as the relation between those parts, and cost per 1,000 Luck. No expected value is
   read out of the page's own result, so a moved market moves both sides together while a wrong
   calculation does not. The selected tool's per-use coin cost is the one figure taken from the page
   (a published price, pinned by `smoke:ecto`, not a domain rule); the run fails rather than
   approximating if it is not a whole copper amount per use;
4. the Magic Find target rows and the account Luck summary against the live Luck read model at the
   same selected tool and quotes;
5. that changing the amount, the tool and both price modes recalculates in the browser — a changed
   effective cost with the backend call count unchanged (`DOMAIN_SPEC.md` 2.3);
6. that "Refresh TP prices" rereads only the two prices (metadata and Luck read counts unchanged) and
   the result follows that answer;
7. no ArenaNet request, no uncaught page error.

Locale handling: every count is formatted by the page itself (`page.evaluate(… toLocaleString())`), so
neither side depends on Node's default ICU locale.

### Verification

Backend `./mvnw spring-boot:run` on `127.0.0.1:8080` (already running, live GW2 API and the real
database); dev server started for this run as `npx vite --port 5193 --strictPort --host 127.0.0.1`,
driven as `GW2_FRONTEND_URL=http://127.0.0.1:5193`. The server was stopped afterwards and
`curl http://127.0.0.1:5193/` confirmed to refuse the connection.

| Command | Result |
| --- | --- |
| `npm run smoke:account` | **PASSED (8 steps)**, exit 0 — 180 bank slots (slotCount 180), 16 empty, 164 occupied, 71 painted quantities and 93 deliberately omitted for a single held item; 9 material categories, 681 positions, 510 owned with a painted quantity and 171 unowned kept in place and greyed out; 681 images all on `/api/items/{id}/icon/…` with `no-referrer`; 494 browser requests, all same-origin |
| `npm run smoke:ecto:live` | **PASSED (7 steps)**, exit 0 — medians `/api/items/metadata` 45ms, `/api/items/prices` 242ms, `/api/account/luck` 1079ms (5 samples each); opening compared at 1710c Ecto / 899c Dust, 1.85 Dust and 104.57 Luck per Ecto, 60c per use → effective cost `3g 56s 33c`, 3408c per 1,000 Luck; after switching to 250 Ectos, Basic/Copper-Fed, Buy order and Listing sell → `8g 15s 4c` with the backend call count unchanged at 10; 4 Magic Find target rows compared; 92 requests, 0 to `/api/ecto/salvage`, 0 to ArenaNet |
| `npx vitest run src/account src/ecto` | **6 files, 47 tests passed** — `BankScreen`, `MaterialsScreen`, `useAccountRead`, `AccountPageHeaderContract`, `EctoSalvageScreen`, `EctoContentHooks` |

**Negative controls (AC 3).** Each proved from a throwaway copy under `frontend/scripts/.tmp-control-*.mjs`,
never by editing a shipped file; all four copies were deleted and `git status --untracked-files=all`
confirmed none remains:

| Control | Outcome |
| --- | --- |
| bank quantity badges removed from the DOM before extraction | exit 1 at *"The painted slot quantities do not match the response, badge for badge and omission for omission."* |
| material tile `title` attributes stripped before extraction | exit 1 at *"The rendered stacks do not match the response."* |
| the live Ecto quote the comparison derives from nudged by 1c | exit 1 at the Ecto-value-consumed figure (`-17g 11s 0c` vs `-17g 12s 0c`, i.e. 100 × 1c) |
| `/api/ecto/salvage` fetched from the page | exit 1 at *"The browser requested the removed backend calculation route /api/ecto/salvage"* |

### Coverage and unresolved quality gaps

Only the two `.mjs` scripts changed, and `vite.config.ts` scopes coverage to `src/**/*.{ts,vue}`, so
the coverage baseline is untouched: `frontend/coverage/coverage-summary.json` still reports 91.14%
lines / 88.61% statements / 90.02% functions / 84.99% branches overall. In the areas these checks
exercise: `BankScreen.vue` 100/100/100/95.65, `MaterialsScreen.vue` and `InventoryTile.vue` 100
throughout, `useAccountRead.ts` 100/100/100/90, `EctoSalvageScreen.vue` 97.23/94.05/93.75/92.05.

Two gaps below the 90% aim remain, both pre-existing and both outside this story's scope (recorded as
findings): `BankSlotTile.vue` branches 78.94% and `InventoryItem.vue` at 0%.

### Remaining uncertainty

The manual-refresh step (ecto, step 6) re-derives the result from the refreshed response, but on both
final runs the live quotes were unchanged between the two reads, so that step did not in fact observe
the result *move* to a new answer — only that it still matches the latest one and that metadata and
Luck were not reread. The changed-price case is covered deterministically by `smoke:ecto` against
controlled responses. Both commands are live checks the CI gate cannot run (`TEST_STRATEGY.md` 31.4):
they need this machine's database, account and network.

## Blockers

None.

## Follow-up Findings

F001: `frontend/src/account/InventoryItem.vue` — the component whose hooks this story's account smoke
was still asserting — is now referenced by nothing (`grep -rn InventoryItem frontend/src` matches only
its own file; Bank renders `BankSlotTile.vue` and Materials renders `InventoryTile.vue` directly). It
measures 0% lines, statements and branches in `frontend/coverage/coverage-summary.json`.

F002: `frontend/src/account/__tests__/BankScreen.spec.ts:194` iterates
`wrapper.findAll('.slot--empty')`, but the current class is `.bank-cell--empty`
(`BankSlotTile.vue:42`), so that loop body never executes and its "an empty slot renders no icon"
assertion is never made. `BankSlotTile.vue` branch coverage is 78.94%, the lowest in `src/account`.

F003: `account-browser-smoke.mjs` ends by printing every observed backend call, which on live data is
one ~700-entry line of icon URLs (~60 KB). It pushes the step lines that are the actual evidence out
of any bounded log view, and had to be filtered out by hand to read this run's outcome.
