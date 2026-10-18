## Story ID

STORY-WEB-003

## Title

Render bank and material storage in the browser

## Status

DONE

## Milestone

milestone-05

## Goal

Provide reachable bank and materials views in the existing browser frontend using the completed account-read HTTP endpoints, preserving backend inventory facts without reproducing inventory rules.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: build bank/materials views; frontend rendering/interaction tests; backend authority and JavaFX coexistence.
- `docs/TARGET_ARCHITECTURE.md` sections 4.1 and 12: Vue 3 with strict TypeScript, backend API communication and presentation-only responsibilities.
- `docs/CURRENT_ARCHITECTURE.md` section 5.12: bank/material-storage routes, response fields, ordering, nulls, grouping and failure contracts.
- `agent/stories/STORY-API-007-bank-materials-read-endpoints.md`: completed read endpoints and recorded limitations, including absent item names.
- `agent/stories/STORY-WEB-001-crafting-profit-table.md`: completed browser foundation.

## Context

The two account-read routes are complete; their recorded Result explicitly leaves browser consumers unimplemented. Bank data retains empty slots and material storage supplies ordered, labelled groups. Neither response supplies item names, and the presence of an iconPath does not establish that a browser can fetch it. This story consumes the existing contracts without expanding them.

## Acceptance Criteria

1. Add reachable Bank and Materials views in the existing Vue frontend. Opening each view loads its respective `GET /api/account/bank` or `GET /api/account/materials` route; an explicit reload repeats only that read. Navigation and reload never trigger synchronization.
2. Render bank entries in the supplied slot order with slot identity, item identity and count where present. Preserve empty slots in their positions; null item/count values represent an empty slot and are never coerced into item id zero or an owned count of zero. Distinguish a successful zero-slot response from a bank containing empty slots.
3. Render the supplied materials categories, labels and stack order unchanged, including backend fallback category labels. Present each supplied stack's item identity and count; do not regroup, deduplicate, aggregate inventory or implement category/inclusion rules in the browser.
4. Use supplied rarity and display metadata where applicable. Identify items by their supplied ids when no name is provided; do not invent names or fetch item details from GW2. Missing or unusable icon metadata must leave a usable inventory entry with a neutral visual fallback. Do not assume filesystem icon paths are browser URLs or introduce a new asset-serving endpoint in this slice.
5. Present loading, successful empty, populated and failed reads distinctly for each view. Show sanitized backend error information where available and allow an explicit read retry. A failed reload must not masquerade as an empty inventory or silently present previous data as newly loaded. Late responses after navigation or a superseding load must not replace the current view's state.
6. Keep all data access through the backend HTTP API. Preserve existing Crafting Profit and sync-control behavior and JavaFX access through the same application services; no domain, persistence, HTTP contract or JavaFX changes are required.
7. Update `docs/CURRENT_ARCHITECTURE.md` with the implemented browser flow. Record actual checks and limitations in Result, including item-name/icon limitations and whether browser evidence used controlled responses or the real backend. Do not claim Phase 5 completion or satisfaction of the Crafting Profit full-page performance gate.

## Required Tests

- Component/interaction tests with controlled API responses proving route selection, explicit reload, bank slot/order/null preservation, material category/order/label preservation, supplied counts and item identities, and missing metadata fallback.
- Cover successful empty reads separately from 503/500 and transport failures, failed reload/retry, navigation and late-response isolation. Verify no sync trigger or external GW2 request occurs.
- Run strict TypeScript checking, the production frontend build and existing frontend regressions relevant to shared navigation and HTTP handling.
- Exercise both views in a real browser against the running backend with the real user database using read-only requests. Compare visible entries, empty bank positions and material category labels with the corresponding HTTP responses. Use controlled responses for cases unavailable in that database, and identify that distinction in Result. Do not mutate the database to manufacture states.

## Constraints

- Reuse the established Vue/TypeScript frontend and existing HTTP contracts; keep this a bounded presentation slice.
- No frontend secrets, direct GW2 requests, authoritative inventory calculations, new synchronization workflow, item-detail API or icon-serving infrastructure.
- No inferred item names, category map, missing count defaults or inventory inclusion policy.
- Other screens, resolution trees, final performance acceptance and milestone health review remain outside this story.

## Dependencies

STORY-WEB-001 (DONE) and STORY-API-007 (DONE). STORY-WEB-002 precedes this story in execution priority but is not an API prerequisite.

## Definition of Done

Both browser views accurately present the existing backend inventory responses, meaningful rendering/state checks pass, strict type checking and the frontend build succeed, and documentation and Result record actual evidence and limitations. No milestone transition is declared.

## Result

