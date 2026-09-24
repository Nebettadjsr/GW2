## Story ID

STORY-APP-009

## Title

Extract bank and material-storage read use cases

## Status

DONE

## Milestone

milestone-03

## Goal

Give BankView and MaterialsView application-service read boundaries, moving their database access into persistence adapters using the established shared connection configuration.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3 application-layer objective and high-level story to update JavaFX views/controllers to call application services only.
- docs/TARGET_ARCHITECTURE.md section 8: GetBankContents and GetMaterialStorage use cases; section 25: application tests with fake adapters and PostgreSQL persistence integration tests.
- docs/CURRENT_ARCHITECTURE.md sections 4, 6 and 9 item 6: both views bypass the application/repository boundary with inline DriverManager connections.
- docs/KNOWN_PROBLEMS.md section 2.2: route these views through shared AppConfig/Db or their Phase 3 application-service replacement when next touched.
- agent/stories/STORY-APP-007-extract-initial-setup-use-case.md, Result: these view bypasses remain outside its completed scope.

## Context

The target explicitly names bank and material-storage reads as application use cases. Current architecture documents both views performing database access themselves with hardcoded connection settings. Query details and displayed behavior must be established during implementation; this story does not infer new inventory semantics.

## Acceptance Criteria

1. Establish and record each view's current read queries, returned data, ordering/filtering, empty-data behavior and error presentation before changing the boundary.
2. Provide named application services for bank contents and material storage with replaceable persistence collaborators and no JavaFX dependency. Views call these services for data and retain presentation responsibilities.
3. Move the existing SQL and connection lifecycle into persistence adapters, reusing compatible existing repositories where available. Both flows use the shared repo.Db configuration path; remove the views' hardcoded connection constants and direct JDBC access.
4. Preserve existing read semantics and displayed values, ordering/filtering, empty states and error handling, apart from intentionally adopting the application's configured database connection. Do not introduce synchronization or database writes into these read use cases.
5. Update docs/CURRENT_ARCHITECTURE.md for the changed boundaries and docs/KNOWN_PROBLEMS.md section 2.2 only to the extent verified resolved. Record verification evidence and limitations without declaring Phase 3 complete.

## Required Tests

- Fake-repository application tests verify delegation, returned results, empty results and propagated failures for both use cases without live database access.
- Focused PostgreSQL integration checks on a disposable schema verify the relocated query/mapping semantics against representative existing data shapes and confirm use of shared connection configuration without relying on view-local credentials.
- Focused real-window JavaFX checks with controlled service results verify both views render expected data/empty states and preserve their established error presentation and relevant controls.

## Constraints

- Bounded Phase 3 extraction of these two read flows; no schema migration, new inventory rules, write operations or UI redesign.
- Keep SQL and connection handling in persistence, orchestration in application services, and rendering in views.
- Do not broaden this into configuration cleanup elsewhere or an automatic full regression campaign.

## Dependencies

None.

## Definition of Done

Both views obtain their data through application services and configured persistence adapters; targeted application, persistence and view verification supports behavior preservation, with authoritative documentation and Result updated.

## Result

DONE. Both views now read through a named application service over a configured persistence adapter;
no view in the codebase opens a database connection or runs SQL any more.

### AC 1 — Pre-change behavior, established by reading the pre-extraction views

Recorded before the boundary moved, and used as the preservation baseline for every check below.

| | `BankView` | `MaterialsView` |
|---|---|---|
| Connection | inline `DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)` on literal `jdbc:postgresql://localhost:5432/GWDatabase` / `postgres` / `0` | identical literals, plus a German TODO acknowledging the duplication |
| Query | `SELECT b.slot, b.item_id, b.count, i.icon_path, i.rarity FROM account_bank b LEFT JOIN items i ON i.item_id = b.item_id ORDER BY b.slot` | `SELECT am.category, am.item_id, am.count, i.icon_path, i.rarity FROM account_materials am LEFT JOIN items i ON i.item_id = am.item_id WHERE am.count IS NOT NULL AND am.count > 0 ORDER BY am.category, am.item_id` |
| Returned data | per-slot record; `item_id`/`count` nullable (empty slot), `icon_path`/`rarity` nullable (LEFT JOIN miss) | per-stack `MatEntry(iconPath, count, rarity)`; `category`/`item_id` used for grouping/ordering only, never displayed |
| Filtering | none — empty slots are returned and rendered as blank tiles | `count IS NOT NULL AND count > 0` |
| Ordering/grouping | `slot` ascending, laid into a 10-column grid in blocks of 3 rows + a spacer row | grouped into a fixed 10-entry category-id→name map in map order; unknown ids fall back to `"Category " + id`; empty groups removed afterwards |
| Empty data | empty list → empty grid, no error | empty result → no blocks, empty page |
| Error presentation | `catch (Exception)` → `printStackTrace()` + red `DB error: <message>` label in a `StackPane` | `catch (Exception)` → `printStackTrace()` only, returns an empty map — **the user is never shown a load error**; the page is indistinguishable from empty storage |

