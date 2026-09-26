## Status

RESOLVED

## Architecture Question

Which reusable item/recipe icon delivery strategy should the web application use,
and what metadata/delivery boundaries must Phase 5 stories implement?

## Context and Constraints

Request-004 asks for technical evaluation and a decision under Architect authority
where possible. Compare available GW2 icon metadata, direct ArenaNet/CDN image URLs,
backend-managed/local caching, browser caching and reuse of existing icon sync/cache
functionality. Assess network/performance behavior, unnecessary duplicate storage,
unavailable-icon fallbacks and deployment/container implications. Cover Crafting
Profit, Bank, Materials and recipe/detail views through one reusable approach.

The supplied WEB-003/API-007 results report filesystem iconPath values that browsers
cannot use, and no images rendered. CURRENT_ARCHITECTURE documents IconSync and
ICON_CACHE_DIR; their existence does not establish a usable browser contract.
Backend-only GW2 API access and secrets remain binding. Distinguish direct static
CDN image retrieval from API calls explicitly if recommending it. Preserve JavaFX
coexistence and avoid unnecessary complexity. Do not choose a strategy merely
because one existing desktop cache is available. No source inspection was performed
in this planning pass.

## Authoritative References

- docs/TARGET_ARCHITECTURE.md sections 4.1, 11, 12 and 12.1.
- docs/CURRENT_ARCHITECTURE.md sections 5.4, 5.11 and 5.12.
- agent/stories/STORY-WEB-003-bank-materials-views.md, Result.
- agent/stories/STORY-API-007-bank-materials-read-endpoints.md, Result.
- agent/product-owner-requests/Request-004-UX-update.md, item 7.
- Supplied docs/ROADMAP.md Phase 5 frontend migration scope.

## Blocked Work

Planning reusable browser icon metadata/delivery and icon rendering stories for
Phase 5. Result filters, selection and the decided resolution-detail contract do
not depend on this decision. Favicon support is a separate static frontend asset.

## Blocking User Decision

None.

## Architect Decision

Class C — architecturally significant technical decision, resolved within
Architect authority. Select backend-supplied ArenaNet static image URLs with
native browser HTTP caching and one reusable frontend icon component. The
normative contract is TARGET_ARCHITECTURE section 12.1; rationale and alternatives
are recorded in ADR-004-web-item-icon-delivery.md.

Backend synchronization owns GW2 item metadata; application read services supply
nullable validated `iconUrl` values for items, recipe outputs and detail nodes.
Direct public render-service image retrieval is permitted, while browser GW2 JSON
API calls and exposure of secrets remain forbidden. Reuse persisted `icon_url`
metadata, preserving JavaFX's separate local cache during coexistence.

The runner-up is a backend-managed same-origin image cache. It offers controlled
headers and cached availability but adds storage, invalidation, safe asset
serving and cache-miss management without a documented offline requirement or
measured bottleneck. Architectural judgment favors native browser facilities for
this project's size. Existing desktop files alone do not justify that complexity.
Cold-load performance and upstream caching guarantees have not been measured.

## Resolution

Updated TARGET_ARCHITECTURE section 12.1 with metadata/delivery boundaries,
URL validation, shared rendering and fallback behavior, container/JavaFX
coexistence guidance and required verification. Added ADR-004 for evidence,
technical alternatives and the decision rationale. No User Decision is needed.

The planner owns implementation scoping for metadata coverage/refresh, batch
application enrichment, affected HTTP contracts, the shared component and its
adoption across Crafting Profit, Bank, Materials and recipe/detail views. Verify
JavaFX compatibility, no filesystem-path leakage, controlled failure cases and
real browser delivery, then reverify the existing full-page performance gate
with recorded cold/warm cache conditions. No gate is claimed satisfied here.

Bounded source inspection confirmed that existing URL sync only backfills null
URLs on existing item rows: it does not establish missing-item coverage or refresh
stale non-null URLs. Account/nontradeable and recipe/ingredient metadata coverage
therefore needs implementation attention. API-007's missing item names remain
an adjacent gap; retain item-ID fallback, and let the planner handle any name
enrichment. No separate architecture investigation is started. Existing Phase 5
scope and exit criteria remain unchanged; no planner-owned files were modified.
