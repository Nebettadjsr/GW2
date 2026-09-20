# Product Owner Requests

Add Markdown files here describing requested changes, features, or product
requirements. These are planning inputs, not executable stories. Authoritative
requirements remain in docs and stories; retained requests provide a reviewable
record of what the planner did.

## Lifecycle

1. Add a request with Status OPEN. Legacy notes without Status are treated as OPEN.
2. Planning passes process OPEN and NEEDS_USER requests before creating stories.
   RESOLVED requests are ignored.
3. The planner checks existing coverage, updates the authoritative documents,
   and creates current-phase stories/backlog entries where needed. It reuses
   existing artifacts instead of duplicating them; later-phase work stays in
   the appropriate future planning documentation.
4. The planner updates the original request's Status and Planner Resolution,
   preserving the requested intent:
   - OPEN: incomplete processing; record progress and remaining work.
   - NEEDS_USER: record the blocking `agent/user-decisions/UD-*.md` file.
     The request stays visible until the decision permits resolution.
   - RESOLVED: the entire request is represented in authoritative planning
     artifacts. Briefly state exactly what was done (docs updated, stories
     created, backlog entries added, existing artifacts reused, or no action
     required), citing the artifact paths and relevant sections/story IDs.
     An unresolved user decision alone is not sufficient for resolution.
5. The planner never deletes request files. The human Product Owner reviews
   RESOLVED requests and deletes them manually when satisfied.

## Format

```markdown
# Product Owner Request

## Status

OPEN

## Title

## Requested Change

## Why / Product Intent

## Constraints

## Additional Context

## Planner Resolution

```

Fill in Title and Requested Change. Context sections are optional; leave
Planner Resolution for the planner. Less-structured notes are accepted and
receive lifecycle sections when processed.

Keep requests focused on product intent. They are never added directly to
`agent/stories/BACKLOG.md` as executable work.
