# CLAUDE.md

Default guide for Claude Code. Default role: **IMPLEMENTATION MODE** (defined below).
Project planning lives entirely in `agent/PLANNER_INSTRUCTIONS.md` — switch to it only if
PROJECT PLANNING MODE is explicitly requested.

Keep context small: read only task-relevant files, prefer targeted inspection over full-file
reads, don't reopen files that haven't changed.

## Project

GW2 crafting/economy tool. Current stack: Java 25, Maven, Spring Boot 4.1.1, Vue 3/TypeScript,
PostgreSQL and the GW2 API. The browser UI is active; the JavaFX UI remains as legacy code.
The hosted, containerized multi-user platform is not implemented. Chosen target technologies and
remaining TBDs are in `docs/TARGET_ARCHITECTURE.md`.

## Documentation Map

Each file below is the **sole owner** of its content — read it when the task touches that
topic, update it only when the task materially changes information it owns. Never duplicate
one file's facts into another.

| File | Owns |
| --- | --- |
| `docs/DOMAIN_SPEC.md` | Authoritative domain rules |
| `docs/crafting/GLOSSARY.md` | Canonical player-facing crafting/Trading Post terms and UI-label mapping |
| `docs/CURRENT_STATE_SPEC.md` | Descriptive current behavior |
| `docs/CURRENT_ARCHITECTURE.md` | Current structure/runtime architecture |
| `docs/TARGET_ARCHITECTURE.md` | Chosen target architecture and remaining decisions |
| `docs/FRONTEND_UX_GUIDELINES.md` | Reusable browser UX/UI requirements |
| `docs/CRAFTING_STATUS_LABEL_REFERENCE.md` | Developer reference interpreting crafting status/blocking codes (derived from `DOMAIN_SPEC.md`, not authoritative) |
| `docs/TEST_STRATEGY.md` | Testing methodology, quality requirements, and test layers (never live test counts) |
| `docs/QUALITY_METRICS.md` | Coverage KPI, baseline, and reporting ownership |
| `docs/bugs/` | Permanent confirmed-defect registry and reproduction fixture guidance |
| `docs/ROADMAP.md` | Phases, dependencies, exit criteria |
| `docs/KNOWN_PROBLEMS.md` | Defects, conflicts, risks, technical debt |
| `docs/CODING_GUIDELINES.md` | Behavioral/coding standards — read before non-trivial work |
| `docs/crafting/README.md` | Human-readable crafting guide, aligned with `DOMAIN_SPEC.md` |
| `agent/stories/BACKLOG.md` | Executable queue, priority, status (index only, not story content) |
| `agent/stories/STORY-*.md` | Scope, acceptance criteria, result, blockers (one canonical file per story) |
| `agent/user-decisions/UD-*.md` | Human decisions, OPEN/RESOLVED status |
| `agent/PROJECT_STATE.md` | Planner-only continuity state — implementation must not write here |

If information is derivable from its owner, don't store a second copy anywhere else.

## Roles

- **IMPLEMENTATION MODE** — default, defined below.
- **PROJECT PLANNING MODE** — `agent/PLANNER_INSTRUCTIONS.md`, only when explicitly requested.
- **PROJECT HEALTH REVIEW** — bounded assessment, scope/rules in `TARGET_ARCHITECTURE.md` §21.
  Report findings via the normal story/result mechanisms; don't treat it as license to fix
  things or expand tests beyond that scope.

## Story System

- `agent/CURRENT_STORY.md` points to the active story; `agent/stories/` holds canonical story
  files; `agent/stories/archive/` holds archived milestones.
- `agent/runtime/artifacts/CLAUDE_RESULT.md` is your implementation result output.
- **Never select or activate the next story.** Selection is deterministic and Python-owned.
- Do not modify `CURRENT_STORY.md`.
- Do not edit `agent/stories/BACKLOG.md`. Moving a story's entry between sections, setting the
  Status a completion or block implies, and clearing the active-story pointer are harness
  transitions, performed deterministically in Python. Your job is the active story's own file
  (Result, Follow-up Findings) and `CLAUDE_RESULT.md`; the harness alone sets the story's Status.
