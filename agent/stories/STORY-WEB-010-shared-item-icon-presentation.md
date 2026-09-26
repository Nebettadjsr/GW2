## Story ID

STORY-WEB-010

## Title

Render backend-supplied icons through one reusable frontend component

## Status

BLOCKED

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

Not started.

## Blockers

STORY-API-009 must complete before selection. STORY-WEB-007 is DONE. AR-005 is
resolved and both icon stories are rescoped; no architecture blocker remains.