### AC 2 — Application services

`application.BankContentsService.getBankContents()` and
`application.MaterialStorageService.getMaterialStorage()`. Neither imports JavaFX. Each takes its
persistence collaborator through a constructor seam (no-arg constructor builds the real one), which
is what the fake-repository tests substitute. The category-id→name map and the grouping/empty-group
removal moved verbatim from `MaterialsView.materialCategoryNames()`/`loadMaterialsGrouped()` into
`MaterialStorageService`, so grouping is now an application concern and the view only renders the
returned `MaterialCategory(name, materials)` list. All presentation — grid/block layout, tiles,
rarity borders, icons, labels, the error label — stayed in the views.

### AC 3 — Persistence adapters

`repo.BankRepository` and `repo.MaterialStorageRepository` hold the two SQL statements and the
connection lifecycle, opening through the shared `repo.Db.open()` → `repo.AppConfig`/`EnvConfig`
path. Each also exposes a `(Connection)` overload so an integration test can point it at a
disposable schema, mirroring `repo.InventoryRepository`'s existing pattern. `InventoryRepository`
was examined for reuse and deliberately **not** reused: it aggregates owned quantity per item across
bank/materials/character inventories and classifies by binding, so it can neither report empty bank
slots nor carry the per-slot category/icon/rarity these views render. Both views' `DB_URL`/`DB_USER`/
`DB_PASS` constants, `DriverManager` calls, `java.sql` imports and `MaterialsView`'s TODO are gone.

### AC 4 — Preservation

SQL text, joins, filter and ordering are byte-for-byte the pre-extraction statements. Nullability
handling (`getObject` for the nullable integer columns) is unchanged. Both error paths are unchanged,
including `MaterialsView`'s silent empty page — deliberately preserved rather than "improved", since
changing it was outside this story. Nothing was added on either path: both services are read-only,
perform no synchronization and issue no writes. One intended behavior change, per the AC's explicit
carve-out: both views now use the application's configured connection, so a missing
`DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD` surfaces through each view's existing failure
presentation instead of silently reaching a literal `localhost` database.

Test-only additions to the views: `bankGrid`/`bankErrorLabel`/`materialsBlocks` node ids and a
three-argument `show(Stage, Runnable, <Service>)` overload on each view. The existing two-argument
`show` forwards with a real service, so `Gw2App` is untouched.

### AC 5 — Docs

`docs/CURRENT_ARCHITECTURE.md` §2 (package layout), §4 (the bypass bullet, rewritten), §6 (both view
rows plus the boundary-count paragraph — eleven named boundaries now) and §9 item 6 (struck through
as resolved); §10's status line no longer lists these two views as unread. `docs/KNOWN_PROBLEMS.md`
§2.2 marked resolved with evidence, plus its three dependent references (§2.1's scope correction, §6's
duplicate-connection-path row, §9's still-open list). Phase 3 is **not** declared complete — that
assessment belongs to `STORY-QUALITY-003`.

### Tests run

All via `./mvnw -o`, against local PostgreSQL and a real windowed TestFX session.

- `application.BankContentsServiceTest` (3) + `application.MaterialStorageServiceTest` (5) — fake
  repositories, no DB: delegation exactly once, row order preserved, category grouping/order,
  empty-group omission, unknown-category-id fallback, empty result, and `SQLException` propagating
  unchanged. **8 pass.**
- `repo.BankRepositoryTest` (4) + `repo.MaterialStorageRepositoryTest` (5) — disposable
  uniquely-named schema, dropped afterwards: slot/category ordering, the `items` LEFT JOIN mapping,
  null item/count for empty slots, null icon/rarity for unjoined items, the zero/null-count
  exclusion, the empty-table case, and that the no-arg load reaches the schema through `repo.Db`'s
  shared configuration rather than any view-local credentials. **9 pass.**
- `BankViewIT` (2) + `MaterialsViewIT` (2) — real window, controlled service results, no database:
  slot-order rendering into the grid with blank tiles for empty slots and the red `DB error: ...`
  label on failure; one block per category in service order and the unchanged empty page on failure.
  **4 pass.**
- Full default suite as a regression check: see CLAUDE_RESULT.md.

### Remaining uncertainty / limitations

- Icon image loading and rarity border colors are unchanged code and are not asserted by the view
  tests; they were verified by reading the diff, not by pixel assertion.
- The repository tests populate representative rows in a disposable schema rather than reading the
  developer's live bank/material tables, so "identical rendering against real account data" is
  argued from identical SQL plus identical mapping, not from a live-data diff.
- `MaterialsView`'s silent-failure presentation is preserved, not fixed; it remains recorded as a
  separate concern in `docs/KNOWN_PROBLEMS.md` §10 CH-03 (Materials conceals load failure), whose own
  note already states that it does not reopen §2.2.
- Phase 3 exit evidence is not assessed here.

## Blockers

None.
