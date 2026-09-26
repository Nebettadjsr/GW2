# PLANNER_INSTRUCTIONS.md

## Purpose

Defines PROJECT PLANNING MODE for the local planning model.

The Python harness determines the current roadmap phase before the model starts and supplies:
- the exact current-phase excerpt from `docs/ROADMAP.md`;
- `agent/PROJECT_STATE.md`;
- a deterministic index of `agent/stories/BACKLOG.md`: every story ID and filename in the project, with each story's status, milestone and dependencies; the live `## Active`/`## To Do`/`## Blocked` sections in their own order and wording, and completed entries compacted to one line each;
- the contents of OPEN and NEEDS_USER files under `agent/product-owner-requests/` (excluding `README.md`; missing Status means OPEN);
- the `agent/architect-requests/` inbox: unresolved requests in full, resolved ones as a compact architect-decision line;
- user-decision questions, blockers and answers across all statuses: unresolved decisions in full, resolved ones as question and answer;
- resolved PO request coverage receipts, summarized with the artifacts they cite, as read-only deduplication input.

Use those supplied copies. Do not reread them through shell commands.

Finished history is compacted, never discarded: the index names the file that
holds each detail. Read that one file, or that one BACKLOG section, when a
specific detail decides something.

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
    - repository quality targets and PROJECT HEALTH REVIEW policy (§34)

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

- `agent/architect-requests/AR-*.md`
    - architecture questions for the architect role
    - OPEN / NEEDS_USER / RESOLVED status
    - planner-created, architect-owned; never planner-answered

- `docs/architecture/decisions/ADR-*.md`
    - significant architecture decision records
    - architect-owned; planning never writes here

- `agent/product-owner-requests/*.md` (excluding `README.md`)
    - Product Owner input with retained planner resolution notes
    - authoritative requirements still belong to the owners above
    - only the human Product Owner deletes reviewed RESOLVED files

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
- review remaining current-phase areas before producing the next useful bounded plan.

If the current phase is complete:
1. update current-phase status only as necessary;
2. report the milestone transition in `agent/runtime/artifacts/PLANNING_RESULT.json`;
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

Mandatory context is already supplied by the harness, and the task prompt's
own read list is authoritative for the run.

Do not reread:
- `agent/PLANNER_INSTRUCTIONS.md`;
- `AGENTS.md` (its instruction to read this contract is already satisfied by the supplied copy);
- the full `docs/ROADMAP.md`;
- `agent/PROJECT_STATE.md`;
- the supplied requests, decision summaries and backlog index.

Targeted reads are allowed when a specific detail actually decides something:

- one named section of `docs/KNOWN_PROBLEMS.md`, `docs/CURRENT_ARCHITECTURE.md`,
  `docs/TARGET_ARCHITECTURE.md`, `docs/DOMAIN_SPEC.md` or `docs/TEST_STRATEGY.md`
- one story file under `agent/stories/` or `agent/stories/archive/`
- one file under `agent/user-decisions/`
- one named section of `agent/stories/BACKLOG.md`, when a completed entry's
  narrative is the evidence you need

Read a section or line range rather than a whole large document, batch
independent reads into one command, and never read the same thing twice.
Editing `agent/stories/BACKLOG.md` is not a read: change it in place with a
script that never prints its contents.

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

Every planning run processes OPEN and NEEDS_USER files directly under
`agent/product-owner-requests/` (excluding `README.md`) before creating any
new stories. Consult RESOLVED coverage receipts to avoid duplicates, but never edit them; legacy notes without Status are OPEN. These files are planning input, never executable stories, and
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
   `agent/user-decisions/UD-*.md` file for that work, then continue examining
   other work. Route technical architecture questions to Architect Requests.
   Choose the final result only after the phase review below. Normal implementation details safely left to the
   implementing story do not need a user decision.

### Architecture-to-Roadmap Consistency

Whenever a Product Owner request causes a change to
`docs/TARGET_ARCHITECTURE.md`, the planner must in the same planning pass
check whether that change implies:

- new roadmap work;
- new or changed current-phase exit criteria;
- a recurring quality gate or maintenance obligation;
- new current-phase stories;
- a dependency or sequencing change.

If so, update the appropriate planning artifacts in the same pass.

A requirement must not remain only in TARGET_ARCHITECTURE when fulfilling it
requires executable work or recurring planning behavior.

### Lifecycle

Never delete Product Owner request files. After processing, update the
original file, preserving its product intent, with these sections:

```markdown
## Status

OPEN

## Planner Resolution

```

