## Story ID

STORY-API-009

## Title

Expose item icon metadata and persistent backend image delivery

## Status

DONE

## Milestone

milestone-05

## Goal

Implement TARGET_ARCHITECTURE section 12.1's AR-005 metadata and persistent image-delivery contract for existing Phase 5 item views, sharing acquisition/storage with JavaFX.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5: Profit, Discovery, bank/materials views, resolution detail and JavaFX coexistence.
- docs/TARGET_ARCHITECTURE.md section 12.1: AR-005 metadata, persistent delivery, coexistence and verification contract, superseding AR-004.
- docs/TARGET_ARCHITECTURE.md section 13: semantic detail identity.
- agent/stories/STORY-API-007-bank-materials-read-endpoints.md and STORY-WEB-003-bank-materials-views.md, Results: existing filesystem iconPath limitation.
- agent/stories/STORY-API-008-crafting-resolution-endpoints.md: both detail routes.

## Context

AR-005 is RESOLVED and supersedes AR-004. Section 12.1 owns the replacement architecture: application-relative image URLs, browser HTTP caching and a persistent source-versioned filesystem cache shared with desktop acquisition. Existing bank/material contracts expose desktop paths and the browser currently renders placeholders. This story implements the backend prerequisite; WEB-010 owns shared browser rendering and integrated cache/performance evidence.

## Acceptance Criteria

1. Reuse persisted items.icon_url and backend metadata synchronization. Cover referenced account items, recipe outputs and ingredients, including nontradeable items; explicit metadata refresh must update changed URLs, not only null entries. Keep metadata acquisition usable without binary download or desktop setup; document invocation without a new browser sync control. Missing metadata remains tolerable.
2. Batch-enrich item-bearing application read results outside domain calculations for Bank, Materials, both crafting table flows, material lists and both resolution-detail flows. Use actual item identity, including each semantic node's actual sourcing; recipes use their output item's icon. No per-row database lookup, page-triggered sync or live GW2 request is allowed.
3. Expose nullable application-relative iconUrl in affected HTTP item representations, using section 12.1's exact canonical source validation, source key and route policy. Invalid/missing sources produce null without affecting calculations. Remove browser iconPath and migrate transport consumers/checks together; retain JavaFX local paths. No URL/HTTP types enter the domain and no upstream URL or redirect reaches the browser.
4. Implement section 12.1's thin image endpoint over an application delivery boundary and filesystem/upstream adapters. Valid disk hits need neither upstream access nor live metadata. Misses match retained metadata before fetching; route validation and path/symlink confinement precede access. Enforce documented finite network, byte, pixel, concurrency and waiting bounds; coalesce same-key misses. Validate bytes and atomically publish before success, protecting existing committed entries. Implement the specified 400/404/503 failures, bounded temporary failure suppression, no-store/Retry-After and successful cache headers, strong ETag and conditional 304 behavior. No generic file-serving route or directory listing.
5. Preserve response order, empty bank slots, quantities, economics, states and current calculation/detail isolation. No secrets or filesystem paths reach these HTTP item representations.
6. Route future IconSync binary writes and web misses through the same cache adapter/key/publication policy. Preserve explicit desktop sync and icon_path compatibility, updating paths only after publication and a matching retained source. Apply section 12.1's provenance/byte-equality requirement before legacy adoption; otherwise leave legacy desktop files usable and fill keyed entries on demand. Do not require mass migration or maintain independent download stores. Verify persistent reuse across restart and failure recovery without expiry of valid entries.
7. Update CURRENT_ARCHITECTURE with implemented boundaries, metadata invocation, chosen protective bounds, storage configuration and evidence limits. Document section 12.1's stable persistent-root deployment requirement without commissioning deployment work. Record missing real-data evidence and retain WEB-010's browser integration and full-page performance gate.

## Required Tests

- Source canonicalization/key and route-policy checks, including rejected sources, malformed routes, obsolete/mismatched keys, redirects and path/symlink escape; no upstream access on rejected misses.
- Disposable-cache/controlled-upstream checks for hits with upstream and metadata unavailable, persistence before success, restart reuse, concurrent misses/publication, interrupted writes, changed sources, invalid/oversized images, finite capacity/deadlines, disk failure and bounded recovery. Verify exact success/error headers and local conditional 304. Never persist a failure or placeholder.
- Application/HTTP tests for nullable URLs, actual output/node identity, batch enrichment, repeated items, empty bank slots, no per-item loading or external calls during reads, no filesystem paths or secrets, and unchanged economic/state fields.
- Focused synchronization tests covering referenced nontradeable and account/output/ingredient metadata, changed URLs and independence from disk download; shared desktop/web publication, proven versus unproven legacy reuse, and path-update failure without invalidating committed assets.
- Read-only real-database HTTP check against persisted metadata; do not silently run live synchronization to manufacture evidence.
- Affected backend/frontend contract checks, frontend type check/build, and targeted JavaFX read/icon compatibility checks.

