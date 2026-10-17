## Story ID

STORY-WEB-010

## Title

Render backend-supplied icons through one reusable frontend component

## Status

DONE

## Milestone

milestone-05

## Goal

Apply the resolved icon-delivery strategy across existing Profit, bank/materials and item-detail views, with usable fallbacks and explicit browser/network evidence.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5 frontend views, backend authority and full-page performance criterion.
- docs/TARGET_ARCHITECTURE.md section 12.1: AR-005 shared icon rendering, persistent backend delivery, browser caching and verification; section 33: complete-page real-database performance.
- agent/stories/STORY-API-009-web-item-icon-metadata.md: prerequisite URL/null contracts.
- agent/stories/STORY-WEB-003-bank-materials-views.md and STORY-WEB-007-profit-resolution-detail-view.md: existing consumer surfaces.

## Context

The metadata prerequisite supplies a browser-usable field. The shared component is reused in the existing screens and is available for subsequent Phase 5 Discovery/Ecto work; this story does not build those screens.

## Acceptance Criteria

1. Implement one shared icon component consuming backend iconUrl and item identity; use it in Profit rows, selected recipe/material/tree details, Bank and Materials. Consume actual output/node item metadata, never construct an image URL from a recipe ID or query GW2 metadata in the browser.
2. Apply section 12.1's delivery and caching rules: ordinary images using the supplied application-relative URLs through application API routing, no-referrer policy, stable URLs, reserved dimensions, prompt visible-image loading and appropriate native offscreen lazy loading. Verify development routing forwards image paths. No browser request or fallback to ArenaNet; CSP needs no ArenaNet image-origin exception.
3. Use one bundled neutral fallback for null/rejected metadata and load/decode failures, without retry loops; reset failure state on identity/URL change. Keep item text, quantities, rarity and domain states usable. Empty bank slots stay empty. Avoid duplicate accessible item announcements and retain row/detail keyboard interaction.
4. Browser network assertions must permit only application image delivery and continue prohibiting browser GW2 JSON requests. Verify actual backend-served images separately from controlled failures. Verify browser caching/revalidation and backend restart reuse with warm storage; with upstream unavailable, cached entries still render and generate zero upstream image requests. Record browser/backend/upstream evidence rather than inferring a disk hit from a rendered image.
5. Reverify the full navigation-to-complete-Profit-page boundary against the real user database under section 33 for cold browser/cold application cache, cold browser/warm application cache and warm browser/warm application cache per section 12.1. Use isolated configured storage for cold-cache checks rather than deleting the user's cache. Record metadata coverage, first/repeat openings, settings, complete requested result counts, environment, individual timings/maxima, browser/backend/upstream requests, cache conditions and image completion/failures. Do not conceal pending images, truncate requested results, or use lazy loading/forced placeholders to claim completion. Record successful delivery separately from unavailable-image fallback runs.
6. Report an unmet or unavailable performance check explicitly for planner disposition; do not claim success from backend timings or invent acceptance. Update CURRENT_ARCHITECTURE with actual browser behavior and evidence limits. This story cannot close Phase 5 or substitute for its bounded health review.

## Required Tests

- Shared-component tests for success, null, image/decode failure, identity changes, stable URL/referrer/dimension behavior and accessible fallback.
- Consumer tests for all listed views, empty bank slots, repeated tree items, unchanged selection and no browser metadata/calculation request from image rendering.
- Real-browser controlled-image checks for slow/failing images and narrow/wide layout stability; actual backend image integration with browser/backend/upstream network records, clearly separated from fixtures. Verify local revalidation, restart reuse and upstream-unavailable warm-cache delivery.
- Real-database complete-page measurements across all three cache combinations specified above, following the existing TEST_STRATEGY section 34 procedure. Record missing evidence or failure rather than weakening the gate.
- Affected frontend tests, type checking and build; no automatic broad regression expansion.

## Constraints

- No new Discovery/Ecto screen, service worker, IndexedDB/blob store, cache-busting URL, direct upstream fallback or image-generation dependency. Reuse API-009's backend delivery; do not introduce another cache implementation.
- No economic calculation or domain state inference from images.
- No live synchronization as an implicit browser-test fixture; metadata absence must exercise fallback honestly.
- Performance work here is verification; substantial evidenced remediation receives separate planner scope.