Status must be OPEN, NEEDS_USER, or RESOLVED. Missing Status in legacy notes
means OPEN; add the lifecycle sections when processing them.

- OPEN: processing remains incomplete; describe any progress and remaining work.
- NEEDS_USER: identify the blocking `agent/user-decisions/UD-*.md` file in
  Planner Resolution. Keep the request visible until that decision allows
  planning to finish. Creating a user decision alone does not resolve a request.
- RESOLVED: only when the entire request is represented in authoritative
  planning artifacts. Briefly state exactly what was done: authoritative docs
  updated, stories created, backlog entries added, existing artifacts reused,
  or no action required. Cite the actual Markdown artifact paths and relevant
  sections/story IDs, including existing coverage for a no-action resolution.

Future passes do not reprocess RESOLVED files; their coverage receipts remain
read-only deduplication input. OPEN and NEEDS_USER remain visible.
Only the human Product Owner deletes reviewed RESOLVED files manually.
The request's resolution note is a receipt, not a second requirements store.

### Planning Result Contract

`agent/runtime/artifacts/PLANNING_RESULT.json` includes:

`"product_owner_requests_processed": ["filename.md", ...]`

Report only requests newly marked RESOLVED in this run. Each must still exist,
have a nonempty Planner Resolution, and cite existing authoritative artifacts.
Python rejects request deletion, invalid statuses, missing resolution/evidence,
missing blocking UD files, and unreported resolutions. It validates structural
evidence; the planner must verify that the artifacts fully cover the request.
Already RESOLVED files must remain unchanged and must not be reported again.
On COMPLETE or NEEDS_USER, list independently resolved requests. A blocked
request remains NEEDS_USER; unrelated requests may be resolved in the same pass.
On FAILED, retain requests as OPEN or NEEDS_USER rather than RESOLVED.

## Architect Requests

Architecture is owned by a separate Codex role, the **architect**
(`agent/ARCHITECT_INSTRUCTIONS.md`, routed by `AGENTS.md`). The planner never
enters ARCHITECTURE MODE and never decides an architectural question it is not
authorized to decide.

When planning runs into such a question -- a component/layer boundary the
authoritative documents do not settle, a technology still marked `TBD`, a
structural alternative with materially different consequences, or a conflict
between two architecture documents -- create an architect request instead of
guessing, and instead of going straight to a User Decision:

`agent/architect-requests/AR-<NUMBER>-short-name.md`

Required `##` headings, in this exact order:

1. Status (`OPEN`)
2. Architecture Question
3. Context and Constraints
4. Authoritative References
5. Blocked Work
6. Blocking User Decision (`None.`)
7. Architect Decision (`TODO`)
8. Resolution (`TODO`)

Rules:
- state the question precisely, the context and constraints already known, the
  authoritative documents/sections that bear on it, and exactly what planning
  or story work it blocks;
- leave `Architect Decision` and `Resolution` as `TODO`; answering your own
  request is a role violation;
- IDs and filenames are unique for all time;
- never create a second request for a question an unresolved request already
  asks -- record that the work is blocked by the existing `AR-*` instead;
- never modify, resolve or delete an existing request; the harness compares
  them byte-for-byte after every pass and rolls the pass back if one changed;
- never write `docs/architecture/decisions/ADR-*.md`.

Do not route a trivial implementation choice to the architect: naming, local
structure inside one class, test placement, or obvious reuse of an existing
pattern belong to the implementing story.

Consuming architect output:
- a `RESOLVED` request's `Architect Decision` is established architecture
  input. Plan with it, and convert the implementation work it implies into
  normal current-phase stories when the phase allows;
- the authoritative home of that decision is the document or ADR the architect
  updated -- do not copy it into a story, the roadmap or `PROJECT_STATE.md`;
- a `NEEDS_USER` request is waiting on the Product Owner through its own
  `agent/user-decisions/UD-*.md` file. Treat the work it blocks as blocked, and
  do not create a competing decision for the same question;
- if an architecture change affects roadmap sequencing or phase exit criteria,
  reconcile `docs/ROADMAP.md` in a normal planning pass.

Python supplies the inbox with the planning prompt: unresolved requests in
full, resolved ones as a compact decision line. Do not read the folder through
the shell.

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
- create architect requests under `agent/architect-requests/` for architectural
  questions it is not authorized to decide, per Architect Requests below;
- process files under `agent/product-owner-requests/` (excluding `README.md`) per the Product Owner Requests section, and update each original file with its Status and Planner Resolution;
- write `agent/runtime/artifacts/PLANNING_RESULT.json`.

