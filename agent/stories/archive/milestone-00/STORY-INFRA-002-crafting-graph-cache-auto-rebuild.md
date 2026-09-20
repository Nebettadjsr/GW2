## Story ID

STORY-INFRA-002

## Title

Auto-rebuild the crafting graph cache when missing, then stop tracking it in git

## Status

DONE

## Milestone

milestone-00

## Goal

Make `craft.CraftingGraphCache.load()` (or its callers) automatically rebuild the cache from the database when the cache file is absent, so `crafting_graph_cache.json` (~4.4 MB, currently tracked in git) can be safely removed from version control, per `docs/KNOWN_PROBLEMS.md` §7.5's own stated precondition: "once a reproducible rebuild step is guaranteed to exist for every environment."

## Authoritative Source Documents / Sections

- `docs/KNOWN_PROBLEMS.md` §7.5 (the confirmed observed fact and its exact precondition for gitignoring the file).
- `docs/CURRENT_STATE_SPEC.md` §14/§23 (describes the cache as derived/rebuildable data).
- `src/main/java/craft/CraftingGraphCache.java` — `load()` (throws `IOException` if the file does not exist) and `rebuild()` (already regenerates the file from `RecipeRepository`, already used by `Gw2App`'s "Sync TP Items" button).
- `src/main/java/CraftingProfitController.java` (~line 87-91) and `src/main/java/CraftingDiscoveryController.java` (~line 67-68) — both call `graphCache.load()` directly and currently turn a missing file into a thrown `RuntimeException`, with no fallback to `rebuild()`.
- `.gitignore`.

## Context

`CraftingGraphCache.rebuild()` already exists and is wired to a UI button (`Gw2App`'s "Sync TP Items" handler), but the two controllers that actually need the graph for their calculations (`CraftingProfitController`, `CraftingDiscoveryController`) only ever call `load()`, which fails hard if the file is missing — for example, on a fresh clone/fresh environment before that button has ever been pressed. This is why the file is currently checked into git rather than gitignored: today, gitignoring it would leave a fresh environment unable to run either the Crafting Profit or Crafting Discovery feature until a user happens to know to click "Sync TP Items" first.

## Acceptance Criteria

- When the cache file does not exist, `CraftingGraphCache.load()` (or the two call sites, if changing `load()` itself is not the natural fit) transparently calls `rebuild()` instead of throwing, so both controllers keep working on a fresh environment with no cache file present yet.
- The existing explicit `rebuild()` call sites (the "Sync TP Items" button) are unaffected — this is additive fallback behavior, not a change to when an explicit rebuild happens.
- Once the auto-rebuild fallback exists, `crafting_graph_cache.json` is removed from git tracking (`git rm --cached`) and added to `.gitignore`.
- No change to the graph's actual content/structure — this is only about when it gets (re)built.

## Required Tests

- None new required if the change is a small, direct addition to existing, already-manually-verified code paths (no existing automated test exercises `CraftingGraphCache`, which needs `RecipeRepository`/a live database — consistent with `docs/TEST_STRATEGY.md` §9's not-yet-decided repository-integration tooling). If a fake/in-memory `RecipeRepository` seam already exists or is trivial to introduce without a larger refactor, prefer adding a focused test for the fallback branch; otherwise state "None" and explain why, and note that manual verification (deleting the local cache file and confirming both the Crafting Profit and Crafting Discovery views still load) was performed instead.
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `rebuild()`'s cache-key/consistency logic.
- Do not remove the explicit "Sync TP Items" rebuild button/flow.
- Confirm removing `crafting_graph_cache.json` from git tracking does not delete it from disk (`git rm --cached`, not `git rm`) — a developer's local working copy must keep the file.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] `CraftingGraphCache.load()` (or its call sites) fall back to `rebuild()` when the cache file is missing.
- [x] Manually verified: deleting the local `crafting_graph_cache.json` and reopening Crafting Profit/Crafting Discovery still works.
- [x] `crafting_graph_cache.json` untracked from git (`git rm --cached`) and added to `.gitignore`.
- [x] `./mvnw test` shows unchanged pass/fail results.
- [x] `agent/CLAUDE_RESULT.md` filled in.

## Result

`craft.CraftingGraphCache.load()` now calls `rebuild()` itself when `crafting_graph_cache.json` does
not exist, instead of throwing. Changing `load()` (rather than the two call sites) was the natural
fit: both call sites (`CraftingProfitController`/`CraftingDiscoveryController`) already wrap the
call in `catch (Exception e)`, so widening `load()`'s throws clause from `IOException` to
`IOException, SQLException` (to match `rebuild()`, which already declares both) required no call-site
changes. `rebuild()`'s cache-key/consistency logic and content/structure are untouched; the
explicit "Sync TP Items" button (`Gw2App.java`) still calls `rebuild()` directly and is unaffected.

No automated test was added for the fallback branch itself, per the story's own guidance: exercising
`CraftingGraphCache` requires a live `RecipeRepository`/database, and no fake/in-memory
`RecipeRepository` seam exists or is trivial to introduce without a larger refactor (consistent with
`docs/TEST_STRATEGY.md` §9's not-yet-decided repository-integration tooling). Manual verification
was performed instead, using the same throwaway/uncommitted-harness pattern established by
`STORY-DOM-004`/`STORY-TEST-002`: a temporary JUnit test renamed the real
`crafting_graph_cache.json` aside, called `CraftingGraphCache.load()` against the local Postgres
instance, confirmed it rebuilt the graph (13,141 recipes) and recreated the cache file, then restored
the original file from the backup. The restored file's size (4,494,223 bytes) and mtime matched the
pre-test original, confirming no content drift. The harness file was deleted afterward and never
committed.

`crafting_graph_cache.json` was removed from git tracking via `git rm --cached` (confirmed via `ls`
that the file remains on disk afterward) and a `crafting_graph_cache.json` entry was added to
`.gitignore` under a new "Derived/rebuildable data" section, with a comment noting why
(`load()` now auto-rebuilds it).

## Files Materially Changed

- `src/main/java/craft/CraftingGraphCache.java` — `load()` falls back to `rebuild()` when the cache
  file is absent instead of throwing `IOException`; signature widened to `throws IOException,
  SQLException` to match `rebuild()`.
- `.gitignore` — added `crafting_graph_cache.json`.
- `crafting_graph_cache.json` — untracked from git (`git rm --cached`); still present on disk.

## Tests Run

`./mvnw test` (full suite, before and after the change) plus a throwaway, uncommitted manual
verification test (deleted after use — see Result section).

## Test Result

16 tests run, 0 failures, 0 errors, `BUILD SUCCESS` — identical to the pre-change baseline (no test
added or removed from the permanent suite).

## Blockers

None.
