## Status

RESOLVED

## Architecture Question

How should AR-004 / ADR-004 be revised to satisfy Request-005's persistent
application-owned icon caching and browser caching, with ArenaNet used only on
cache miss? Define the replacement technical contract in TARGET_ARCHITECTURE
section 12.1 and update or supersede the prior ADR as appropriate.

## Context and Constraints

The Product Owner explicitly rejects normal rendering's dependence on direct
external requests for hundreds of items with a cold browser cache. The revised
product requirement is recorded in TARGET_ARCHITECTURE section 12.1; no new
implementation is claimed. The existing API-009 / WEB-010 contracts implement
the old decision and are blocked pending this review and planner rescoping.

Settle canonical metadata ownership; application icon URL/API; cache location,
ownership, keys/filenames and persistent storage; browser cache headers;
on-demand misses and successful persistence; upstream failure and placeholders;
deployment persistence requirements; compatibility with ICON_CACHE_DIR/IconSync
and reuse of existing files; and any justified cleanup/invalidation. Preserve
backend-only metadata acquisition and no metadata lookup during page rendering.
Prefer simple filesystem reuse if suitable; no database binary store, distributed
cache or external service without demonstrated need. Do not require bulk download.
Define persistence constraints without commissioning out-of-scope deployment work.
Do not change crafting calculations or the active resolution-endpoint story.

## Authoritative References

- agent/product-owner-requests/Request-005-changeArchitect-desicion.md
- docs/TARGET_ARCHITECTURE.md section 12.1: revised product requirement and previous contract.
- agent/architect-requests/AR-004-web-item-icon-strategy.md: prior resolved decision supplied to planner.
- docs/architecture/decisions/ADR-004-web-item-icon-delivery.md: prior rationale identified by section 12.1; not read by planner.
- agent/stories/STORY-API-009-web-item-icon-metadata.md
- agent/stories/STORY-WEB-010-shared-item-icon-presentation.md
- Supplied docs/ROADMAP.md Phase 5: item views, backend authority, JavaFX coexistence and full-page performance.

## Blocked Work

STORY-API-009 and STORY-WEB-010 must be rescoped by a subsequent planner pass
against the architect's replacement contract before becoming selectable. Full
Request-005 resolution and integrated icon-performance assessment remain pending.

## Blocking User Decision

None.

## Architect Decision

Class C — architecturally significant technical decision (persistent caching
and delivery). Requires Product Owner Decision: NO.

Select a shared source-versioned filesystem cache under ICON_CACHE_DIR, served
through the backend application image endpoint with normal browser HTTP caching.
TARGET_ARCHITECTURE section 12.1 owns the complete technical contract; ADR-005
records the rationale and supersedes ADR-004.

Request-005 explicitly authorizes persistent application ownership. Engineering
judgment: filesystem reuse and one desktop/web acquisition adapter meet that
requirement with fewer moving parts than a proxy service or binary database.
Canonical metadata stays in items.icon_url, versioned keys separate changed
URLs, and valid local hits require no ArenaNet request.

Runner-up: serve legacy item-ID files with a source-version sidecar. It reduces
file movement but needs coordinated image/sidecar replacement and browser
versioning, and cannot establish provenance absent from existing files.
Select source-keyed files with conservative legacy adoption instead.
Direct-CDN delivery fails the revised requirement; distributed storage solves
no demonstrated project problem. No measurements or implementation completion
are claimed.

## Resolution

Updated TARGET_ARCHITECTURE section 12.1 with the replacement metadata,
application URL, shared filesystem, browser caching, miss/failure, JavaFX
migration, retention, deployment persistence and verification contract.
Created ADR-005 and marked ADR-004 superseded, retaining its historical
rationale. The earlier architect request was not read or edited.

Planner follow-up: rescope blocked STORY-API-009 and STORY-WEB-010 before
selection, replacing direct-CDN/no-cache criteria and including shared cache,
legacy adoption and failure evidence. Carry persistence constraints into existing
deployment scope without commissioning new deployment work here. Retain roadmap
sequencing and full-page performance and health-review gates; reconcile planner
artifacts as needed. Request-005 closure and integrated icon-performance
assessment remain pending. No stories, backlog, roadmap, source, tests or active
resolution-endpoint contract were modified.

Relevant limitations: null-only URL backfill does not prove metadata coverage
or refresh; legacy filenames do not prove source versions. The replacement
contract addresses these, with real-data verification left to implementation.
No additional architecture review or User Decision was required.