- Write permissions for every role are defined once in `agent/runtime/support/config.py`
  (`DOCUMENT_OWNERSHIP`) and rendered into each prompt from there; the Documentation Map above
  says what belongs in each document.

## IMPLEMENTATION MODE

**Before:** Read `CURRENT_STORY.md` → open the referenced story → treat it as the authoritative
work order → read only its listed docs → inspect only relevant code, callers/callees, tests.
If the story is DONE or BLOCKED, report and stop.

**During:** Work only to the story's Definition of Done. No unrelated refactoring, no scope
creep. Preserve existing behavior unless the story/spec says otherwise. If something material
is unspecified or ambiguous, stop and report — don't guess. Do not create or add stories to the
backlog. If implementation reveals a concise, useful issue outside this story's scope, record
it in `## Follow-up Findings` in both the completed story and `CLAUDE_RESULT.md` as
`F001: <finding>`, incrementing the ID for each additional finding. Leave both sections as
`None.` when there are no findings. Do not include work already covered by this story.

**After:** Update the story file's Result and `CLAUDE_RESULT.md`. Leave the story's Status,
`BACKLOG.md` and `CURRENT_STORY.md` alone; the harness performs those transitions. Include the same `## Follow-up Findings` section in the story and result so the
planner can disposition out-of-scope observations during its next normal pass. Update another
doc only if this story materially changed information that doc
owns (see Documentation Map) — e.g. an architecture change updates `CURRENT_ARCHITECTURE.md`,
a domain change updates `DOMAIN_SPEC.md`, a user-visible crafting rule change updates
`docs/crafting/` per §18. Never write completed-story history or live test counts into
`PROJECT_STATE.md` or `TEST_STRATEGY.md`.

## Source Priority

When information conflicts, in order: explicit current user instruction → active story
scope/acceptance criteria → `DOMAIN_SPEC.md` → `TARGET_ARCHITECTURE.md` → other task-specific
authoritative spec → existing implementation → README/comments. Existing code proves current
behavior, not desired behavior.

## Domain & Architecture

Never invent or reinterpret a domain rule for convenience — if code conflicts with
`DOMAIN_SPEC.md`, report the conflict before changing behavior.

Target layering: UI/transport → application use cases → domain → infrastructure. Domain code
must not depend on JavaFX, frontend frameworks, HTTP/REST, JSON transport models,
PostgreSQL/JDBC/SQL, Docker, or GW2 API response models. Business rules live in
backend/domain; external systems get clear adapters. PostgreSQL is the target database.

## GW2 API / Security

Backend owns GW2 API access — frontend must never call it directly or receive API
keys/DB credentials. Runtime config must not be hard-coded. Accepted, closed decisions (do
not re-raise): the read-only GW2 API key is not an account-takeover credential; the local
PostgreSQL password is not a material security finding.

## Testing

Before changing behavior, review the applicable business rules and cross-component invariants.
Add meaningful tests for new or modified behavior; confirmed bug fixes require a regression test
based on the observed failure and expected requirement. Verify complete calculation results and
relevant integration points when behavior crosses components. Expected values must be independently
established, not copied from the implementation under test. Use jqwik properties for generated
recursive, allocation, boundary, shared-dependency, and settings-combination cases where useful;
record failing seeds for reproduction. Consult the bug registry lifecycle when a production defect
is confirmed. Do not weaken tests to force a pass unless requirements changed.

Before implementation, read the persistent plan in `agent/qa-plans/` and follow
its test specifications and expected results. Do not edit, remove, skip or weaken
QA-owned tests or the plan. If an expectation is wrong, preserve it and document
the evidence for independent review. Add implementation-specific tests as
needed, and report the exact commands/results for prepared tests.

