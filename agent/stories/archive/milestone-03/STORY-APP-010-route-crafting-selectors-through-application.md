## Story ID

STORY-APP-010

## Title

Route crafting selector reads through the application layer

## Status

DONE

## Milestone

milestone-03

## Goal

Remove the remaining direct CharacterRepository queries from both crafting views so selector data follows Phase 3's view-to-application boundary.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7: Phase 3 objective and JavaFX application-service delegation high-level story.
- docs/TARGET_ARCHITECTURE.md section 8: application-layer orchestration.
- docs/KNOWN_PROBLEMS.md section 4.4, CH-E2: selector repository bypass.
- agent/stories/STORY-QUALITY-003-phase-three-completion-review.md Result, F2.

## Context

The completed review identifies direct CharacterRepository reads in CraftingProfitView.reloadDisciplineChoices and CraftingDiscoveryView.reloadDisciplineChoices/reloadCharacterChoices. Existing calculation services do not remove these selector call sites. The planner retains this concrete remainder within Phase 3.

## Acceptance Criteria

1. Establish and record the existing selector-loading behavior before editing: choices, ordering, defaults, empty and error handling, selection preservation and reload triggers.
2. Move ownership of these character/crafting repository reads behind a named application operation, using the established service pattern. Both views obtain selector data through the application boundary and no longer instantiate or query CharacterRepository.
3. Preserve Profit's Discipline scope and Discovery's individual-character semantics, existing selector behavior and initial-load sequencing. Do not introduce extra repository loads or calculation reloads.
4. Keep UI control construction, formatting and event handling in presentation; leave repository queries and domain eligibility rules unchanged.
5. Record targeted verification and update CURRENT_ARCHITECTURE and the selector portion of KNOWN_PROBLEMS section 4.4 at their owners. Do not mark the separate total-profit finding resolved.

## Required Tests

- Application-level tests with a controlled repository collaborator proving selector read delegation, returned data and failure propagation.
- Targeted real-view checks for both crafting selectors covering populated/empty choices, retained defaults/scope semantics and selection-to-reload delegation. Exercise failure presentation through the established seam.
- Assess the initial-load path against the accepted STORY-PERF-001 behavior; if the extraction changes measured work or reload frequency, run the relevant real-database complete-page measurement and record its results. Do not substitute fixture timings for performance acceptance.

## Constraints

- Bounded behavior-preserving extraction only; no selector redesign, domain-rule change, broad concurrency remediation or infrastructure redesign.
- Preserve the accepted complete-page performance requirement in TARGET_ARCHITECTURE section 33.
- No milestone closure or criterion transfer in this story.

## Dependencies

STORY-APP-001, STORY-APP-002 and STORY-QUALITY-003 (all recorded DONE).

## Definition of Done

Both crafting views delegate selector reads through the application layer, relevant behavior checks pass, and Result and authoritative documentation record the boundary and verification limits.

## Result

DONE. Both crafting views populate their selectors through one named application operation; neither
instantiates nor queries `repo.CharacterRepository` any more. After this story no view in the
codebase constructs a `repo.*` repository.

### AC 1 — Pre-change selector behavior, established by reading the three pre-extraction call sites

Recorded before the boundary moved, and used as the preservation baseline for every check below.

