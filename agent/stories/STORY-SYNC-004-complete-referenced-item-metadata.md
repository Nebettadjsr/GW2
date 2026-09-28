## Story ID

STORY-SYNC-004

## Title

Repair referenced-item metadata gaps for real item icons

## Status

DONE

## Milestone

milestone-05

## Goal

Make existing account and crafting item views display actual GW2 icons wherever available by completing backend metadata coverage, including referenced items absent from the items table, while reusing the completed icon presentation and delivery infrastructure.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 5: Bank/Materials and crafting frontend migration, backend authority and coexistence.
- docs/TARGET_ARCHITECTURE.md section 12.1, Item and recipe icon delivery, including Complete metadata coverage (Request-009).
- docs/CURRENT_ARCHITECTURE.md section 5.14: existing explicit metadata refresh, enrichment and shared image delivery.
- docs/KNOWN_PROBLEMS.md CH-10: account-only metadata coverage gap.
- agent/stories/STORY-API-009-web-item-icon-metadata.md, Result: refresh FROM items cannot reach account-only items without a row.
- agent/stories/STORY-WEB-010-shared-item-icon-presentation.md, Result: incomplete live Bank metadata coverage.
- agent/product-owner-requests/Request-009-finish-icons.md.
- docs/TEST_STRATEGY.md, applicable persistence, external-service, frontend and CI layers; section 36.

## Context

API-009 and WEB-010 are completed reusable infrastructure. Their recorded evidence leaves occupied Bank entries without retained icon metadata, and API-009 identifies referenced account-only items absent from the metadata table as unreachable by its refresh. The existing explicit metadata-only refresh provides the repair boundary; page navigation and image GETs must not acquire GW2 JSON. No full account sync is needed to repair IDs already stored locally.

## Acceptance Criteria

1. Extend the existing backend metadata refresh to discover referenced IDs independently of the presence of an items row. Cover stored Bank, Materials and character items, recipe outputs and ingredients used by existing item views, including nontradeable items. Resolve missing canonical metadata through the existing GW2 adapter and persist it in the existing item metadata store, including icon source and name/rarity where applicable. Preserve valid existing metadata and unrelated account/domain data.
2. Existing stored account data is repairable through the documented standalone metadata-only invocation without a full live account synchronization, first-time setup or binary bulk download. Repeated repair is safe; changed icon sources continue to refresh. An unavailable or failed metadata response must not fabricate metadata, destroy valid retained data or make an unresolved ID permanently ineligible for a later explicit repair.
3. After repair, existing item-bearing APIs supply the established application-relative icon URLs and all existing ItemIcon consumers use them unchanged. Retain batch enrichment and actual item identity. No live metadata acquisition on navigation, page-data reads or image requests; no browser GW2 calls or upstream URL construction. Keep the existing fallback for unavailable/rejected metadata and image failures; keep empty Bank slots empty.
4. Reuse the existing image-delivery endpoint, source validation and persistent filesystem cache. No second cache or new frontend icon implementation. Preserve JavaFX compatibility and backend ownership; do not change crafting economics, account quantities, ordering or eligibility.
5. Keep ArenaNet image bytes out of Git, database dumps and distributable project artifacts. Verify runtime cache exclusion and demonstrate that an initially empty runtime cache and missing metadata can be populated through existing runtime paths without bundled images. Use isolated cache storage; do not delete the user's cache.
6. Execute the metadata-only repair against the existing real user data and record before/after coverage for occupied Bank, Materials and the existing crafting item views. Verify actual image delivery separately from fallbacks, classify remaining unavailable/rejected/failed cases and report any valid resolvable IDs still missing. Do not claim coverage solely from fixture results or nullable URLs. Preserve account contents; no full account sync solely for this check.
7. Update CURRENT_ARCHITECTURE section 5.14 with the implemented repair behavior and invocation. Assess only the metadata portion of CH-10 against evidence; do not mark its separate desktop display/stale-path issues resolved without evidence. Record evidence and remaining limits in this story's Result. Final integrated Phase 5 performance verification and health review remain required after outstanding frontend work.

## Required Tests

