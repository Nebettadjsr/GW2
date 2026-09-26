# Product Owner Request

## Status

RESOLVED

## Title

Revise web item icon architecture to use persistent backend caching

## Requested Change

Revisit and revise the architecture decision recorded by AR-004 / ADR-004 for
web item and recipe icon delivery.

The current decision relies on ArenaNet image URLs plus native browser caching.

For this application, core views such as Bank, Materials and Crafting Profit can
display hundreds of different items at once. The Product Owner does not want
normal page rendering to depend on potentially hundreds of direct external image
requests whenever the browser cache is cold.

Adopt an icon delivery architecture with two cache levels and ArenaNet as the
upstream source.

Expected flow:

1. The frontend requests an item icon through the application/backend.
2. The browser's normal HTTP cache is the first cache level.
3. If the browser needs the resource, it requests it from the backend.
4. The backend checks a persistent local icon cache.
5. If the icon already exists locally, the backend serves it without contacting
   ArenaNet.
6. If the icon is missing locally, the backend retrieves it using the canonical
   ArenaNet icon URL already associated with the item metadata.
7. The backend stores the successfully retrieved image in its persistent icon
   cache.
8. The backend returns the image to the frontend.
9. Subsequent requests can therefore be served from either the browser cache or
   backend cache without contacting ArenaNet again.

Conceptually:

Browser cache
↓ cache miss
Backend persistent icon cache
↓ cache miss
ArenaNet image service
↓
persist locally
↓
serve through backend
↓
browser caches response

## Product Intent

Icons will be used broadly throughout the application, including:

- Bank;
- Materials;
- Crafting Profit;
- recipe/details views;
- future item-oriented views.

These screens may contain hundreds of items.

The application should therefore avoid making large numbers of external image
requests part of normal repeat usage.

An icon that has already been retrieved by the application should normally not
need to be retrieved from ArenaNet again.

The desired result is:

- first use of an unknown icon may require an ArenaNet request;
- subsequent application use should use the persistent backend copy;
- repeated browser rendering should additionally benefit from normal browser
  caching;
- all frontend views should use the same icon delivery mechanism.

## Architecture Review

Route this request to the Architect.

The Architect should revisit AR-004 / ADR-004 and update or supersede the
previous decision as appropriate.

The architecture decision should define:

- the canonical source of icon metadata;
- the backend icon URL/API exposed to the frontend;
- the persistent cache location and ownership;
- whether filesystem storage or another simple persistent mechanism is most
  appropriate;
- cache key / filename strategy;
- HTTP cache headers for browser caching;
- behavior on cache miss;
- behavior when ArenaNet is unavailable;
- fallback behavior for missing/broken icons;
- container/deployment persistence requirements;
- compatibility with the existing JavaFX icon cache / `IconSync`;
- whether existing cached icon files can be reused instead of maintaining two
  independent copies;
- cleanup/invalidation behavior, if any is actually required.

Prefer the simplest implementation that provides persistent reuse.

Do not introduce a database-backed binary image store, distributed cache,
external cache service or other infrastructure unless there is a demonstrated
reason for it.

A simple persistent filesystem cache is acceptable if the Architect determines
it fits the runtime/deployment model.

## Loading Behavior

Frontend image components should still use normal browser caching.

Where appropriate, images outside the visible viewport should use lazy loading
so pages containing hundreds of items do not request every icon immediately.

A failed or unavailable icon must result in a stable placeholder rather than a
broken-image UI.

The application must not require an ArenaNet image request before rendering an
item whose icon has already been cached locally.

## Metadata

Do not introduce additional GW2 API metadata requests merely to discover an
icon while rendering a page.

The canonical ArenaNet icon URL should come from item metadata already obtained
and retained by the application where available.

The expected miss path is:

known item metadata
→ known ArenaNet icon URL
→ download image once
→ persist image

not:

render item
→ query GW2 API for item
→ discover icon URL
→ download icon

## Scope

This request changes the previously selected web icon-delivery architecture.

It does not require pre-downloading every Guild Wars 2 icon.

Icons should normally be cached on demand as they are actually required by the
application.

Existing caches may be reused or migrated if technically appropriate.

## Why / Product Intent

The application routinely works with large collections of Guild Wars 2 items.

A cold browser cache should not imply that every future page visit depends on
hundreds of third-party image requests.

Persisting icons already used by the application provides a predictable,
application-owned resource after the first retrieval while retaining browser
caching as an additional fast cache layer.

ArenaNet remains the authoritative upstream source for icons, but should be the
fallback for cache misses rather than the normal delivery path for every browser.

## Additional Context

This explicitly requests reconsideration of the previous AR-004 / ADR-004
decision.

If the Architect finds a materially simpler implementation that provides the
same behavior — persistent application-owned caching, browser caching and
ArenaNet only on cache miss — that implementation may be selected.

## Planner Resolution

2026-09-26: RESOLVED as planning coverage. Reused the AR-005 contract in
docs/TARGET_ARCHITECTURE.md section 12.1 and existing
agent/stories/STORY-API-009-web-item-icon-metadata.md and
agent/stories/STORY-WEB-010-shared-item-icon-presentation.md for retained metadata,
shared persistent storage, backend delivery, browser caching, fallbacks, desktop
compatibility and integration/performance verification. The supplied
agent/stories/BACKLOG.md retains API-009 as active and WEB-010 blocked on it.
No duplicate architecture request or story was created. Implementation remains
unfinished; no performance or milestone completion is claimed.