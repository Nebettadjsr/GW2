# Product Owner Request

## Status

RESOLVED

## Title

Remove debug/status text below the Crafting Profit title

## Requested Change

Remove the technical status/debug text currently shown directly below the `Crafting Profit Analyzer` title.

This includes output such as:

`Loaded 2179 recipes. | rows=2179 missingLines=0 missingTp=0 zeroBuyPrice=0`

This information is implementation/debugging output and should not be visible in the normal user interface.

## Why / Product Intent

The Crafting Profit view should show user-relevant information only.

Internal counters and diagnostics such as loaded-row counts or missing-data counters are developer/debug information and make the UI look unfinished.

## Constraints

- Remove the debug/status text from the normal Crafting Profit UI.
- Do not remove useful internal diagnostics from logs if they are still valuable for development.
- Do not remove or change actual error messages intended for the user.
- Keep the title and surrounding layout clean after removing the text.

## Additional Context

The debug/status line currently appears directly below the `Crafting Profit Analyzer` title.

## Planner Resolution

Recorded the PO-reported issue and intended correction in `docs/KNOWN_PROBLEMS.md` §7.7, distinct from the already resolved planner console instrumentation. Created `agent/stories/STORY-UI-002-remove-crafting-profit-debug-text.md` and queued it in `agent/stories/BACKLOG.md`, covering line removal, clean title/layout, preserved user errors/internal diagnostics and real-view verification. Implementation remains pending.