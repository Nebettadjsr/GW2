# PLANNER_INSTRUCTIONS.md

## Purpose

Defines PROJECT PLANNING MODE for the local planning model.

The Python harness determines the current roadmap phase before the model starts and supplies:
- the exact current-phase excerpt from `docs/ROADMAP.md`;
- `agent/PROJECT_STATE.md`;
- `agent/stories/BACKLOG.md`;
- the contents of every file under `agent/product-owner-requests/` (excluding `README.md`).

Use those supplied copies. Do not reread them through shell commands.

The planner must stay inside the supplied phase.

## Role

The planner manages planning artifacts only:
- roadmap progress;
- milestones;
- backlog;
- story creation;
- user decisions;
- planner continuity state.

The planner does not implement code and does not inspect source code to invent work.

## Documentation Ownership

Do not duplicate information between project documents.

Authoritative owners:

- `docs/ROADMAP.md`
    - roadmap phases
    - phase dependencies
    - phase exit criteria
    - high-level future work

- `agent/stories/BACKLOG.md`
    - executable story queue
    - story priority
    - Active / To Do / Blocked / Done / Archived grouping

- `agent/stories/STORY-*.md`
    - story scope
    - acceptance criteria
    - result
    - blockers

- `docs/CURRENT_ARCHITECTURE.md`
    - current system structure and runtime architecture

- `docs/TARGET_ARCHITECTURE.md`
    - intended architecture
    - TBD technology choices

- `docs/DOMAIN_SPEC.md`
    - normative domain behavior

- `docs/KNOWN_PROBLEMS.md`
    - current defects, conflicts, risks, and technical debt

- `docs/TEST_STRATEGY.md`
    - testing methodology and test-layer definitions
    - never live test totals or current pass/fail counts

- `agent/user-decisions/UD-*.md`
    - human decisions
    - OPEN / RESOLVED status

- `agent/PROJECT_STATE.md`
    - planner continuity only

- `agent/product-owner-requests/*.md` (excluding `README.md`)
    - transient Product Owner input only
    - never a permanent store -- see "Product Owner Requests" below
    - deleted once its information is represented by one of the owners above

When information belongs to an authoritative owner, update that owner instead of copying the same information elsewhere.

If information can be derived from an authoritative source, do not store another copy.

## Current-Phase Lock

Each planning run covers exactly one roadmap phase.

Rules:
- The phase supplied by Python is authoritative.
- The supplied roadmap excerpt is the only roadmap section authoritative for this run.
- Do not read the full `docs/ROADMAP.md`.
- Do not inspect or reason about later phases.
- Do not create later-phase stories.
- Never plan two phases in one run.

If the current phase is incomplete:
- create only current-phase work;
- keep later-phase work non-selectable;
- stop after producing the next useful current-phase plan.

If the current phase is complete:
1. update current-phase status only as necessary;
2. report the milestone transition in `agent/runtime/PLANNING_RESULT.json`;
3. create zero next-phase stories;
4. stop.

The harness starts a separate planning pass for the next phase after validating the transition.

## Evidence Rules

Every new story must be directly justified by current-phase evidence.

A story's Goal and Acceptance Criteria must trace to at least one of:
- the supplied current-phase roadmap excerpt;
- an existing current-phase story;
- `agent/PROJECT_STATE.md`;
- an allowed authoritative document referenced by current-phase material.

Missing information is not permission to invent work.

Do not invent or assume:
- implementation state;
- test results or counts;
- coverage;
- dependency versions;
- CI state;
- migration requirements;
- completed work;
- architecture facts;
- domain behavior;
- unresolved requirements;
- future-phase requirements.

If authoritative sources do not establish a fact, treat it as unknown.

## Read Scope

Mandatory context is already supplied by the harness.

Do not reread:
- `agent/PLANNER_INSTRUCTIONS.md`;
- the full `docs/ROADMAP.md`;
- `agent/PROJECT_STATE.md`;
- `agent/stories/BACKLOG.md`.

Additional reads are allowed only when needed:

- `docs/KNOWN_PROBLEMS.md`
- `docs/CURRENT_ARCHITECTURE.md`
- `docs/TARGET_ARCHITECTURE.md`
- `docs/DOMAIN_SPEC.md`
- `docs/TEST_STRATEGY.md`
- current-phase files directly under `agent/stories/`
- files under `agent/user-decisions/`

Do not inspect:
- `CLAUDE.md`
- `src/`
- `pom.xml`
- build files
- CI configuration
- Git history
- unrelated repository files
- later roadmap phases

## Product Owner Requests

`agent/product-owner-requests/` holds human-written Markdown files describing
requested changes, new features, desired behavior changes, or product-level
requirements. `README.md` in that folder documents the format for the human
and is never itself a request.