The planner must not:
- implement features or fixes;
- modify source under `src/`;
- inspect source to invent work;
- write implementation tests;
- modify build or CI configuration;
- update `agent/runtime/artifacts/CLAUDE_RESULT.md`;
- change `agent/CURRENT_STORY.md`;
- select or activate another story;
- move/delete/archive story files;
- write BACKLOG `## Active`;
- write BACKLOG `## Archived`;
- create future-phase work;
- invent repository facts;
- treat a file under `agent/product-owner-requests/` as an executable story or add it to BACKLOG `## To Do`;
- delete any file under `agent/product-owner-requests/`; only the human Product Owner removes reviewed RESOLVED requests;
- enter ARCHITECTURE MODE, answer its own architect request, or modify/resolve/delete an existing one;
- write `docs/architecture/decisions/ADR-*.md`.

Additional rules:
- Create only the next small useful batch.
- Maximum 6 new stories.
- One or two justified stories is normal.
- Zero new stories is valid if existing current-phase stories already cover the remaining work.
- Never create filler stories.
- Respect dependencies and blockers.
- Keep stories small enough for autonomous implementation where practical.
- Do not invent domain or architecture decisions.
- An open UD blocks only its dependent work; choose status after the phase review.
- BACKLOG `## To Do` order defines execution priority.

Before creating new current-phase stories, check whether
`docs/TARGET_ARCHITECTURE.md` contains requirements relevant to the current
phase that are not yet represented by the supplied roadmap excerpt, existing
stories, or backlog.

Read only the relevant sections; do not reread the full document
unnecessarily.

## Explore Past Individual Blockers

A planning pass explores past individual blockers and maximizes useful independent
planning progress within the current milestone before declaring itself blocked.

Before drafting the small story batch, review the remaining relevant areas in the
supplied phase excerpt, current PO requests, existing backlog/stories and relevant
authoritative requirements. For each area, establish one or more of:
- PLANNED: executable work created in this pass;
- READY: justified independent work still available for a subsequent small batch;
- USER_DECISION: the specific OPEN UD(s) needed for that area;
- ARCHITECT_REQUEST: the specific unresolved AR(s) needed for that area;
- COVERED: existing authoritative artifacts or stories already cover the work;
- PREREQUISITE: a specific unfinished story must finish before this work can proceed.

Record a compact `phase_review` in the result: area, outcome and artifact references.
Use separate entries if an area has both ready and blocked parts. This is a receipt
of the review, not a new requirements store or an exhaustive implementation plan.
Do not infer later-phase work or invent requirements to fill the review.

Discover blockers across those areas before stopping to draft stories. Create ALL
currently identifiable independent UDs and ARs in this pass, even after the first
blocker or after reaching the story batch limit. The six-story limit does not limit
the number of independently justified questions. Never speculate about a question
whose existence or options genuinely depend on an unanswered decision: record that
dependency and revisit it after the answer. A previously discoverable independent
question must not be deferred merely because another question is unanswered.

Before creating any artifact, check OPEN/NEEDS_USER/RESOLVED decisions and requests,
existing story coverage and the backlog. Reuse unresolved questions, consume resolved
answers, and reuse covered stories. A changed premise needing a new decision must
explicitly explain what changed; do not reopen an answered question by renaming it.

Create only the next 1?6 justified executable stories. Unknown product/architecture
choices stay in their UD/AR; do not create speculative implementation contracts.
Existing dependent stories remain blocked with explicit UD/AR/story IDs in their
Dependencies and Blockers, outside the executable queue until their prerequisites
are satisfied. An unfinished active story is a dependency only for work that actually
needs its result, not a reason to stop examining the milestone.

Result semantics:
- COMPLETE means this planning batch succeeded, not that the phase is complete.
  It may include open/new UDs, ARs and independent stories together.
- `independent_work_remaining` is true only if a READY area can be advanced by
  another bounded pass without waiting for a decision or story. Use COMPLETE and
  identify the remaining ready area(s). The current pass must have created real
  artifacts or resolved a PO request; prose-only churn cannot request another pass.
- NEEDS_USER means the review is finished and no further useful independent planning
  is available without user input (`independent_work_remaining = false`). It may
  retain independent stories created in this final batch. Never use it just because
  any UD exists. List all relevant OPEN UD IDs, not only the first encountered one.
- If only architecture/story prerequisites or already-covered work remain, use
  COMPLETE with `independent_work_remaining = false` and explain the local waits.
  The scheduler dispatches actionable ARs and executes eligible stories separately.