- Targeted metadata persistence tests against a disposable PostgreSQL schema: referenced ID with no items row, existing row without an icon source, nontradeable ID, all reference families, changed source, safe repeat and partial upstream failure followed by successful repair. Assert existing account quantities and unrelated metadata are preserved.
- Controlled GW2 metadata fixtures prove complete retained field mapping, missing/rejected response handling and that metadata repair downloads no image bytes.
- Targeted API/integration checks prove repaired IDs receive the established icon URL, page-data reads do not call GW2, and existing cache delivery serves repaired sources with persistent reuse. Reuse existing coverage where sufficient.
- Explicit real-database metadata-repair and browser smoke check for Bank, Materials and existing crafting item views: before/after coverage, actual decoded images, genuine fallback cases, empty slots, application-only browser requests, and repeated delivery with warm cache. Record the exact checks and results; these live checks are not supplied by the CI gate.
- Relevant frontend rendering/interaction checks only if affected. The GitHub CI gate owns complete suites; do not require a full local regression run.

## Constraints

- Implement only the missing metadata coverage and its verification; do not reopen API-009 or WEB-010 or modify the active WEB-012 story.
- Follow TARGET_ARCHITECTURE section 12.1; reuse its explicit refresh boundary and existing adapters/store/cache. No new browser sync control is required.
- No unrelated Bank/Materials redesign, desktop cleanup, new deployment mechanism or domain calculation changes.
- Do not bundle upstream images, silently suppress unresolved coverage, or infer full-page performance acceptance from icon checks.

## Dependencies

- STORY-API-009 (DONE).
- STORY-WEB-010 (DONE).

## Definition of Done

Acceptance criteria met; required targeted and explicit live checks recorded, relevant CI gate green, documentation updated at its owner, and remaining evidence limits stated. Actual icon coverage is demonstrated after metadata-only repair, including formerly absent metadata rows, without altering account contents. Phase closure is not part of this story.

## Result

DONE — **metadata only**: two production changes in one file (`src/main/java/sync/IconSync.java`), its
test class, and one live-check script. No frontend icon implementation, no second cache, no new route,
no DTO change, no domain or economics change, and no full account synchronization.

### What was wrong and what changed (AC 1)

`referencedOrUnknownItemIds` selected `FROM items`, so an id that account synchronization stored but
for which no metadata row had ever been created was **structurally unreachable** — the gap
`STORY-API-009`'s Result recorded. The selection is now a union of the reference families themselves
(`account_bank`, `account_materials`, `character_items`, `recipes.output_item_id`,
`recipe_ingredients`, each with its own NULL filter) plus `items WHERE icon_url IS NULL`, so discovery
no longer depends on a row existing. Nothing joins a tradeability source, so a referenced nontradeable
item is covered like any other.

The write became an upsert: a **missing row is created** with the canonical `name`, `type`, `rarity`,
`vendor_value`, `icon_url` and `fetched_at`; an **existing row keeps every value it holds** and has its
**missing** canonical fields filled from the same response. Each field is
`COALESCE(items.<col>, EXCLUDED.<col>)`, so the stored side always wins and a populated value is never
overwritten, while a row that exists but holds no `name`/`type`/`rarity`/`vendor_value` is completed
instead of being left with an icon and nothing else. `icon_url` coalesces the other way round
(`COALESCE(EXCLUDED.icon_url, items.icon_url)`) so a changed source is written while a response
reporting no icon never blanks a stored one, and the statement's `WHERE` fires only when the row
actually gains something — a differing icon source, or one of the four canonical fields being NULL
where upstream has a value. So an unchanged run costs no row updates and `items.icon_path` — the
desktop column — is still never touched. Upstream is the same public `/v2/items` adapter every other sync uses,
behind a package-private `ItemMetadataSource` seam so a controlled fixture can stand in for it.

### Failure handling (AC 2)

A batch whose metadata request fails is **reported and skipped, not fatal**: no metadata is fabricated
for its ids, nothing retained is destroyed, and because the selection is recomputed from the references
on every run those ids are selected again by the next explicit repair. `InterruptedException` re-sets
the interrupt flag and propagates rather than being swallowed. The run returns a `MetadataRepair`
report — selected, rows written, rows created, ids upstream did not return, items with no icon
reference, items whose reference the canonical policy rejects, failed batches and the ids they covered
— so remaining fallbacks are classified, not merely counted. `--dry-run` additionally reports how many
selected ids have no `items` row, through the same selection and no GW2 call. Invocation is unchanged
(JavaFX first-time setup, or the standalone `java -cp … sync.IconSync` documented in §5.14): no browser
control, no first-time setup and no binary download is needed.

