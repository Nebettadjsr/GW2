# ADR-005 — Persistent application-owned item icon cache

## Status

ACCEPTED — 2026-09-25. Class C, architecturally significant technical decision.
Supersedes [ADR-004](ADR-004-web-item-icon-delivery.md).
Requires Product Owner Decision: NO.

## Context

Request-005 changes the earlier requirement: a cold browser must reuse images
the application already fetched without contacting ArenaNet. The Product Owner
authorizes persistent filesystem storage and on-demand misses, rejecting
unnecessary binary database storage, distributed caches and services. This
authorizes a technical decision; no measured bottleneck is asserted.

Observed through bounded source inspection: IconSync stores upstream sources
in items.icon_url, backfills only null URLs on existing rows, writes
items/{itemId}.png under its supplied root, and records icon_path. It skips
nonempty files and records no source-version provenance. AppConfig already
exposes ICON_CACHE_DIR with a user-home default. The root and desktop path
capability are reusable; filenames alone cannot establish version correctness.
No real cache contents, metadata coverage or runtime timings were audited.

## Constraints

TARGET_ARCHITECTURE section 12.1 owns the replacement technical contract.
Backend metadata acquisition and domain isolation remain binding. ROADMAP
Phase 5 requires JavaFX coexistence and the full-page performance gate.
API-009 and WEB-010 retain blocked old contracts pending planner rescoping.
This decision authorizes no source, story or deployment implementation.

## Decision

Select a shared source-versioned filesystem cache behind an application image
endpoint, with normal browser HTTP caching. Reuse the desktop configuration
root and converge future desktop/web binary acquisition on one adapter.
TARGET_ARCHITECTURE section 12.1 is the sole normative owner of route, key,
storage, migration, HTTP, failure and verification details.

## Rationale

Architectural judgment: filesystem reuse best fits this single-maintainer
migration. It meets the explicit persistence requirement without another
runtime, service, dependency ecosystem or binary database workload. Existing
HTTP boundaries keep table/tree presentation and image delivery outside domain
calculations. A shared adapter and nullable URL field are bounded responsibilities
for coding agents and future item views.

Source-versioned files separate changed metadata from stale item-ID browser URLs,
share bytes between items and preserve images referenced by old pages.
They cost a migration boundary and retain old versions. Conservative legacy
adoption avoids inventing historical provenance. Compatibility requires
converging future writes, not simply exposing the old directory over HTTP.

Application-controlled freshness and local ETag validation provide browser reuse.
Finite freshness was selected over immutable caching so repaired local content
can become visible on browser revalidation. Disk retention is independent of
browser freshness. These are engineering choices, not ArenaNet guarantees.

Safe fetching, bounded misses, atomic publication and persistent storage now
belong to the backend, as the Product Owner requested. Coalescing and resource
bounds prevent unbounded work from hundreds of items. A fully cold application
cache still needs upstream downloads. No speed improvement or seven-second
compliance is claimed.

The design uses existing filesystem/HTTP facilities without a new library or
support lifecycle. Deployment requires a persistent mount within existing scope,
not another service. A later storage-adapter change remains possible behind the
application URL field. Assets are reconstructible, but ordinary restart and
redeployment must preserve them.

## Alternatives Considered

| Option | Benefit here | Deciding limitation |
|---|---|---|
| Shared source-versioned filesystem cache (selected) | Persistent reuse, shared acquisition, no new service | Backend owns safe misses/storage; legacy adoption needs provenance |
| Legacy item-ID files plus source-version sidecar (runner-up) | Less file movement; existing desktop layout | Coordinated image/sidecar updates, stale-version detection and browser versioning are still needed; existing provenance is absent and identical images remain duplicated |
| Direct ArenaNet URLs and browser cache | Least backend implementation | Fails Request-005 for a cold browser requesting an already-used application image |
| Reverse-proxy cache | HTTP serving and caching | Adds runtime/configuration ownership; still needs canonical metadata checks, storage and desktop reuse |
| Database binaries, distributed cache or object service | Centralized or multi-instance storage | No documented scale/availability need; adds operations explicitly disfavored by Request-005 |
| Bulk mirror or custom browser store | Prefetch or browser-managed retention | Exceeds on-demand scope; browser storage alone cannot provide application-owned persistence |

## Consequences

The direct-browser exception is removed. Implementation must prove cached
operation during upstream outage and after restart, and persistence before
returning a successful miss. Missing images retain usable placeholders.

Legacy files without trustworthy provenance may require one on-demand upstream
retrieval before adoption; they are neither deleted nor universally trusted.
Future writes converge on the shared store. Retention consumes disk, with no
invented capacity estimate or scheduled eviction requirement.

Planner follow-up: rescope the blocked icon stories, carry the persistent-mount
constraint into existing deployment scope, retain Phase 5 gates, and assess
integrated performance before closing Request-005. No implementation, test
result, new story or Product Owner request closure is claimed.

## References and evidence

- [TARGET_ARCHITECTURE section 12.1](../../TARGET_ARCHITECTURE.md#121-web-presentation-assets-and-product-requirements): normative contract.
- [Request-005](../../../agent/product-owner-requests/Request-005-changeArchitect-desicion.md): revised product intent.
- [CURRENT_ARCHITECTURE](../../CURRENT_ARCHITECTURE.md), sections 5.4, 5.11 and 5.12; [ROADMAP](../../ROADMAP.md), Phase 5.
- Read-only inspection: src/main/java/sync/IconSync.java, IconSyncGateway.java and src/main/java/repo/AppConfig.java.
- [GW2 render service](https://wiki.guildwars2.com/wiki/API%3ARender_service): signature/file-ID paths and PNG/JPG formats, checked via indexed official wiki on 2026-09-25; direct fetch unavailable. No live upstream headers or availability verified.
- [RFC 9111](https://www.rfc-editor.org/rfc/rfc9111.html): HTTP freshness, validation and no-store; [RFC 8246](https://www.rfc-editor.org/rfc/rfc8246.html): immutable semantics. Checked 2026-09-25. TTL and disk retention are project choices.