## Constraints

- Follow section 12.1 without changing its architecture. No arbitrary proxy, direct-browser upstream fallback, parallel metadata/binary store, browser GW2 JSON requests, distributed cache or new deployment story. Backend persistence is required.
- Do not turn icon availability into crafting eligibility or result availability.
- No broad synchronization redesign, new long-running-operation mechanism or domain refactor.

## Dependencies

- STORY-API-008 completed so both detail contracts can be enriched.
- STORY-WEB-008 completed to keep the shared economic row projection integrated.
- STORY-API-007, STORY-WEB-003 and STORY-APP-012 (DONE).

## Definition of Done

Existing Phase 5 contracts provide application URL/null metadata without desktop paths; persistent delivery and shared JavaFX acquisition satisfy section 12.1 with targeted evidence. Metadata acquisition remains independent of downloads. WEB-010 retains integrated browser/performance verification.

## Result

DONE — **backend only.** `TARGET_ARCHITECTURE.md` §12.1 (AR-005) is implemented: item-bearing HTTP
reads carry an application-relative nullable `iconUrl`, one thin backend route serves those images
from a persistent source-keyed filesystem cache, and the JavaFX desktop icon download now writes
through that same cache. `STORY-WEB-010` retains all browser rendering and the integrated
cache/performance gate — **no `<img>` is rendered anywhere yet and no §33 timing is claimed.**

An interrupted attempt was **resumed, not restarted.** Commit `b9e6a10` already carried the
production classes, most of their fixture suites, both real-database ITs and
`CURRENT_ARCHITECTURE.md` §5.14; this session re-verified all of it against the live database,
closed the one missing required-test area (the metadata refresh's own coverage and changed-URL
behaviour), corrected the architecture sections the earlier attempt left contradicting §5.14, and
wrote the records. Nothing already correct was re-implemented.

### What the implementation is (AC 1–6)

**One canonical source policy** (`infra.icons.IconSourcePolicy`, with `IconSource` as its accepted
result) is the only place §12.1's acceptance rules live — parsed absolute HTTPS on exactly
`render.guildwars2.com` with `/file/{hex-signature}/{positive-id}.{png|jpg}`; userinfo, nondefault
ports, queries, fragments, percent-encoding (rejected outright rather than decoded, so an encoded
separator cannot describe a path the policy would refuse), dot segments and every other
host/scheme/path are rejected. Canonicalization lowercases scheme/host, drops an explicit 443 and
retains the accepted path spelling; the key is lowercase 64-hex SHA-256 of the canonical URL's UTF-8
bytes. Rejection is not an error — it yields `null`, and no calculation sees it.

**The emitted URL and the route check are the same code.** `application.icons.ItemIconUrls` both
formats `/api/items/{itemId}/icon/{sourceKey}.{ext}` and parses it back, so what a read emits and
what the endpoint accepts cannot drift. Dev routing needed no change: Vite already proxies `/api`.

**Enrichment is batch, outside the domain, with no per-row work.** The retained source rides the
*existing* batch reads — `repo.ItemRepository.ItemInfo.iconUrl` for crafting tables, material lists
and both resolution-detail flows, and an `icon_url` column added to `repo.BankRepository.BankSlotRow`
/ `repo.MaterialStorageRepository.MaterialStorageRow` — so a read costs no extra query and performs
no per-item lookup, synchronization, image download or GW2 request. Identity is actual:
`web.CraftingRowMapper` uses the recipe's **output item** for a row and each missing material's own
item; `web.CraftingResolutionMapper` uses **each node's own item**, never the requested recipe's
output and never the node's sourcing. `iconPath` is gone from `BankSlotDto`/`MaterialStackDto` and
from the frontend transport types, fixtures and smoke stubs, migrated together; JavaFX's
`BankView`/`MaterialsView` keep consuming `items.icon_path` unchanged.

**Delivery decisions live in one application boundary** (`application.icons.IconDelivery`), whose step
order is the contract: validate the route's own values → read the cache → on a miss load the item's
retained source and require the key *and* extension it derives to match → only then acquire. A valid
disk hit therefore needs neither the upstream host nor live metadata; an unknown item, absent or
rejected metadata and an obsolete or foreign key are 404s with **no upstream access**; a cached old
key keeps serving its original image and can never be handed new-version bytes. `web.ItemIconApi
Controller` is thin — it opens no file, computes no path, reads no metadata and decides no caching
rule; there is no listing, no generic file path and no caller-supplied path, host or URL on the route.