### Targeted tests

`sync.IconSyncMetadataTest` against a disposable, uniquely-named Postgres schema, upstream supplied by
a controlled fixture (`FixtureMetadataSource`) that answers only from a fixed catalogue and omits ids
it does not know, exactly as the real endpoint does. **15 tests, 0 failures** —
`./mvnw -o test -Dtest=IconSyncMetadataTest -DfailIfNoTests=false` →
`Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`. It covers: every reference family reachable with
**no** `items` row; the same families when they already have metadata; a referenced nontradeable item;
an item with no metadata and no reference; a bank slot whose item has no row obtaining the full
canonical row *and* a non-null `ItemIconUrls.iconUrlFor`; an existing row filled while its
name/type/rarity/vendor_value/`icon_path` are asserted unchanged;
**an existing referenced row whose `name`, `type`, `rarity` and `vendor_value` are all NULL having all
four filled from upstream with its stored `icon_url` and `icon_path` untouched, beside a second row
whose populated name survives while only its NULL fields are filled, and a repeat run then writing 0
rows** (`anExistingRowMissingCanonicalFieldsHasThemFilledWithoutOverwritingWhatItHolds`; against the
previous icon-only `ON CONFLICT` it fails with
`both half-populated rows were completed ==> expected: <2> but was: <0>`, verified by temporarily
restoring that statement); a changed source written and the **second identical run writing 0 rows**; missing/rejected/no-icon upstream responses classified with
no row fabricated for the unresolved id, a rejected source retained verbatim yet yielding a null
presentation URL, a stored source not blanked, and the unresolved id still selected afterwards; a
250-id run where the second batch fails, the first 200 are still created, nothing is fabricated for the
failed batch and the next run repairs exactly those 50; account quantities (250 / 1 337), the empty
bank slot and an unreferenced item's metadata all intact after a repair; and no image directory
produced. The changed-source test's two seed rows were made canonically complete so its "only the item
whose source changed is written" assertion still means that under the widened write. The whole `sync`
package was run too: `./mvnw -o test -Dtest='sync.*Test'` →
`Tests run: 36, Failures: 0, Errors: 0, Skipped: 0` (`AccountSyncTest`, `CharacterSyncTest`,
`DesktopIconAdoptionTest`, `IconSyncMetadataTest`, `RecipeSyncTest`, `TpSyncTest`).

### Real-database repair, before and after (AC 6)

Measured with a throwaway read-only JDBC reporter under `target/` (not committed), against the real
user database. `--dry-run` first reported **14 302** selected ids, **337 of them with no `items` row**.

| | before | after |
|---|---|---|
| occupied bank slots with an `items` row | 75 / 163 | **163 / 163** |
| occupied bank slots with a non-null `icon_url` | 75 / 163 | **163 / 163** |
| material stacks with a non-null `icon_url` | 678 / 680 | **680 / 680** |
| distinct recipe ingredients with a non-null `icon_url` | 2 870 / 2 873 | **2 873 / 2 873** |
| distinct recipe outputs with a non-null `icon_url` | 12 975 / 13 065 | **13 025 / 13 065** |
| distinct character-inventory items with no `items` row | 216 / 324 | **0 / 324** |
| `items` rows / rows with `icon_url` | 13 981 / 13 928 | **14 278 / 14 278** |

The run itself reported
`MetadataRepair[selected=14302, rowsWritten=350, rowsCreated=297, notReturnedUpstream=40,
withoutIconSource=0, rejectedIconSource=0, batchesFailed=0, idsLeftUnrequested=0]` — 297 rows created
plus the 53 existing rows that held no source, and the numbers reconcile (337 = 297 + 40; 13 981 − 13 928
= 53). **Repeat safety observed on real data:** an immediate second run reported
`rowsWritten=0, rowsCreated=0`, and `--dry-run` then reported the same 14 302 selected with 40 without a
row — the unresolved ids stayed eligible rather than becoming permanently ineligible.