| | `CraftingProfitView.reloadDisciplineChoices` | `CraftingDiscoveryView.reloadDisciplineChoices` | `CraftingDiscoveryView.reloadCharacterChoices` |
|---|---|---|---|
| Read | view-constructed `repo.CharacterRepository.loadAllCharacterCrafting()` | same method, own view-constructed instance | same instance, `loadAllCharacterNames()` |
| Repository order | `ORDER BY discipline, rating DESC, name` | identical | `ORDER BY name` |
| Trigger | once, from `show(...)`, immediately before the view's own initial `reloadTable.run()` | once, from `show(...)` | once, from `show(...)`, right after the discipline load |
| Thread | new daemon `Thread`, UI update via `Platform.runLater` | identical | identical |
| Choices | `clear()`, then `DiscChoice.all()`, then the nine base disciplines in the literal order Chef, Huntsman, Weaponsmith, Armorsmith, Artificer, Tailor, Leatherworker, Jeweler, Scribe, then one `charDiscipline(discipline, rating, charName)` per row in repository order | `clear()`, then **only** `charDiscipline(...)` per row in repository order — no "All", no discipline-only entry | `items.setAll(names)` |
| Default | unconditional `selectFirst()` → "All" | `selectFirst()` **only if** the item list is non-empty | previous value re-selected if still present, else `selectFirst()` if non-empty, else nothing |
| Empty data | "All" + the nine base disciplines, "All" selected | empty selector, nothing selected; the "Pick a 'Discipline lvl — Character' entry." status appears once any other listener runs `reloadTable` | empty selector, nothing selected |
| Error | `catch (Exception)` → `printStackTrace()` only; selector left empty, **nothing shown to the user** | `printStackTrace()` **plus** `statusLabel.setText("❌ Failed loading characters: " + message)` | `printStackTrace()` only — silent, as in Profit |
| Selection preservation | none (always clears + `selectFirst`) | none (always clears) | explicit previous-value preservation; unreachable in production today because the method only runs once per page open |
| Reload coupling | value listener reloads only when `sameScope(old, new)` is false, so the background `null → ALL` transition does **not** start a second reload (STORY-PERF-001) | value listener reloads on every change | value listener reloads on every change |

Initial-load sequencing: Profit starts one selector load and one table reload per page open. Discovery
starts two selector loads and no reload of its own — its first reload(s) come from the two
`selectFirst()` calls (both firing is the pre-existing overlap recorded in `KNOWN_PROBLEMS.md` §10
CH-01, untouched here).

### AC 2 — Application operation

`application.CharacterSelectionService`, with `getCraftingCharacterOptions()` and
`getCharacterNames()` delegating to the unchanged `CharacterRepository.loadAllCharacterCrafting()` /
`loadAllCharacterNames()`. It follows the `BankContentsService`/`MaterialStorageService` pattern
exactly: no JavaFX import, no calculation, one persistence collaborator behind a constructor seam
(the no-arg constructor builds the real repository), `SQLException` propagated unchanged so each view
keeps its own failure presentation. One shared service rather than new methods on
`CraftingProfitService`/`CraftingDiscoveryService`, because both views need the same read and it must
not be coupled to either calculation. Each view gained a five-argument
`show(Stage, Runnable, TradingPostPriceRefreshService, AccountRefreshService, CharacterSelectionService)`
overload; the existing four-argument overload forwards with a real service, so `Gw2App` and every
existing caller are untouched.

### AC 3 / AC 4 — Preservation