**Bounds are finite and documented in one place** (`infra.icons.IconCacheBounds`; limits, not measured
latencies): connect 5 s, whole response 10 s, body ≤ 2 MiB, decoded pixels ≤ 4 000 000, ≤ 4 concurrent
downloads, ≤ 32 requests handling a miss at once, ≤ 5 s waiting for a coordination or download slot,
30 s in-process failure suppression per key, `Retry-After: 30`. `IconAcquisition` coalesces same-key
misses per process and **rechecks disk after taking the key's slot**, which is what makes the second
caller of a pair do no upstream request and what keeps suppression from ever hiding a valid entry.

**Publication protects committed entries.** `FilesystemIconStore` writes a unique `*.tmp` beside the
destination and renames it **without** `REPLACE_EXISTING` — a plain `Files.move`, deliberately *not*
`ATOMIC_MOVE`, which on Windows replaces the destination, the one thing a committed entry must be
protected from. Readers never see a partial file; empty, partial, corrupt, symlinked and temporary
files all read as a miss; resolved paths are confined to the root and the key/extension shape is
checked before the filesystem is touched. A persistence failure is a 503, never a successful uncached
image, and no failure or placeholder is ever written at an image key.

**Metadata acquisition is explicit and download-free.** `sync.IconSync.syncItemIconUrls()` now selects
everything an item-bearing view can reference — bank slots, material storage, character inventories,
recipe outputs and recipe ingredients, **nontradeable items included** since it joins no tradeability
source — plus anything still missing metadata, and writes a URL that **changed**, not only a null one.
It downloads no image and needs no local directory, and `items.icon_path` is untouched by it.
Invocation is documented in `CURRENT_ARCHITECTURE.md` §5.14 and needs no browser control: JavaFX
"First-time DB Setup" as before, or standalone `java -cp … sync.IconSync` (`--dry-run` reports
coverage and calls nothing).

**Desktop and web share one store.** `syncItemIconsToDisk` no longer downloads into
`items/{itemId}.png`; it goes through `sync.DesktopIconAdoption`, which reuses whatever either side
published, otherwise acquires and publishes through the same protocol, and only **then** hands back
the file the caller records in `items.icon_path` — so a failed path update cannot invalidate a
committed asset and the next run reuses it. Legacy files are left in place and stay usable; their
bytes become a keyed entry only when byte-identical to a successful on-demand fetch of the canonical
source (`ADOPTED_LEGACY`), because `IconSync` recorded no provenance and used `.png` names even for
`.jpg` sources. No mass migration, no startup migration, no second download store.

### What this session changed

- `sync.IconSync`: the metadata selection and the changed-URL write became two connection-taking
  package-private methods (`referencedOrUnknownItemIds(Connection)`, `applyIconUrlUpdates(Connection,
  Map)`), so both are testable without the GW2 API. Behaviour is unchanged — the same SQL, the same
  "only a differing value is written" rule and the same per-batch transaction, now with the
  `AutoCommit` state restored rather than left off.
- New `sync.IconSyncMetadataTest` (8 tests, disposable Postgres schema).
- `docs/CURRENT_ARCHITECTURE.md`: §5.14's evidence paragraph updated with this session's figures and
  the new test, plus four sections the earlier attempt left stale — §5.5's Profit response content,
  §5.11's Bank/Materials field lists and its frontend "no icon is fetched" bullet (both were still
  describing `iconPath` as a browser field), §5.13's node description, and the "not built yet"
  paragraph.

### Tests run

- Backend `./mvnw -o test`: **Tests run: 493, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
  (+8, all in the new `sync.IconSyncMetadataTest`). Icon-specific fixture coverage totals **82**:
  `IconSourcePolicyTest` 11, `ItemIconUrlsTest` 7, `IconDeliveryTest` 13, `FilesystemIconStoreTest` 15,
  `HttpIconImageFetcherTest` 11, `IconAcquisitionTest` 8, `IconImageBytesTest` 7,
  `ItemIconApiControllerTest` 11 — plus `DesktopIconAdoptionTest` 8 and `IconSyncMetadataTest` 8.
  Between them they pin: rejected sources and derived keys; a changed source deriving a different key;
  malformed routes, obsolete/mismatched keys and a mismatched extension, each proven to reach no
  upstream; an unfollowed redirect and no API key/cookie/authorization on the upstream request;
  symlink and non-entry paths; a second publisher keeping the committed entry and concurrent
  publishers converging on one; a second store over the same root finding what the first published
  (restart reuse) with nothing expiring on its own; concurrent misses producing a single download;
  interrupted/temporary files never being entries; truncated, format-mismatched, oversized and
  over-pixel images rejected and never persisted; storage failure as 503 rather than a streamed image;
  suppression expiring and never hiding a disk hit; every status with its exact headers, `no-store` on
  all three failures, `Retry-After: 30`, the strong ETag and local `If-None-Match` → 304 (matching,
  multi-valued, wildcard and stale); no path, upstream URL or lower-layer message in any failure body;
  no listing under the route.