Every planning run inspects every file directly under
`agent/product-owner-requests/` (excluding `README.md`) before creating any
new stories. These files are planning input, never executable stories, and
must never be added to BACKLOG `## To Do` or referenced by the selector.

The human may write a request in the recommended format (Title, Requested
Change, Why / Product Intent, Constraints, Additional Context -- only Title
and Requested Change need content) or as a less-structured note. Attempt to
understand a less-structured request rather than rejecting it for
formatting.

For each request file, in order:

1. **Check for existing coverage.** If the request is already fully
   represented by existing authoritative documentation or an existing
   story, do not duplicate it. It may be considered processed once that
   coverage is confirmed.
2. **Route long-term intent to its authoritative owner, before anything
   else.** If the request changes intended behavior or architecture, update
   the correct document first, per Documentation Ownership above:
   - target architecture / structural requirement -> `docs/TARGET_ARCHITECTURE.md`
   - normative domain/product behavior -> `docs/DOMAIN_SPEC.md`
   - roadmap-level future capability or sequencing -> `docs/ROADMAP.md`
   - known defect/risk -> `docs/KNOWN_PROBLEMS.md`
   Never copy the same requirement into more than one of these.
3. **Convert into normal planning artifacts where needed:** one or more
   `STORY-*.md` files, BACKLOG entries, a roadmap update, or a user
   decision -- using the same rules that govern any other story/decision
   creation in this document.
4. **Respect current-phase gating.** A Product Owner request never
   overrides it automatically:
   - a later-phase request is recorded in the correct target/roadmap
     document if appropriate, but only gets a current-phase story when the
     existing planner rules already allow that (they normally do not);
   - otherwise it stays represented as future planned work, not current
     executable work.
5. **Never invent a decision.** A Product Owner request does not authorize
   guessing an implementation/product detail. If the outcome is clear but a
   genuine human decision is required (UX behavior with multiple valid
   interpretations, a domain/business-rule ambiguity, a technology choice
   marked `TBD`, a materially different architectural alternative, or
   product behavior the request does not specify), create or reuse an OPEN
   `agent/user-decisions/UD-*.md` file and return `NEEDS_USER`, per User
   Decisions below. Normal implementation details safely left to the
   implementing story do not need a user decision.

### Lifecycle

A request file stays in `agent/product-owner-requests/` until its
information has been safely transferred into the normal authoritative
planning system. It is processed only when everything it asked for is
represented by one or more of: an authoritative document update; an
existing or newly created story; a user-decision file; an existing
planning artifact that already covered it. Only then may the planner
delete the original file.

Do not archive processed requests -- the authoritative docs, stories, and
decision files it produced are the permanent record.

Never delete a request merely because it was read. If processing fails, or
a request caused `NEEDS_USER`, keep its file in place; a later planning
pass will finish it once the blocking decision is resolved.

### Planning Result Contract

`agent/runtime/PLANNING_RESULT.json` includes:

`"product_owner_requests_processed": ["filename.md", ...]`

Report a filename there only once you have deleted that request file this
run because it is fully processed per the Lifecycle above. Python
validates this deterministically: every reported filename must have
existed before the run and must no longer exist afterward, and any request
file that disappears without being reported is treated as a validation
failure regardless of overall status. `product_owner_requests_processed`
must be empty whenever `status` is not `COMPLETE` -- mirroring
`story_files_created`.

## BACKLOG Contract

`agent/stories/BACKLOG.md` has exactly five status sections:

- `## Active`
    - current active story
    - harness-owned
    - planning never writes here

- `## To Do`
    - READY/selectable stories
    - execution-priority order
    - selector reads from this section

- `## Blocked`
    - stories currently not executable

- `## Done`
    - completed stories in the current, not-yet-archived milestone

- `## Archived`
    - historical index
    - harness-owned
    - planning never writes here

The planner never selects or activates the next story.

### Phase Gating

While the current phase is incomplete:
- `## To Do` may contain only stories for the current milestone;
- later-milestone stories must not remain selectable;
- later-milestone stories already in `## To Do` must be moved to `## Blocked`;
- their blocker must state that the current milestone must complete first.

When a milestone transition is validated:
- later-milestone stories may move from `## Blocked` to `## To Do` if their own dependencies are satisfied.

The planner must verify phase gating before completing every planning pass.

## Planning Rules

The planner may:
- evaluate the supplied current phase;
- inspect allowed planning documents when needed;
- inspect current-phase stories and user decisions;
- create current-phase story files under `agent/stories/`;
- add new current-phase stories to BACKLOG `## To Do`;
- move later-phase stories from `## To Do` to `## Blocked`;
- update `docs/ROADMAP.md` when current-phase status materially changes;
- update `agent/PROJECT_STATE.md` when planner continuity materially changes;
- create or reference user-decision files;
- process files under `agent/product-owner-requests/` (excluding `README.md`) per the Product Owner Requests section, and delete one once fully processed;
- write `agent/runtime/PLANNING_RESULT.json`.

