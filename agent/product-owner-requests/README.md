# Product Owner Requests

Drop Markdown files here to hand the planner new work: requested changes,
new features, desired behavior changes, or other product-level
requirements.

This folder is transient input only — not a second requirements database.
Do not expect a request to still be here once it has been fully acted on
(see "Lifecycle" below), and do not treat it as a place to look up product
history later. Once processed, the permanent record lives in
`docs/TARGET_ARCHITECTURE.md`, `docs/DOMAIN_SPEC.md`, `docs/ROADMAP.md`,
`docs/KNOWN_PROBLEMS.md`, `agent/stories/`, `agent/stories/BACKLOG.md`, or
`agent/user-decisions/` — whichever actually owns that information.

## Lifecycle

1. You add a `.md` file here describing what you want.
2. Every PROJECT PLANNING MODE pass reads every file in this folder
   (except this `README.md`) before it creates any new stories.
3. For each request, the planner:
   - checks whether it is already fully covered by existing docs/stories —
     if so, nothing new is created for it;
   - updates the correct authoritative document first if it changes
     long-term intended behavior or architecture (`docs/TARGET_ARCHITECTURE.md`,
     `docs/DOMAIN_SPEC.md`, `docs/ROADMAP.md`, or `docs/KNOWN_PROBLEMS.md`,
     never more than one of these for the same fact);
   - converts concrete, current-phase-appropriate work into normal
     `STORY-*.md` files and `agent/stories/BACKLOG.md` entries;
   - keeps later-phase work represented only as future planned work, never
     as a current executable story;
   - opens (or reuses) an OPEN file under `agent/user-decisions/` and
     returns `NEEDS_USER` instead of guessing, whenever the request is
     clear on outcome but a genuine human decision (UX behavior, a
     business-rule ambiguity, a `TBD` technology choice, a materially
     different architectural alternative, or unspecified product
     behavior) blocks planning it.
4. A request file is deleted **only** once its information is fully
   represented elsewhere — by a doc update, a story, a user-decision file,
   or an existing planning artifact that already covered it. Nothing here
   is ever archived; the docs/stories/decisions it produced are the
   permanent record.
5. If a request is only partially processed, or needs a decision from you,
   it stays in this folder untouched until a later planning pass can
   finish it. A request is never deleted merely because it was read, and
   never deleted if processing it failed or is still blocked on you.

A request file is never turned into a directly executable unit itself —
it is planning input, not a story, and it is never referenced from
`agent/stories/BACKLOG.md`'s `## To Do` section.

## Format

Recommended, not required:

```markdown
# Product Owner Request

## Title

## Requested Change

## Why / Product Intent

## Constraints

## Additional Context
```

Only **Title** and **Requested Change** need actual content — the other
sections may be left empty.

Less-structured notes are also fine. The planner will still attempt to
understand a plain-language request that doesn't follow this template
rather than rejecting it purely for formatting.

Do not put implementation instructions or source-code-level detail here —
this is a product-intent inbox, not a story file.