Every row of the AC 1 table still holds, including the two deliberately different failure
presentations (Profit silent, Discovery's "Failed loading characters" status) and Discovery's
currently-unreachable previous-selection branch, both preserved rather than "improved". The static
base-discipline list, `DiscChoice` construction, `clear()`/`setAll`/`selectFirst` calls, threading,
`Platform.runLater` hand-off, control construction and every value listener stayed in the views
verbatim; only the data source expression changed. No SQL, no repository method and no domain
eligibility rule was touched. The call count is unchanged — one `loadAllCharacterCrafting()` per
crafting page open (Profit and Discovery each), one `loadAllCharacterNames()` per Discovery page
open — and no reload was added or removed.

**Performance assessment (required test 3):** the extraction changes neither the measured work nor
the reload frequency on the initial-load path — same two repository methods, same number of calls,
same threads, same sequencing, and Profit's `sameScope` suppression of the `null → ALL` transition is
untouched (still pinned by `CraftingProfitViewScopeChangeTest`). `TARGET_ARCHITECTURE.md` §33's
accepted complete-page measurement is therefore unaffected, so the real-database benchmark was
deliberately **not** re-run and no fixture timing is offered in its place.

### AC 5 — Docs

`docs/CURRENT_ARCHITECTURE.md` §2 (package layout), §4 (the selector-bypass bullet, rewritten), §5.1
and §5.2 (selector population added to both flow diagrams), §6 (both crafting-view rows plus the
boundary-count paragraph — eleven named boundaries, and no view constructs a repository any more).
`docs/KNOWN_PROBLEMS.md` §4.4 records the selector half of CH-E2 as resolved with evidence and
explicitly restates that the total-profit duplication remains open; §10's CH-E2 summary line carries
the same split. The total-profit finding (`STORY-APP-011`) was **not** marked resolved and no
production code related to it was touched. No milestone or phase transition is declared here.

### Tests run

All via `./mvnw -o`, against local PostgreSQL and a real windowed TestFX session.

- `application.CharacterSelectionServiceTest` (5, new) — fake `CharacterRepository`, no DB: both
  reads delegate exactly once and return the repository's rows/names in repository order, neither
  read triggers the other, nothing-synced returns empty lists, and each read's `SQLException`
  propagates as the same instance. **5 pass.**
- `CraftingProfitViewSelectorIT` (4, new) — real window, injected selector results, fixture schema:
  "All" + the nine base disciplines + per-character entries in service order with "All" selected;
  the empty read still offering All + base disciplines; a failed read leaving the selector empty
  with no "❌" in the status label while the page still renders; and a genuine scope change starting
  a reload (asserted by reading the status label inside the same FX runnable as the selection, so an
  unrelated background completion cannot be mistaken for it). **4 pass.**
- `CraftingDiscoveryViewSelectorIT` (5, new) — same setup: per-character-only entries plus the name
  list, each with its first entry selected and one call to each operation; empty crafting rows
  leaving the scope selector unselected with the existing "Pick a 'Discipline lvl — Character'
  entry." status; the existing "❌ Failed loading characters: …" status on a failed crafting read;
  a failed name read staying silent with the rest of the page working; and a character change
  starting a reload. **5 pass.**
- Regression: full default suite `./mvnw -o test` — **165 pass, 0 failures**. Plus the existing
  crafting-view integration tests on the final tree: `CraftingProfitViewAutoRefreshIT`,
  `CraftingDiscoveryViewAutoRefreshIT`, `CraftingProfitViewTpRefreshIT`,
  `CraftingDiscoveryViewTpRefreshIT` (8), and `uiverify.CraftingProfitViewSmokeIT`,
  `uiverify.CraftingProfitViewRefreshPreservationIT`,
  `uiverify.CraftingDiscoveryViewRefreshPreservationIT`, `uiverify.CraftingProfitViewBlockedRowIT`,
  `uiverify.CraftingDiscoveryViewBlockedRowIT`, `uiverify.CraftingProfitCoordinatedScopeIT`,
  `uiverify.CraftingProfitViewErrorStatusIT` (7). **All pass.**

### Remaining uncertainty / limitations

- The new view checks inject a controlled service, so they prove the view's use of the boundary and
  its rendering, not the SQL — that stays unchanged in `repo.CharacterRepository` and is not covered
  by a disposable-schema test of its own (none existed before this story either).
- "No extra calculation reload was introduced" is argued from the unchanged listener code plus the
  existing `sameScope` unit pins, not from an instrumented reload counter: the views construct their
  own controllers, so a reload count is not observable from a test today.
- The performance claim above is an assessment of unchanged work, not a fresh measurement; §33's
  acceptance still rests on `STORY-PERF-001`'s recorded run.
- `CharacterRepository.DiscRow`, a persistence row type, still crosses the application layer into the
  views — the same shape as review finding F4, which §34 records as not current debt.
- Discovery's previous-selection preservation branch is preserved but remains unreachable in
  production, so no test exercises it.
- Phase 3 / milestone closure is not assessed here.

## Blockers

None.