- New `sync.IconSyncMetadataTest` proves the two things the old null-only backfill did not establish:
  a bank slot, material stack, character-inventory item, recipe output and recipe ingredient are all
  selected **even though they already have metadata**, while an unreferenced item that has metadata is
  not; a referenced item with no Trading Post row is still selected; an item with no metadata at all is
  still covered; and a **changed** URL is written while an unchanged one costs no row update. It calls
  no GW2 API, downloads nothing and asserts `items.icon_path` is left exactly as found.
- Real database, read-only, explicit run: `web.ItemIconApiRealDbIT` **3/3** and
  `web.AccountReadApiRealDbEquivalenceIT` **2/2**, 0 failures. **180 bank slots (17 empty, 74 with an
  accepted retained source)**; **9 material categories / 504 stacks, 504 with one**; three sampled real
  URLs delivered 200 with `image/png`, `nosniff`, `max-age=86400` and a strong ETag, each revalidating
  to a 304 that kept both. A foreign key for a real item was a 404 with `no-store` and no upstream URL
  in the body; three malformed routes were 400.
- **Observed persistent reuse across restart, on real data.** `~/.nebet-gw2-tool/icons/assets-v1`
  holds exactly the three entries published by an earlier session's backend process at 09:18; today's
  run in a new process served all three as 200 and added no file and no new timestamp. The root
  contains no `items/` directory at all.
- `sync.IconSync --dry-run` on the real database: **13 965 items** the metadata refresh would cover.
  Confirms the documented standalone invocation works; **no live synchronization was executed.**
- Frontend: `npm test` **207 passed** (15 files), `npm run type-check` clean, `npm run build` clean
  (132.88 kB JS / 45.42 gzip, 16.42 kB CSS / 3.51 gzip). `npm run smoke:layout` **PASSED, 11 steps** in
  real Chrome against the script's own stub origin on `127.0.0.1:5210` — Bank and Materials render the
  migrated contract, 20 pairs at WCAG AA (lowest 4.92:1), 37 API requests all reads, no page error.
  Per `tasks/lessons.md` the port was probed free on **both** address families before use and
  **confirmed released on both** afterwards; no dev server or backend was started for it.
- JavaFX compatibility: `BankViewIT` and `MaterialsViewIT` pass unchanged, still feeding the views a
  local `iconPath`.

### Limitations and missing evidence (explicit)

- **No browser image rendering and no §33 timing.** Nothing in `frontend/` requests an image;
  `InventoryItem.vue` still shows its neutral placeholder and uses `iconUrl` only to say whether the
  backend offered one. Every §12.1 browser requirement — the shared image component, reserved
  dimensions, lazy loading, `referrerpolicy`, the bundled placeholder — and the whole cold/warm
  browser × cold/warm application-cache timing table, the restart run and the warm-cache
  upstream-unavailable run remain `STORY-WEB-010`'s. **No Phase 5 exit criterion, performance
  acceptance or milestone transition is claimed.**
- **Metadata coverage on the live database is partial and was not repaired** (74 of 180 bank slots
  carry an accepted source). The refresh was only dry-run, deliberately: manufacturing coverage with a
  live synchronization run is what the story forbids. Missing metadata stays `iconUrl: null`.
- **Legacy adoption has fixture evidence only.** This machine's cache holds no `items/` directory, so
  the proven-versus-unproven legacy paths were never exercised against real legacy files.
- **Upstream behaviour is controlled-fixture evidence.** The real-database IT accepts 200/404/503 by
  design and happened to observe three disk hits; no real upstream outage, redirect, oversized image or
  disk-full condition was reproduced against the live host.
- The image route is unauthenticated like every existing one (§20 TBD).
- Deployment is **recorded, not commissioned**: §12.1's persistent-volume-at-`ICON_CACHE_DIR`
  requirement is written into §5.14, and no container, service or deployment work was started.

`docs/CURRENT_ARCHITECTURE.md` §5.5, §5.11, §5.13, §5.14 and the "not built yet" paragraph.
`docs/KNOWN_PROBLEMS.md` CH-10 deliberately **not** changed: every clause of it is still true — the
JavaFX views still treat a null `iconPath` as the empty-slot branch, `syncItemIconsToDisk` still
selects only rows whose `icon_path` is NULL/empty and still does not check that a recorded file
exists, and account sync still creates no `items` row for an account-only item, so the refresh
(`FROM items`) cannot reach one.

## Blockers

None.