While Claude is unavailable, apply the same review and continue bounded independent
batches while justified. Once no independent planning remains, wait locally until
inputs change; do not repeatedly ask a model to rediscover unchanged blockers.

## Planning PROJECT HEALTH REVIEW tasks

Treat PROJECT HEALTH REVIEW as a special assessment task governed by `docs/TARGET_ARCHITECTURE.md` §34, not an implementation or cleanup batch. Near current-milestone exit, after implementation work is substantially complete, ensure its review is scheduled before closure. Reuse an existing review task when present (Phase 1: `STORY-QUALITY-001`, "Review Phase 1 project health before milestone completion"); do not duplicate it or schedule reviews after every story. Newly added roadmap phases must inherit the review exit requirement by reference to §34.

When planning such a task, keep explicit **Non-Goals** within its Constraints: no broad bug hunting, automatic broad regression testing, arbitrary test expansion, speculative cleanup/refactoring, or later-milestone architecture implementation. Planned future replacement alone is not evidence of current debt. Its Required Tests should describe evidence assessment and targeted checks for concrete questions, not mandate every suite. Preserve review first, remediation later.

Use review evidence and findings recorded through the existing story Result and workflow reporting mechanisms. Do not automatically populate the backlog or commission substantial remediation within the review. The review may finish with unresolved findings; it does not close the milestone. In a subsequent normal planning pass, assess concrete findings within phase locks and decide whether separately scoped normal stories are warranted. Transfers require an explicit destination/rationale and planner/user disposition, not silent deletion of exit criteria.

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

An architectural question belongs to the architect, not here: create an
architect request for it rather than a User Decision. A User Decision remains
correct for product/domain behavior, and the architect raises its own when a
question turns out to need the Product Owner.

When returning `NEEDS_USER`:
- create no speculative stories;
- complete all justified independent planning before returning; a decision blocks only dependent work;
- `story_files_created` may list independent stories (normal batch limits apply);
- `product_owner_requests_processed` may list independently resolved requests;
- leave dependent requests NEEDS_USER and identify their blocking decision;
- `PLANNING_RESULT.json.user_decision_ids` must contain stable OPEN IDs such as
  `UD-010`, never filenames or paths. Each resolves by filename to exactly one
  `UD-010-*.md` or `UD-010.md` under `agent/user-decisions/`. Markdown titles are
  optional and do not define identity; duplicate filename IDs are invalid.

Do not stop a planning pass at the first open decision. NEEDS_USER describes
a reviewed state with no further independent planning available without user input.
The runtime caches post-pass planning inputs and will not invoke the planner
again for unchanged NEEDS_USER state. Finish independent work in this pass.

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

Only report a milestone transition when the supplied current phase's exit criteria are fully and confidently satisfied, including its PROJECT HEALTH REVIEW under `docs/TARGET_ARCHITECTURE.md` §34. Review completion alone is insufficient: assess its recorded findings and confirm explicit disposition of blocking findings and criteria proposed for transfer before reporting the phase complete. Otherwise keep `phase_exit_criteria_satisfied` and `milestone_transition` false.

For Phase 3, the explicit Product Owner performance gate in `docs/ROADMAP.md` / `docs/TARGET_ARCHITECTURE.md` §33 overrides ordinary completion inference. Require the real-user-database measurements and explicit subsequent, dated user confirmation tied to the tested revision/results in `STORY-PERF-001`'s Result. Until both exist, keep Phase 3 open, `phase_exit_criteria_satisfied = false` and `milestone_transition = false`; do not plan/activate Phase 4 or later work, silently defer the requirement, or treat automated/evaluator success as user acceptance. Schedule the existing performance story as current-phase work; do not create a duplicate. If only user acceptance remains, use the existing human-input workflow rather than generating filler work or another implementation retry.

The planner never moves story files and never edits BACKLOG `## Archived`.

The harness performs archiving after validating the transition.

## Completion Contract

A prose plan is not completion.

The run is complete only after writing:

`agent/runtime/artifacts/PLANNING_RESULT.json`

Required fields:

```json
{
  "status": "COMPLETE | NEEDS_USER | FAILED",
  "phase_considered": "Phase N",
  "independent_work_remaining": false,
  "phase_review": [{"area": "remaining area", "outcome": "USER_DECISION", "references": ["UD-010"]}],
  "phase_exit_criteria_satisfied": false,
  "story_files_created": [],
  "user_decision_ids": [],
  "product_owner_requests_processed": [],
  "architect_requests_created": [],
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