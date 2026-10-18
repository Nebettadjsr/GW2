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

## To Do

## Blocked

## User

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
