# Story Backlog Index

Index only. Each entry points to its canonical story file under `agent/stories/`, which owns the story's status, goal, acceptance criteria, references, definition of done, and result/blockers.

The active story is the file pointed to by `agent/CURRENT_STORY.md`. `## Active` holds at most one
entry and is empty whenever no story is active; the harness fills it deterministically when it
activates a story.

Entry format is machine-read, not decorative. `agent/runtime/core/story_state.py` parses each row as
`- STORY-ID | <filename>.md | STATUS | milestone-NN | deps: …`, with the filename bare. A markdown
link in the filename field makes the row invisible to story selection, so rows in the queue sections
below stay in the plain form.

## Active

- STORY-INFRA-004 | STORY-INFRA-004-versioned-account-schema-and-legacy-preservation.md | TODO | milestone-06 | deps: None

## To Do

## Blocked
- STORY-APP-015 | STORY-APP-015-account-scoped-repositories-and-publication.md | BLOCKED | milestone-06 | deps: STORY-INFRA-004
- STORY-SYNC-006 | STORY-SYNC-006-account-scoped-refresh-and-task-ownership.md | BLOCKED | milestone-06 | deps: STORY-API-010, STORY-APP-015
- STORY-API-011 | STORY-API-011-enforce-account-isolation-across-http-workflows.md | BLOCKED | milestone-06 | deps: STORY-API-010, STORY-APP-015, STORY-SYNC-006
- STORY-WEB-035 | STORY-WEB-035-memory-only-account-credential-lifecycle.md | BLOCKED | milestone-06 | deps: STORY-API-011
- STORY-API-010 | STORY-API-010-validated-account-access-foundation.md | BLOCKED | milestone-06 | deps: None

## User

- [UI-008](../user-interventions/UI-008-STORY-API-010.md)

## Superseded

## Done

## Numbering Convention

`STORY-<AREA>-<NUMBER>-short-name.md`. Numbers are sequential **per area**, not global.

Area codes in use: `API` (HTTP contracts), `APP` (application services), `DOM` (domain behavior), `INFRA` (infrastructure), `PERF` (performance), `QUALITY` (milestone health review), `SYNC` (game-data/account refresh), `TEST` (test infrastructure), `UI` (JavaFX presentation), and `WEB` (browser UI). Numbers are sequential within each area.

## Archiving

`BACKLOG.md` indexes the current milestone only. When a roadmap milestone is confirmed complete,
the harness moves every DONE and SUPERSEDED story of it (and of any earlier milestone) into
`agent/stories/archive/milestone-NN/` and drops its row. Nothing is deleted; `docs/ROADMAP.md`
records what each closed phase delivered.
