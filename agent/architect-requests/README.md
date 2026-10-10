# Architect Requests

Architecture questions the planner is not authorized to decide. One file per
question, named `AR-<NUMBER>-short-name.md`.

These are not executable stories and never appear in `agent/stories/BACKLOG.md`.
They are also not a second human-decision mechanism: when a question turns out
to need the Product Owner, the architect escalates it through the existing
`agent/user-decisions/UD-*.md` flow and records the blocking `UD-*` here.

## Who writes what

- The **planner** (`agent/PLANNER_INSTRUCTIONS.md`) creates a request when
  planning hits an architectural question it may not decide, and never answers
  one. It may not edit an existing request; the orchestrator verifies that
  byte-for-byte after every planning pass.
- The **architect** (`agent/ARCHITECT_INSTRUCTIONS.md`) is the only role that
  answers a request. It is invoked by the orchestrator for exactly one request
  at a time, and only while an unresolved request exists -- there is no
  periodic architecture review.
- The **human Product Owner** answers the `UD-*` file a `NEEDS_USER` request
  points at, exactly as for any other user decision.

## Lifecycle

```text
OPEN         needs the architect
NEEDS_USER   escalated; waiting on the UD-* named in Blocking User Decision
RESOLVED     answered; never processed again
```

**Re-queuing a request by hand** is a normal operation: set `## Status` back to
`OPEN` and the architect is dispatched again on the next pipeline run.
`TODO`, `TO DO`, `NEW`, `PENDING` and `REOPENED` are also read as `OPEN`, since
that is what "not done yet" looks like everywhere else in this repository.

Any other value is **not** dispatched -- the harness never guesses a lifecycle
state -- but it is never silently ignored either: the orchestrator logs
`Architect request cannot be dispatched -- <file>: <reason>` and names the file
in its stop reason. The same applies to a `NEEDS_USER` request that names no
`UD-*`, or one whose `UD-*` does not exist: nothing can release it, so it is
reported rather than left to look like an ignored question.

- `OPEN` -> the orchestrator dispatches the architect.
- The architect either resolves it (updating the architecture documents or an
  ADR its own contract allows) or escalates it to `NEEDS_USER` with an OPEN
  `UD-*`.
- Once every blocking decision is `RESOLVED`, the orchestrator runs the
  architect again for that same request, automatically.
- Once the request is `RESOLVED`, planning continues automatically and consumes
  the decision as input.

A `RESOLVED` request is a receipt, not a requirements store: the decision
itself lives in its authoritative owner (`docs/TARGET_ARCHITECTURE.md`,
`docs/CURRENT_ARCHITECTURE.md`, `docs/KNOWN_PROBLEMS.md`, or an ADR under
`docs/architecture/decisions/`).

## Format

```markdown
# AR-001 — Short title

## Status

OPEN

## Architecture Question

The decision needed, stated precisely enough to be answerable.

## Context and Constraints

What is already known, decided, or ruled out.

## Authoritative References

The documents and sections that bear on the question.

## Blocked Work

What planning or story work cannot proceed until this is answered.

## Blocking User Decision

None.

## Architect Decision

TODO

## Resolution

TODO
```

All eight headings are required, in that order. `Architect Decision` and
`Resolution` stay `TODO` until the architect fills them in.
