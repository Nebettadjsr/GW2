# ADR-004 — Web item icons from ArenaNet static URLs

## Status

SUPERSEDED — 2026-09-25 by [ADR-005](ADR-005-persistent-web-icon-cache.md)
in response to Request-005. The text below records the former decision and
its historical rationale; TARGET_ARCHITECTURE section 12.1 owns the replacement
contract. Originally accepted 2026-09-25, Class C.

## Context

Request-004 item 7 asks for one icon strategy across item-oriented web screens.
WEB-003 and API-007 report filesystem `iconPath` values and no rendered images.
CURRENT_ARCHITECTURE sections 5.4, 5.11 and 5.12 document the desktop cache and
current HTTP/browser gap. This is a presentation delivery decision, not a change
to crafting, inventory or recipe semantics.

Bounded source inspection of `sync/IconSync.java` and `sync/IconSyncGateway.java`
confirmed two separable operations: `syncItemIconUrls()` retrieves `/v2/items`
metadata for existing item rows with null `icon_url`; `syncItemIconsToDisk()`
downloads binaries by item ID and writes `icon_path`. The existing URL operation
does not create missing item rows or refresh non-null stale URLs. Therefore reuse
is useful, but that method alone does not prove complete metadata coverage or a
refresh policy. No database coverage audit or runtime timing was performed.

The official GW2 wiki API documentation describes item `icon` URLs and a render
service addressed by signature and file ID. Recipe metadata identifies an output
item; use that item's icon rather than inventing recipe-specific assets. These
facts were checked through indexed official documentation on 2026-09-25; direct
page fetches returned 403 and live API fetches were unavailable in the web tool.
No live CDN header, availability or latency guarantee was established.

## Constraints

TARGET_ARCHITECTURE sections 4.1, 5, 11 and 12 retain backend-owned domain logic,
metadata/API access and secrets. Section 33 retains the seven-second real-data
full-page requirement. ROADMAP Phase 5 requires JavaFX coexistence. The project
is a single-maintainer migration with no documented offline-icon requirement,
dedicated asset service requirement or measured image-delivery bottleneck.

## Decision

Select direct public ArenaNet render-service image delivery using backend-supplied
URLs and native browser caching. TARGET_ARCHITECTURE section 12.1 owns the
normative metadata, rendering, validation, migration and verification contract.
This ADR records why that contract was selected, not a second contract owner.

## Rationale

Architectural judgment: this uses the capabilities the project needs with the
fewest moving parts. The backend already stores URL metadata; browsers already
load and cache images. Metadata can accompany existing table/tree responses,
avoiding per-item metadata round trips and keeping presentation enrichment out
of economic calculations. Reusing stable URLs permits browser reuse across
rows and views subject to upstream headers and browser cache state. It does not
guarantee one network request per unique icon or compliance with the timing gate.

Cold loads still incur image transfers, connection and decode costs. Direct
delivery avoids routing those bytes through the application backend, but is not
claimed faster than a warm local cache. It depends on the browser's connection
to ArenaNet and exposes ordinary request metadata to that origin; referrer
suppression and public asset-only URLs avoid forwarding application/account data.
No documented requirement requires same-origin-only or offline delivery.

Native images/HTTP caching add no image-library lifecycle, dependency release
cadence, service deployment or cache maintenance schedule. A single component
and nullable transport field suit bounded coding-agent changes. The URL contract
also makes a later delivery change local to metadata mapping and shared rendering,
rather than every feature view. No additional recurring paid service is selected.

## Alternatives Considered

| Option | Benefit here | Deciding cost or limitation |
|---|---|---|
| Direct ArenaNet images + native browser cache (selected) | Reuses stored metadata and browser facilities; no server binary storage | External image availability and browser cache behavior remain outside our control |
| Backend-managed same-origin cache/proxy (runner-up) | Centralizes fetches and cache headers; can keep previously fetched icons available when upstream is down and hide client IP from the CDN | Needs safe source validation, misses/timeouts, concurrent-download control, invalidation, disk limits, HTTP asset serving and persistent storage; no measured need justifies these responsibilities yet |
| Serve the existing desktop disk cache | Reuses already downloaded files | Filesystem paths are not a browser contract; still needs safe serving and volumes, has incomplete coverage, and item-ID filenames do not by themselves handle changed source URLs |
| Bulk mirror icons into frontend assets or database binaries | Locally controlled image delivery | Duplicates upstream assets, expands builds/storage and introduces refresh/distribution work for data already available by URL |
| Custom browser persistent cache | Application-controlled retention/offline behavior | Adds service-worker/IndexedDB invalidation and failure states without an offline requirement; browser HTTP cache is sufficient as the initial design |

## Consequences

Planner follow-up is required for persisted metadata coverage/refresh, application
batch enrichment, HTTP contract migration and shared frontend rendering across
the requested screens. Reuse the existing item sync and URL storage where useful;
do not mistake the null-only URL backfill for a complete refresh mechanism.
Preserve desktop disk caching while removing it as a web prerequisite.

Missing item names in Bank/Materials are reported by API-007 and remain an adjacent
presentation-data gap; this decision does not authorize a browser GW2 lookup to
fill them. The existing item-ID label remains a valid fallback. No source, tests,
story, backlog, roadmap or current-state documentation is changed by this decision.
Phase 5 sequencing and exit criteria are unchanged; actual performance and
integration evidence remain implementation follow-up, not architecture results.

## References

- `docs/TARGET_ARCHITECTURE.md` sections 4.1, 5, 11, 12.1 and 33.
- `docs/CURRENT_ARCHITECTURE.md` sections 5.4, 5.11 and 5.12.
- `docs/ROADMAP.md` Phase 5; `agent/product-owner-requests/Request-004-UX-update.md` item 7.
- Results of `STORY-WEB-003-bank-materials-views.md` and `STORY-API-007-bank-materials-read-endpoints.md`.
- `src/main/java/sync/IconSync.java` and `IconSyncGateway.java` (read-only inspection).
- [GW2 item metadata](https://wiki.guildwars2.com/wiki/API:2/items), [recipe metadata](https://wiki.guildwars2.com/wiki/API:2/recipes), and [render service](https://wiki.guildwars2.com/wiki/API:Render_service).
- [MDN HTTP caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Caching): reuse, revalidation and cache ownership; no ArenaNet-specific TTL inferred.
