# Product Owner Request

## Status

RESOLVED

## Title

Complete real item-icon coverage across Bank, Materials and existing item views

## Requested Change

The intended product behavior is that items shown in the application display their actual Guild Wars 2 item icon wherever ArenaNet provides one.

STORY-WEB-010 implemented the shared frontend icon component and the backend image-delivery/cache path, but it did not complete the original product intent: occupied Bank and Materials entries may still show the neutral fallback because their stored item metadata does not contain the information required to resolve the real icon.

The existing ItemIcon component and backend icon-delivery architecture must be reused.

For every displayed item with a valid GW2 item ID:

- Resolve the corresponding GW2 item metadata required for icon presentation when it is not already available locally.
- Persist the resolved item metadata, including the ArenaNet icon reference/source information, through the existing backend-owned persistent metadata storage.
- Use the existing backend image-fetching and icon-cache mechanism to retrieve and serve the actual image.
- Supply the frontend only with the existing application-relative icon URL expected by ItemIcon.
- Apply this consistently to Bank, Materials and all existing item views that already use ItemIcon.
- Existing stored account data must not remain permanently without real icons merely because the required item metadata was absent when that account data was originally synchronized.

The neutral fallback remains required, but only for genuinely unavailable, rejected or failed images. It must not be the normal presentation for a valid GW2 item merely because its metadata has not yet been resolved.

## Why / Product Intent

The purpose of adding item icons was to make Bank, Materials and other item-heavy views visually recognizable and easier to navigate.

The desired product behavior is:

`occupied item slot -> actual GW2 item icon`

not merely:

`occupied item slot -> render icon if iconUrl happens to already exist, otherwise placeholder`

STORY-WEB-010 successfully implemented the presentation and delivery infrastructure, but its own completion evidence still recorded occupied Bank entries without icon metadata. The remaining work is therefore metadata/icon coverage, not another frontend icon implementation.

## Constraints

- Reuse the existing ItemIcon component and backend image-delivery/cache infrastructure introduced by STORY-WEB-010.
- The browser must not query ArenaNet directly or construct ArenaNet image URLs itself.
- GW2 item metadata resolution remains backend-owned.
- Persist item metadata such as item ID, name/rarity where applicable, and the ArenaNet icon reference in the application's existing persistent metadata storage.
- The actual ArenaNet/GW2 image files must **not** be stored in the Git repository.
- The actual image bytes should continue to use the existing runtime filesystem icon cache rather than being committed as project assets.
- Runtime-generated icon-cache contents must remain outside version control / ignored by Git.
- PostgreSQL dumps or other distributable project artifacts must not contain bundled ArenaNet image binaries.
- A fresh clone/install of the project must be able to rebuild required metadata and icon-cache contents at runtime from the GW2 API/render service rather than relying on copyrighted image files shipped with the repository.
- Do not introduce a second icon-cache implementation.
- Do not require a full live account synchronization solely to repair missing icon presentation unless the existing architecture makes that explicitly necessary and the planner documents why.
- Preserve the existing neutral fallback for items whose icon genuinely cannot be resolved or loaded.
- Do not expand this request into unrelated Bank/Materials redesign.

## Additional Context

STORY-WEB-010 is technically complete for the scope that was written: it created the reusable frontend component, backend image delivery, persistent filesystem caching, failure handling and browser verification.

Its result explicitly recorded incomplete metadata coverage, including occupied Bank slots without icon metadata. Therefore the infrastructure for displaying and caching real icons already exists; the missing product behavior is ensuring that valid GW2 items actually obtain the metadata needed to use that infrastructure.

The intended storage separation is:

`PostgreSQL -> persistent item metadata / ArenaNet icon reference`

`runtime filesystem cache -> downloaded image bytes`

`browser cache -> delivered application icon responses`

`Git repository -> source code and project-owned assets only; no ArenaNet/GW2 image library`

The planner should treat STORY-WEB-010 as completed reusable infrastructure rather than reopen or duplicate it, and create only the remaining work required to provide real icon coverage.

## Planner Resolution

2026-09-27: RESOLVED as planning coverage, not implementation completion. Extended docs/TARGET_ARCHITECTURE.md section 12.1 with complete referenced-item metadata repair and runtime-only upstream image storage requirements. Created agent/stories/STORY-SYNC-004-complete-referenced-item-metadata.md and queued it in agent/stories/BACKLOG.md for metadata-only repair of existing account data, including absent items rows, persistent metadata acquisition and real-data/browser verification. Reused completed agent/stories/STORY-API-009-web-item-icon-metadata.md and agent/stories/STORY-WEB-010-shared-item-icon-presentation.md for existing delivery/cache and ItemIcon infrastructure. No duplicate icon implementation or full account synchronization is required. Existing Phase 5 performance and health-review exit requirements remain unchanged; repair must precede final integrated evidence and closure.