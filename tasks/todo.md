# STORY-API-009 — web item icon metadata and persistent image delivery

Plan (each item states how it is verified).

- [ ] 1. `infra/icons/`: canonical source policy + key (`IconSourcePolicy`, `IconSource`),
      protective bounds (`IconCacheBounds`), filesystem cache adapter (`FilesystemIconStore`,
      `IconStore`, `StoredIcon`, `IconStorageException`), upstream adapter
      (`IconImageFetcher`, `HttpIconImageFetcher`, `IconFetchResult`, `IconImageBytes`) and the
      shared miss coordinator (`IconAcquisition`, `IconAcquisitionResult`).
      *Verify:* unit tests per class — rejection/canonicalization/key, publication atomicity,
      confinement, coalescing, suppression, bounds.
- [ ] 2. `application/icons/`: `ItemIconUrls` (route format/parse over the policy) and
      `IconDelivery` + `IconDeliveryResult` (the delivery boundary: route validation → disk →
      retained metadata → acquisition). *Verify:* unit tests with fake store/fetcher/metadata.
- [ ] 3. `repo`: `icon_url` added to the existing batch reads (`ItemRepository.ItemInfo`,
      `BankRepository.BankSlotRow`, `MaterialStorageRepository.MaterialStorageRow`) and a
      single-item retained-source read (`ItemIconMetadataRepository`) used only on a cache miss.
      *Verify:* repository tests + the real-database ITs.
- [ ] 4. `web`: nullable `iconUrl` on `CraftingRowDto`, `MissingItemDto`, `ResolutionNodeDto`,
      bank slots and material stacks (replacing `iconPath`), plus the thin
      `ItemIconApiController`. *Verify:* controller tests for headers/304/400/404/503 and the
      updated contract tests.
- [ ] 5. `sync`: referenced-item metadata refresh in `IconSync.syncItemIconUrls()` and keyed
      publication/legacy handling in `syncItemIconsToDisk` via `DesktopIconAdoption`.
      *Verify:* `DesktopIconAdoptionTest` with fake store/fetcher (no DB, no network).
- [ ] 6. Frontend: `iconPath` → `iconUrl` in types, `InventoryItem`, both screens, fixtures and
      the smoke script. *Verify:* `npm run test:unit`, `type-check`, `build`.
- [ ] 7. Docs: `CURRENT_ARCHITECTURE.md` (boundaries, metadata invocation, chosen bounds,
      storage configuration, deployment requirement, evidence limits), story Result, BACKLOG
      entry, `CLAUDE_RESULT.md`. *Verify:* re-read against acceptance criterion 7.

# STORY-WEB-014 — Profit live integration

- [x] Trace all three reported symptoms through the running browser and API; compare source contracts.
- [x] Add a strict live smoke check and a delayed-response control regression using existing fixtures.
- [x] Verify current backend/browser together: strict live smoke passed after the user's restart.
- [x] Attempt directly related regression suites; filesystem limitations and qualified results recorded in the story.
- [x] Record verification evidence, runtime remedy and remaining limits in the story Result.
