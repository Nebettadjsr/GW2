# User Interventions

This folder records story-execution attempts that stopped because Claude
Code needs an external human/tooling action it cannot perform itself —
not a product, domain, or architecture decision. Those belong in
`agent/user-decisions/` instead; this folder is never used for them.

Examples of what belongs here:

- a permission or capability must be granted (e.g. shell/tool access was
  denied);
- a tool or capability the current session cannot install or invoke is
  missing;
- a manual verification step only a human can perform (e.g. interactive
  GUI/JavaFX behavior with no automation available);
- credentials or environment access Claude does not have;
- Claude explicitly asks the user to choose between concrete
  runtime/tooling options it cannot resolve itself.

Ordinary technical uncertainty Claude could resolve by investigating
further, or an incidental question in Claude's response that doesn't
actually block progress, is never a reason to create a file here.

## Lifecycle

1. The evaluator classifies a story-execution attempt as `NEEDS_USER`.
2. Python — never the evaluator itself — writes a new file here named
   `UI-<NUMBER>-<STORY-ID>.md` (e.g. `UI-001-STORY-DOM-015.md`), embedding
   the complete raw Claude response verbatim so you can see exactly what
   was requested and why.
3. The affected story is moved to `agent/stories/BACKLOG.md`'s
   `## Blocked` section, citing this file as its blocker. This does not
   consume the story's normal implementation retry count.
4. The development pipeline continues with other independently executable
   work; the blocked story's pointer is cleared.
5. Every pipeline run (by hand or the hourly schedule) re-checks this file's
   `## Status` before selecting work; nothing waits on a timer.
6. Once you have performed the required action, set `## Status` to
   `RESOLVED` and fill in `## User Resolution` / `## Resolution Notes`.
7. The pipeline then re-checks the story's normal eligibility
   (dependencies, other blockers) before requeuing it — resolving an
   intervention here never bypasses an unrelated blocker.

Never delete a file here manually while its story is still blocked; the
pipeline (Python, not an LLM) is the only thing that moves the story
back to `## To Do`, and only after re-verifying it is genuinely
executable.

## Format

```markdown
# User Intervention

## Status

OPEN

## Story

STORY-DOM-015

## Reason

Short evaluator/orchestrator explanation of why external intervention is
required.

## Claude Response

<the COMPLETE raw Claude response from the implementation attempt>

## User Resolution

TODO

## Resolution Notes

TODO
```

## Naming

`UI-<NUMBER>-<STORY-ID>.md`, e.g. `UI-001-STORY-DOM-015.md`. Numbers are
sequential and never reused, independent of the `STORY-*`/`UD-*` numbering
schemes.