**After the upsert learned to complete existing rows**, the same documented invocation was run once
more against the same real database. A read-only reporter (throwaway, under `target/`, not committed)
found **0** `items` rows with a NULL `name`, `type`, `rarity`, `vendor_value` or `icon_url` before the
run, and **0** occupied bank slots, material stacks, character items, recipe outputs or recipe
ingredients whose item row is missing a canonical field. `--dry-run` reported 14 302 selected with 40
without a row; the run reported
`MetadataRepair[selected=14302, rowsWritten=0, rowsCreated=0, notReturnedUpstream=40,
withoutIconSource=0, rejectedIconSource=0, batchesFailed=0, idsLeftUnrequested=0]`, and the reporter
returned the identical figures afterwards — the widened write causes **no churn** on already-complete
rows, and 14 278 `items` rows were unchanged. Completing a half-populated row is therefore evidenced by
the targeted test, not by live data: this database holds no such row today, because both writers
(`ItemSync` and this repair) create rows with the full canonical set.

**The 40 that remain are unresolvable, not pending.** All 40 are recipe outputs; the GW2 items endpoint
answers `{"text":"no such id"}` / HTTP 404 for them (verified directly for a sample and for a
three-id batch, against a control request that returned Glob of Ectoplasm normally). They are not valid
resolvable ids, and no valid resolvable id is still missing in any of the checked families. No account
contents were written — the repair touches `items` only — and no account synchronization was run.

### Live browser evidence (AC 3, 5, 6)

`frontend/scripts/icon-live-check.mjs` covered Bank only. It was extended to open **Bank, Materials and
the crafting Profit table**, each reported separately, with the origin/route assertions now spanning all
three. A first `post-restart` run failed because the upstream-tunnel count had moved after the added
navigations — a real regression in that phase's meaning, fixed by counting tunnels where it was counted
before (right after the first opening) and running the multi-view loop only in the cold phase, whose
warm-storage claims it would otherwise have changed.

Runtime: this session's own processes — backend `web.Gw2ApiApplication` on `127.0.0.1:8093` against the
real database with `ICON_CACHE_DIR` pointed at an **initially empty** isolated directory
(`%LOCALAPPDATA%\Temp\gw2-sync004\icons`), egress through `scripts/upstream-proxy.mjs` on 8293, page
served by `vite --host 127.0.0.1 --strictPort` → the script's recording proxy on 8193 → the backend.
The user's own cache (`~/.nebet-gw2-tool/icons`, 7.4 MB) was never configured, read or written, and a
pre-existing backend on 8099 was identified and left alone.

- `icon-live-check.mjs pre-restart` **PASSED, 7 steps** (cold isolated cache): Bank 163 images with a
  URL, Materials 505, crafting Profit 250 — **0 without metadata and 0 failed on all three**, 0 refused
  by the backend, every delivered image `max-age=86400, public` with a strong ETag and a verified
  `image/png|jpeg`. 336 image requests across the three views, **0 to ArenaNet and 0 off this origin**,
  all on `/api/items/{id}/icon/{64-hex}.{png|jpg}`. Reopening Bank in a second tab re-requested **0 of
  the 64** already delivered; **12 of 12** forced revalidations reached the backend as `If-None-Match` →
  **304** with Cache-Control and ETag retained, 0 re-downloaded, 0 upstream tunnels.
- `icon-live-check.mjs post-restart` **PASSED, 4 steps**: the backend was stopped (port probed until it
  refused connections), restarted over the same isolated storage as a new process, and served 53 real
  images with **0 upstream CONNECT tunnels** — repeated delivery from a warm application cache.
- A second `pre-restart` run on the restructured script **PASSED, 7 steps** and needed **0 additional
  upstream tunnels (8 → 8)**: everything all three views ask for is now in the isolated store.
- `npm run smoke:account` against the real backend **PASSED, 8 steps**: 180 bank slots, **17 empty**, in
  the supplied order; 9 categories / 505 stacks; **505 images all on the icon route, all `no-referrer`,
  0 entries on the fallback** (`STORY-WEB-010`'s run of this same step recorded 89 occupied bank slots
  without metadata); 358 browser requests, all to the page's own origin; no synchronization call, no
  page error.
- `web.ItemIconApiRealDbIT` (run with the isolated `ICON_CACHE_DIR`) **Tests run: 3, Failures: 0** and
  printed `180 bank slots, 163 with an accepted retained icon source` — `STORY-API-009`'s run of the
  same line printed 74.

Nothing here is inferred from a rendered picture: browser requests come from the driver, backend
requests from the recording proxy's log, upstream access from the CONNECT lines of the proxy the
backend was configured to use.

### Image bytes stay out of the repository (AC 5)