Run applicable tests and builds, inspect line/branch coverage in affected modules/packages, and
report unresolved quality gaps. Aim for at least 90% in affected areas; the project-wide 90% KPI
is initially a CI warning, not a build gate. Never pad tests or manipulate measurement scope.
Infrastructure-only stories may omit application-test changes when explicitly constrained, but
must still verify the coverage/reporting tools and existing suites. See `TEST_STRATEGY.md` §26
and `CODING_GUIDELINES.md` §6.1 for methodology.

**Run only the tests that cover what you are changing** — one test, one class, at most the
directly related suite. Do not run the full backend/frontend/harness suite locally: GitHub
Actions is the authoritative full-regression gate and runs it on the pushed commit
(`TEST_STRATEGY.md` §20, §36). Report the exact commands you ran and their real output.
Exception: a change with genuinely unbounded blast radius (shared fixture, build/dependency,
repository-wide rename) is worth a broad local run before it is pushed.

Layers the gate cannot run stay local and explicit when a story touches them: TestFX UI
verification (§32, needs a real desktop), browser smoke scripts (§12.2), real-database `*IT`
checks (§34, §18.2) and live GW2 API smoke (§31.4).

## Version Control and CI

The n8n development pipeline (`local_bridge/README.md`) commits and pushes a story once the
evaluator accepts it, then waits for that commit's CI result. Don't commit or push yourself unless asked — see
`CODING_GUIDELINES.md` §8. If CI fails you get the failing tests back as a short report:
reproduce them with the narrowest command, fix the cause, and stop; the harness re-pushes
and re-runs the gate. CI runs on Linux against an empty PostgreSQL database and a
placeholder GW2 API key, so a test that needs your machine's data, display or network is
itself the defect.

## Working Habits

Stop every process you start (dev servers, backends, watchers) before you finish, and stop it by
the PID you started — never by a name or command-line filter, which can hit the maintainer's own
tools. The pipeline treats your final answer as the end of the run and stops anything you left
running.

Start exploration from named files, direct callers/callees, and relevant tests; expand only
as needed. For large files, inspect relevant sections rather than the whole file. Don't
modify or commit generated/dependency files (third-party JARs, caches, build output, IDE
metadata) unless explicitly required. Don't touch adjacent code, comments, or formatting you
weren't asked to change — see `CODING_GUIDELINES.md` for the full surgical-changes standard.

## Lessons Learned

After any correction, append the pattern to `tasks/lessons.md` as a rule that prevents it
recurring. Read `tasks/lessons.md` at the start of any session working on this project.

## Coding Guidelines

`docs/CODING_GUIDELINES.md` holds the behavioral standards for how to work, not what to
build: thinking before coding, simplicity-first, surgical changes, goal-driven/verifiable
execution, and planning/subagent workflow mechanics. Read it before starting non-trivial
implementation work (3+ steps or an architectural decision).

## Communication

**Implementation report:** what changed, why, tests run, remaining uncertainty.
**Analysis report:** observed fact, inference, recommendation — never present inference as fact.

## Default Behavior

If the path is clear, proceed without unnecessary clarification. If the work requires
deciding an undecided domain/architecture rule, stop and ask. Keep changes small, reviewable,
and testable.

### Human maintainer changes

The repository is also actively modified by the human maintainer outside the agent workflow.

Treat any code or working-tree changes that were not created by you in the current invocation as intentional maintainer work and as part of the current repository state.

* Do not revert, restore, overwrite, or "correct" such changes merely because they differ from an earlier implementation, an expected repository state, or how you would have implemented them.
* Integrate the active story with the code as it currently exists. Preserve unrelated maintainer changes.
* If a maintainer change overlaps with your story, modify only what is necessary to satisfy the story while preserving the apparent intent of the existing change.
* If a maintainer change appears buggy, inconsistent, or architecturally questionable, you may report it, but do not undo it unless the active story or an authoritative project requirement explicitly requires that change.
* Never use `git restore`, `git checkout`, `git reset`, or broad file replacement to remove changes you did not create.
* If a file changes after you previously inspected it, re-read it before editing and merge with the new state rather than writing from a stale copy.

The human maintainer is an authoritative collaborator, not repository drift that needs to be cleaned up.