DONE. An interrupted attempt was resumed, not restarted: the five frontend source files, their four
test files and `scripts/account-browser-smoke.mjs` were already present and passing, so this session
verified them, produced the missing real-browser evidence, and wrote the documentation and records
the story still lacked. **No Java, Maven or resource file was touched** (the Java changes in the
working tree predate this story, newest 01:54 vs. this slice's 02:37–02:41), so no domain,
persistence, HTTP contract or JavaFX behavior changed.

### What exists

| File | Role |
|---|---|
| `frontend/src/api/accountApi.ts` | the two §5.12 reads behind an interface a test can substitute |
| `frontend/src/account/useAccountRead.ts` | one read's phase/data/failure, explicit reload, superseded-answer guard |
| `frontend/src/account/BankScreen.vue` | supplied slot order, empty slots kept in place, four states |
| `frontend/src/account/MaterialsScreen.vue` | supplied categories, labels and stack order, unchanged |
| `frontend/src/account/InventoryItem.vue` | one entry: supplied item id, count, rarity, neutral icon fallback |
| `frontend/src/App.vue` | three-screen navigation; only the selected screen is mounted |
| `frontend/src/api/types.ts` | `BankSlot`/`BankContents`/`MaterialStack`/`MaterialCategory`/`MaterialStorage` |
| `frontend/src/account/__tests__/*`, `frontend/src/__tests__/App.spec.ts` | the checks below |
| `frontend/scripts/account-browser-smoke.mjs` | real-browser check against the running backend |

Behavior: a null `itemId`/`count` is never read as id `0` or an owned count of `0` (a missing count
renders `—`); an empty slot is the both-null case and stays in its position; `slotCount: 0` is a
distinct message from a bank containing empty slots; nothing is regrouped, deduplicated, sorted or
aggregated, and no category map or inclusion rule exists in the browser; a read that fails clears
the previous answer first, so a failed reload can neither look like an empty inventory nor present
stale data as newly loaded; a superseded or post-unmount answer is discarded.

### Checks actually run

- `npm test` — **84/84 passed** (10 files), controlled responses only. Account-specific coverage:
  route selection on open and reload-repeats-only-that-read for both screens; slot order with empty
  slots in place; the both-null slot never coerced; supplied identity/count/rarity; zero-slot vs.
  empty-slot distinction; loading state; 503 `DATA_STORE_UNAVAILABLE`, 500 `ACCOUNT_READ_FAILED` and
  an unreachable backend each shown as failures rather than emptiness, with retry; reload failure not
  presenting the earlier bank; category/label/stack-order preservation including a `"Category 77"`
  fallback and the same item id in two categories; a stack without an item id keeping its count; no
  `<img>` and no supplied path in the DOM; and in `useAccountRead`/`App.spec`, superseded answers,
  post-unmount answers, and a late bank answer not reaching the Materials screen.
- `npm run type-check` — clean. `npm run build` — OK (88.44 kB JS, 32.52 kB gzip).
- `npm run smoke:account` — **PASSED, 8 steps**, real Chrome, dev server, **real backend and the
  user's real PostgreSQL database**, read-only. No account read before a screen is opened; Bank HTTP
  200 with **180 slots (slotCount 180), 17 empty, in the supplied order**; reload issued
  `/api/account/bank` and nothing else; Materials HTTP 200 with **9 categories, 505 stacks**; the
  rendered slot order, empty-slot positions, item ids, counts, category labels and stack order were
  compared field-for-field against a second read of each route and matched; zero `<img>` elements; no
  `/api/sync` or `/api/prices` call, no non-backend call, no page error.
- Live data exercised the fallback-label and missing-metadata paths for real: 7 of the 9 categories
  came back as `"Category <id>"`, and 89 of the 180 bank slots were occupied with `iconPath` and
  `rarity` null — all rendered with the neutral placeholder.
- Regression: `npm run smoke:browser` — **PASSED, 8 steps** against the same live backend (3175 rows,
  sort, search, scope change), so the new navigation did not disturb Crafting Profit;
  `npm run smoke:sync` — **PASSED, 8 steps** against its own controlled origin.

### Limitations

- **Controlled responses only, never observed live:** every failure state (503/500/transport), the
  retry path, a successful zero-slot bank, empty material storage, a stack without an item id, and
  the late-response/navigation isolation. The live database produced none of them, and none was
  manufactured — nothing was written to it.
- **No item names and no icon images.** Neither route supplies a name (`STORY-API-007`), so items are
  identified by `#<itemId>`; `iconPath` is a backend filesystem path, so no image is requested, every
  entry uses the same neutral placeholder, and no item-detail lookup or asset-serving endpoint was
  added. Two paths that are genuinely different — no metadata supplied, and metadata supplied but not
  browser-usable — therefore look identical to a user beyond a tooltip.
- All 180 slots and 505 stacks go into the DOM; there is no virtualization, paging or filtering, and
  no search on either screen. Navigation is component state, not a route, so a page reload returns to
  Crafting Profit.
- TypeScript describes transport shape only and validates no received JSON.
- §33 performance and the Crafting Profit full-page performance gate are **not** claimed, no Phase 5
  exit criterion is claimed and no milestone transition is declared.

### Incident during verification, and the fix

Two `smoke:sync` runs in this session **started real synchronizations against the user's live
database and the GW2 API** — an account sync, a global-data sync and two Trading Post price
refreshes. Cause: a leftover Vite dev server from the interrupted attempt was listening on
`[::1]:5174` only, so the script's stub bound `0.0.0.0:5174` successfully while Chrome resolved
`localhost` to `::1` and loaded that dev server, whose `/api` proxy reaches the real backend. The
script noticed nothing and reported a step timeout. The operations ran to completion rather than
being interrupted (`Global recipes + items + ingredients synced`, graph cache rebuilt, TP prices
synced), so the database is consistent and fully refreshed, not partially written; the rebuilt
`crafting_graph_cache.json` is gitignored.

`scripts/sync-browser-smoke.mjs` was hardened in response — the only file changed outside the
story's own slice: it now binds and opens `127.0.0.1` instead of the name `localhost`, rejects on a
`listen` error instead of ignoring it, and asserts its own server served the page before any control
is clicked. Verified in the still-polluted environment: the run then passed on `127.0.0.1:5174` with
the stray dev server still on `[::1]:5174`. Recorded in `tasks/lessons.md` and as rule 3 of
`docs/TEST_STRATEGY.md` §12.3.

### Documentation updated

`docs/CURRENT_ARCHITECTURE.md` §5.11 (title, file layout, navigation and Bank/Materials flow blocks,
"not built yet", commands, smoke-check paragraph) and §5.12's opening line (the routes now have a
browser consumer); `docs/TEST_STRATEGY.md` §12.2 table row and §12.3 rule 3; `README.md` browser
section and command table; `tasks/lessons.md`.

## Blockers

None.