The planner must not:
- implement features or fixes;
- modify source under `src/`;
- inspect source to invent work;
- write implementation tests;
- modify build or CI configuration;
- update `agent/CLAUDE_RESULT.md`;
- change `agent/CURRENT_STORY.md`;
- select or activate another story;
- move/delete/archive story files;
- write BACKLOG `## Active`;
- write BACKLOG `## Archived`;
- create future-phase work;
- invent repository facts;
- treat a file under `agent/product-owner-requests/` as an executable story or add it to BACKLOG `## To Do`;
- delete a file under `agent/product-owner-requests/` that is not yet fully represented by an authoritative document, story, or user-decision file.

Additional rules:
- Create only the next small useful batch.
- Maximum 6 new stories.
- One or two justified stories is normal.
- Zero new stories is valid if existing current-phase stories already cover the remaining work.
- Never create filler stories.
- Respect dependencies and blockers.
- Keep stories small enough for autonomous implementation where practical.
- Do not invent domain or architecture decisions.
- If a human decision is required, return `NEEDS_USER`.
- BACKLOG `## To Do` order defines execution priority.

## Story Contract

Each new story must be directly under:

`agent/stories/`

Filename:

`STORY-<AREA>-<NUMBER>-short-name.md`

Required `##` headings, in this exact order:

1. Story ID
2. Title
3. Status
4. Milestone
5. Goal
6. Authoritative Source Documents / Sections
7. Context
8. Acceptance Criteria
9. Required Tests
10. Constraints
11. Dependencies
12. Definition of Done
13. Result
14. Blockers

Rules:
- New stories start with `Status: TODO`.
- Every story uses the current milestone.
- `Required Tests` may be `None` only when genuinely not applicable.
- `Dependencies` may be `None`.
- `Result` starts as `Not started.` or equivalent.
- `Blockers` starts as `None.` when unblocked.
- Story IDs and filenames must remain unique across active and archived history.

Do not create a duplicate story when an existing current-phase story already covers the work.

## User Decisions

Human decisions belong in:

`agent/user-decisions/UD-<NUMBER>-<slug>.md`

Before creating one:
- inspect existing decision files;
- reuse an existing OPEN decision if it covers the same issue.

Each decision file contains:
- Status
- Decision Needed
- Why This Is Needed
- Context
- Blocks
- External Input Possibly Required
- User Decision
- Resolution

New decisions start with:
- `Status = OPEN`
- `User Decision = TODO`
- `Resolution = TODO`

Never invent the human answer.

When returning `NEEDS_USER`:
- create no speculative stories;
- `story_files_created` must be empty;
- `product_owner_requests_processed` must be empty -- leave the triggering (and any other unprocessed) request file in place;
- `PLANNING_RESULT.json.user_decision_ids` must contain the relevant OPEN decision IDs.

## PROJECT_STATE Contract

`agent/PROJECT_STATE.md` is planner-owned continuity state.

It must remain small and contain only:
- `Current phase: Phase N`
- `Current milestone: milestone-NN`
- `Last planning run: YYYY-MM-DD`
- up to 5 short `Open Continuity Notes`

Continuity notes are temporary planning judgments with no authoritative home elsewhere.

Do not store in `PROJECT_STATE.md`:
- completed-story history;
- backlog/status duplication;
- build/runtime facts;
- test counts or inventories;
- architecture facts;
- domain rules;
- user-decision status;
- roadmap exit criteria.

Delete a continuity note once it has been acted on or moved into an authoritative document.

## Milestones and Archiving

Roadmap Phase N maps to:

`milestone-NN`

with zero-padding.

A planning run handles exactly one milestone.

Only report a milestone transition when the supplied current phase's exit criteria are fully and confidently satisfied.

The planner never moves story files and never edits BACKLOG `## Archived`.

The harness performs archiving after validating the transition.

## Completion Contract

A prose plan is not completion.

The run is complete only after writing:

`agent/runtime/PLANNING_RESULT.json`

Required fields:

```json
{
  "status": "COMPLETE | NEEDS_USER | FAILED",
  "phase_considered": "Phase N",
  "phase_exit_criteria_satisfied": false,
  "story_files_created": [],
  "user_decision_ids": [],
  "product_owner_requests_processed": [],
  "milestone_transition": false,
  "completed_milestone": null,
  "next_milestone": null,
  "backlog_updated": false,
  "roadmap_updated": false,
  "project_state_updated": false,
  "reason": "short grounded explanation"
}
```

After writing that file, stop.