## Dependencies

- STORY-API-009 completed with browser transport migration.
- STORY-WEB-007 completed for resolution-tree rendering.

## Definition of Done

Existing item views share the decided image component with deterministic fallback evidence, actual persistent-backend/browser integration evidence and an honest real-database performance assessment across the required cache states for subsequent planner review.

## Result

Done, frontend plus one narrow infrastructure line. An interrupted attempt (capacity exhausted at
01:00, nothing committed) was **resumed, not restarted**: `items/ItemIcon.vue`, its suite, the six
consumer changes, `icon-browser-smoke.mjs`, `icon-live-check.mjs`, `recordingProxy.mjs` and
`upstream-proxy.mjs` were already on disk. This session verified them, wrote the one missing measurement
harness, fixed the three things that did not hold up, ran every check and recorded the evidence.

### One component, in every item view (AC 1)

`frontend/src/items/ItemIcon.vue` is the only place an item image is rendered. Consumers:
`CraftingProfitTable.vue` (inside the row-selection button, so the control's accessible name stays the
recipe), `SelectedResultDetail.vue` (the output item beside the heading at 32 px, and each entry of
both "still to buy" lists), `ResolutionTreeNode.vue` (each node's **own** item), and `InventoryItem.vue`
for Bank and Materials. Identity is the actual item: a row passes `row.outputItemId`, a node
`node.itemId`, a material its own `itemId`. The component builds no URL — `src` is the backend's
`iconUrl` string unchanged — so nothing is derived from a recipe id and no GW2 metadata is fetched in
the browser. Unit tests assert the rendered URL contains `/api/items/1101/` (the output item) and that
the page HTML contains neither `/api/items/11/` (the recipe id) nor `recipes/`.

### Delivery and caching rules (AC 2)

Ordinary `<img>`, application-relative URL verbatim (no query, no timestamp, no version),
`referrerpolicy="no-referrer"`, `decoding="async"`, `width`/`height` reserved plus a reserved wrapper
box, `loading="eager"` for the detail's one visible image and `loading="lazy"` for list entries. The
`<img>` is keyed by URL so a changed URL mounts a fresh element rather than re-pointing a failed one.
Development routing was verified **empirically**, not from config: the live check serves the page from
the Vite dev server, whose `/api` proxy forwarded 41–43 image requests per run into the recording proxy
and on to the backend. No browser request reached ArenaNet in any run (0 of 3 667 image requests in the
heaviest phase; 0 requests left the page's own origin at all). **No CSP is configured anywhere in this
repository** (`grep` for `content-security-policy`/`img-src` over `frontend/src`, `frontend/index.html`
and `src/main` finds nothing), so there is no ArenaNet image-origin exception to remove — and the
browser makes none that would need one.

### Fallback, text and interaction (AC 3)

One inline neutral SVG covers null metadata (absent *or* backend-rejected, both of which arrive as
null) and load/decode failure — inline on purpose, so the fallback for a failed image request is not
itself a request that can fail. A failure sets a flag and stops; `src` is never reassigned. In the
controlled browser run the 503, the 404 and the undecodable body were each requested **exactly once**,
and the 503 item, rendered in three places at once (a row, a tree node, a bank slot), was requested
exactly three times — three renderings, not a retry. The flag resets on an item-id *or* URL change, so
a different item in the same row, and the same item whose retained source changed, each get their own
attempt. `alt=""`/`aria-hidden` and nothing focusable: the row button's accessible text stayed exactly
`Iron Ingotrecipe 11`, Enter on a focused row still opened its detail, and item ids, counts, rarity and
the domain diagnostics were asserted intact beside failed images (`× 7`/`Rare` on the 503 slot). Empty
bank slots contain **no** icon element (asserted per slot in unit and browser tests).

### Browser/backend/upstream evidence, separated (AC 4)

- **Controlled failures** — `npm run smoke:icons` (built page, stub origin in the script's own process,
  `127.0.0.1:5177`): **PASSED, 10 steps**. 5 image requests all on
  `/api/items/{id}/icon/{64-hex}.{png|jpg}`; row URLs verbatim with no-referrer, reserved 20×20 and
  native lazy loading; a 1 200 ms image left all 6 row labels at identical positions and its box
  measured 20×20 while still pending; 15 square icon boxes and no horizontal overflow at 360×800; bank
  2 empty slots with no icon and 3 occupied with one each; 17 browser requests, all to the stub.
- **Actual backend delivery** — `node scripts/icon-live-check.mjs` against the real backend and the real
  user database, page served by the dev server, `/api` routed through `scripts/recordingProxy.mjs`,
  backend egress through `scripts/upstream-proxy.mjs`:
  - `pre-restart` **PASSED, 5 steps**: 43 images delivered, every one with `max-age=86400, public`, a
    strong ETag and a verified `image/png|jpeg`, 0 refused; on screen 74 images (51 decoded), 89 slots
    without metadata, 0 failed. Reopening the page in a second tab of the same profile re-requested
    **0 of the 43** the browser already held (5 first-time requests were icons lazy loading had
    deferred). Revalidation: **12 of 12** conditional requests reached the backend as
    `If-None-Match` → **304** with Cache-Control and ETag retained, 5 679 bytes still delivered to the
    page, 0 re-downloads, 0 upstream tunnels.
  - `post-restart` **PASSED, 4 steps**: the backend was stopped and started again over the same warm
    storage; the fresh process served 41 real images with **0 upstream CONNECT tunnels**.
  - `upstream-down` **PASSED, 4 steps**: the backend was restarted behind an upstream proxy in
    `--refuse` mode. 43 stored images still rendered, 0 failed, **0 upstream tunnels**. The refusal was
    proven armed first, by an independent `curl -x http://127.0.0.1:8096` whose CONNECT the proxy logged
    as `refused` — so the zero is "the backend never tried", against an upstream that would have failed.
- **Real-data account views** — `npm run smoke:account` against the real backend: **PASSED, 8 steps**.
  180 bank slots (17 empty) in the supplied order; 9 categories / 504 stacks; **504 images, all on the
  icon route, all `no-referrer`**; 251 browser requests, all to the page's own origin; no
  synchronization call and no page error. This run surfaced a defect the earlier attempt had missed —
  the bank-reload step asserted the reload produced *exactly* `['/api/account/bank']`, which item
  images now legitimately accompany; it separates and counts them (27 image requests) and still fails
  on anything else.
- **Nothing is inferred from a rendered picture**: browser requests come from the driver, backend
  requests from the recording proxy's own log, upstream access from the proxy's CONNECT lines.

### §33 full-page reverification, real database (AC 5)

`frontend/scripts/profit-page-perf.mjs` (new, `npm run perf:profit-page`). Completion is detected by
quiescence per `TEST_STRATEGY.md` §34: a 50 ms sample of displayed row count, the first and last row's
identity and displayed values, the summary and settings lines, the notices and every icon's state, plus
the number of calculations the page has issued — so a redundant reload recomputing identical numbers
still moves the sample. The reported time is the **last observed change**; the 3 s settle window is
excluded. A page counts as complete only when every in-viewport image has finished, and each run then
reads a real displayed value (`total-profit`) and checks the row control is enabled before calling it
interactive. Cold browser cache is a fresh, isolated, disposable Chrome profile directory created by
the script; **the user's own browser profile and cache were never touched**, and the backend ran against
an isolated `ICON_CACHE_DIR` under `%LOCALAPPDATA%\Temp\gw2-web010\icons`, never the user's
`~/.nebet-gw2-tool/icons`.

Environment: working tree at `e1d7f3d`, 6× Intel i5-9600K @ 3.70 GHz, 64 GB RAM, OpenJDK 25.0.1,
node v22.13.1, Chrome 152.0.7977.84, viewport 1440×900. Page served by `vite preview` (the production
build) → recording proxy → backend on `127.0.0.1:8091` → real PostgreSQL user database, read-only; no
synchronization was run. Scope **ALL**, settings as echoed: own materials used · buying off · max buy
1g 0s 0c · instant sell · instant buy · daily items bought · non-Trading-Post materials allowed.
Requested result count **3 176** rows (304 matching the default filters). Metadata coverage on the
page: **3 138 of 3 176 rows carried an `iconUrl`, 38 did not**; bank coverage 74 of 180 slots (89 of the
occupied ones without metadata), unchanged and deliberately not repaired.

| Phase | Opening | navigation → complete | rows shown / requested | calcs | backend requests (profit ms; images) | upstream tunnels | icons on screen |
|---|---|---|---|---|---|---|---|
| cold browser / cold app cache (0 stored entries) | first, document navigation | **2 645 ms** (first rows 1 978) | 250 / 3 176 | 1 | 19 (1 672; 17×200) | 4 | 250 URL, 17 decoded, 0 pending in view, 233 offscreen unfinished, 0 failed |
| | repeat, document reload | **1 703 ms** | 250 / 3 176 | 1 | 179 (1 414; 177×200) | 0 | same, 56 image requests still in flight |
| | repeat, in-app navigation | 63 ms | 250 / 3 176 | **0** | 0 | 0 | same |
| | complete set, document navigation | **2 979 ms** | **3 176 / 3 176** | 1 | 2 (2 078; 0 images) | 0 | 3 138 URL, 258 decoded, 0 pending in view, 2 880 offscreen unfinished, 38 no-url, 0 failed |
| | complete set, repeat reload | **2 405 ms** | **3 176 / 3 176** | 1 | 2 (1 494; 0 images) | 0 | as above |
| cold browser / warm app cache (291 stored) | first, document navigation | **1 876 ms** | 250 / 3 176 | 1 | 19 (1 528; 17×200) | **0** | 250 URL, 17 decoded, 0 failed |
| | repeat, document reload | **1 676 ms** | 250 / 3 176 | 1 | 235 (1 360; 233×200) | 0 | same |
| | repeat, in-app navigation | 172 ms | 250 / 3 176 | 0 | 0 | 0 | same |
| | complete set, document navigation | **2 388 ms** | **3 176 / 3 176** | 1 | 2 (1 497; 0 images) | 0 | 3 138 URL, 256 decoded, 38 no-url, 0 failed |
| | complete set, repeat reload | **3 694 ms** | **3 176 / 3 176** | 1 | 227 (1 482; 225×200) | 4 | as above, 2 598 image requests still in flight |
| warm browser / warm app cache | first, document navigation | **1 849 ms** | 250 / 3 176 | 1 | 2 (1 614; **0 images**) | 0 | 250 URL, 17 decoded, 0 failed |
| | repeat, document reload | **1 725 ms** | 250 / 3 176 | 1 | 2 (1 446; 0 images) | 0 | same |
| | repeat, in-app navigation | 84 ms | 250 / 3 176 | 0 | 0 | 0 | same |
| | complete set, document navigation | **2 341 ms** | **3 176 / 3 176** | 1 | 2 (1 441; 0 images) | 0 | 3 138 URL, 258 decoded, 38 no-url, 0 failed |
| | complete set, repeat reload | **3 571 ms** | **3 176 / 3 176** | 1 | 169 (1 566; 167×200) | 0 | as above, 2 429 in flight |

Per-phase maxima: **2 979 ms**, **3 694 ms**, **3 571 ms** — every reading inside §33's 7 000 ms, and
the maximum is a complete-requested-set opening, not a favourable warm one. Phase totals: browser 628 /
3 688 / 3 690 requests (607 / 3 667 / 3 669 images), **0 to ArenaNet, 0 off the page's origin, 0 images
off the icon route** in all three; backend 324 / 549 / 176 recorded requests (315 / 540 / 167 on the
icon route); stored entries grew 0 → 291 → 491 → 630 as real upstream misses were published. Nothing
was concealed to reach these numbers: no result was truncated (the complete-set openings assert
displayed rows **equal** the backend's `rowCount` or fail), no placeholder was forced, and a page was
never called complete with a visible image pending (asserted `0` in every phase).

### What the measurements do **not** establish (AC 6)

- **The in-application "repeat opening" is not a comparable reopening.** This client keeps the
  calculated rows, so renavigating re-renders them without asking the backend (0 calculations, 0
  requests, 63–172 ms). It is reported under its own label; the comparable repeat is the document
  reload, which does recalculate.
- **Offscreen icons keep loading after the page is complete.** Native lazy loading defers them, which is
  what §12.1 asks for, and the complete-set openings left 2 429–2 880 offscreen icons unfinished with up
  to 2 598 requests still in flight at the moment of completion. Those counts are reported rather than
  waited out, and no complete-page claim rests on them.
- **A CONNECT count is tunnels, not requests.** One tunnel carries many HTTP/2 fetches, so 4 tunnels
  published 15+ images. Zero is decisive only because the restart and upstream-down phases used freshly
  started backend processes with no tunnel to reuse; a positive count proves only "at least one fetch".
- **Revalidation was exercised through the browser's own validate-with-the-server mode**, not by waiting
  out the 24-hour freshness window. Inside that window Chrome either serves the stored copy or
  re-downloads, so neither a normal nor a hard reload produces a conditional subresource request; the
  stale-entry path shares this mechanism but was not observed.
- **No performance acceptance is claimed.** These are one machine, one revision, the default All scope
  and the Profit table; the **detail a selection loads is still untimed**, and §33's Product Owner
  confirmation gate is untouched. Reported for planner disposition: nothing here closes Phase 5, and
  this story is not a substitute for its bounded §34 health review.
- **Metadata coverage was not repaired** (38 rows and 89 occupied bank slots have none) — manufacturing
  coverage with a live synchronization run is what the story forbids, so those entries exercised the
  fallback honestly.

### One production change outside `frontend/`

`infra.icons.HttpIconImageFetcher`'s default client now builds with `.proxy(ProxySelector.getDefault())`
instead of the builder's no-proxy default. Without it the JVM's `https.proxyHost`/`https.proxyPort` are
ignored, so **no upstream image request could be counted or refused** and AC 4's upstream evidence would
have had to be inferred. With nothing configured the selector chooses a direct connection, so behaviour
is unchanged for every existing run, and egress becomes configuration rather than a code change (§15).
Covered by a new test in `HttpIconImageFetcherTest` that points the JVM proxy at the local fixture and
fetches an **unresolvable** host, so only the proxy can answer it.

### Three defects in the interrupted work, fixed

1. `account-browser-smoke.mjs`'s bank-reload step demanded the reload produce exactly one request, which
   item images now accompany — it failed on the first real run against live data.
2. The measurement harness the other scripts' comments referred to (`profit-page-perf.mjs`) did not
   exist; AC 5 had no evidence at all.
3. `icon-live-check.mjs`'s revalidation step used `page.reload()`, which does not revalidate a fresh
   subresource, so it asserted on an empty list and failed.

### Tests and checks run

- Frontend `npm test`: **17 files, 230 tests passed** (includes 9 new `ItemIcon.spec.ts` and 6 new
  `crafting/__tests__/itemIcons.spec.ts`, plus the rewritten Bank/Materials icon expectations).
- `npm run build` (type-check + production build): clean — 134.23 kB JS / 45.69 kB gzip, 16.88 kB CSS /
  3.62 kB gzip.
- Backend `./mvnw -o test -Dtest=infra.icons.*Test,application.icons.*Test,web.ItemIconApiControllerTest`:
  **Tests run: 84, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** (`HttpIconImageFetcherTest` 12,
  +1 new).
- `npm run smoke:icons` **10 steps**, `npm run smoke:profit` **30 steps**, `npm run smoke:account`
  **8 steps**, `icon-live-check` `pre-restart`/`post-restart`/`upstream-down` **5/4/4 steps**,
  `perf:profit-page` all three cache phases — every one passed, output quoted above.
- No broad backend or JavaFX run: no JavaFX file was touched and the one backend change is a single
  client-construction line whose class suite was run. GitHub Actions remains the full-regression gate.
- Per `tasks/lessons.md`: the interrupted attempt's two leftover backends and dev server were found
  listening and stopped before anything was measured; every port used was probed on **both** address
  families before use and confirmed released (`000`) on both afterwards, and the user's own backend on
  `8080` was verified still answering `200` at the end.

### Documentation

`docs/CURRENT_ARCHITECTURE.md` only: §2's frontend tree (the new `items/` directory and the five new
scripts), §5.11's inventory bullet, a new "One image component" subsection with the measured browser
behaviour and an explicit evidence-limits paragraph, the corrected "Not built yet" paragraph (item
images are no longer missing; the untimed selection detail and the absent favicon are), and §5.14's
evidence paragraph, which now points at §5.11 instead of claiming no browser renders an image, plus the
`ProxySelector` note. `TARGET_ARCHITECTURE.md` §12.1 needed no change — it already specifies all of
this as intent.

## Blockers

None.