The cache root is `<user.home>/.nebet-gw2-tool/icons` (or an explicit `ICON_CACHE_DIR`), never inside
the working tree, so exclusion is structural rather than a `.gitignore` rule: the tree contains no
`assets-v1` directory and no file named with a 64-hex key, `git status` shows only this story's source
files, and every tracked image is project-owned (logos, favicons, documentation screenshots).
PostgreSQL holds a `TEXT` URL and no bytes, so a dump carries no ArenaNet binary. The isolated cache
started **empty** and ended with **312 files / 2.4 MB** built entirely at runtime through the existing
image route from the GW2 render service — a fresh clone can rebuild both metadata and cache with
nothing bundled. The user's cache was not deleted or modified.

### Limits

- The **40 unresolvable recipe-output ids** are classified as "upstream does not know them"; no further
  attempt was made to explain why those recipes exist locally.
- **Filling a stored row's missing canonical fields was exercised on fixtures only**: the real database
  contains no half-populated `items` row, so the live re-run could only show the absence of churn
  (`rowsWritten=0`). A row whose `vendor_value` is NULL is filled with upstream's value, or with `0`
  when upstream omits it — the same value a freshly created row gets, since both writers bind `0` for
  an absent `vendor_value`. A stored blank string counts as populated, not missing; only NULL is
  treated as missing.
- A batch containing *only* ids upstream rejects would be answered HTTP 404 and counted as a failed
  batch rather than 200 ids not returned. Every real batch here contained resolvable ids, so this
  distinction was exercised by fixture only.
- No `upstream-down` phase was run, and no real upstream outage, redirect, oversized image or disk-full
  condition was observed — those remain `STORY-WEB-010`'s and the controlled fixtures' evidence.
- **No §33 performance claim is made and no Phase 5 closure is implied.** Full-page timings, the
  integrated performance verification and the health review remain outstanding (`STORY-PERF-002`).
- Java suites outside the `sync` package, the frontend unit tests and the other smoke scripts were not
  run locally; GitHub Actions is the full-regression gate (`TEST_STRATEGY.md` §20, §36).
- `CH-10`/`KP-22` is marked **partially resolved, metadata half only**. Its JavaFX null-`icon_path`
  empty-slot rendering and its stale-`icon_path` repair gap were not touched and no evidence was
  gathered for them.

### Documentation

`docs/CURRENT_ARCHITECTURE.md` §5.14 (owner of current icon structure and behavior): heading, the new
"Reaching an id that has no `items` row at all" paragraph with the write table — whose "row exists"
entry now states the missing-field completion and the `COALESCE` direction — and the failure/report
behavior, and the evidence paragraph with the measured before/after figures, the no-churn re-run and
the extended live check. `docs/KNOWN_PROBLEMS.md` KP-22 (owner of defects/risks): partial-resolution status naming
exactly what remains open. No other document owns information this story changed — `DOMAIN_SPEC.md`,
`TARGET_ARCHITECTURE.md`, `TEST_STRATEGY.md` and `docs/crafting/` are untouched because no domain rule,
intended architecture, testing method or user-visible crafting rule changed.

## Follow-up Findings

F001: `Request-009`'s Planner Resolution states that `docs/TARGET_ARCHITECTURE.md` §12.1 was extended
with "complete referenced-item metadata repair and runtime-only upstream image storage requirements",
and this story's Authoritative Source Documents cite "§12.1 … including Complete metadata coverage
(Request-009)". Neither string exists in the file: §12.1 still holds only the original
"Item and recipe icon delivery" bullets, with no metadata-coverage or image-storage requirement. The
story was implemented from its own acceptance criteria and Request-009 itself; nothing was invented,
but §12.1 does not currently record the requirement the planner believes it records.

F002: `frontend/scripts/account-browser-smoke.mjs`'s "bank reload repeats only that read" step compares
the calls made during the reload window against exactly `['/api/account/bank']` plus icon-route
requests. The application lands on its default destination (`#/crafting`) before the script clicks
`nav-bank`, and that screen's `/api/crafting/profit` request can still be in flight, landing inside the
window — the first run of this session failed on exactly that, with
`rendered: ["/api/crafting/profit","/api/account/bank"]`, and an immediate rerun passed. The step is
timing-dependent rather than wrong; it would be stable if it either awaited the landing screen's
calculation before navigating or filtered that one path the way it already filters image requests.

## Blockers

None.
