## Story ID

STORY-SYNC-004

## Title

Repair referenced-item metadata gaps for real item icons

## Status

TODO

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

Not started.

## Blockers

None.
