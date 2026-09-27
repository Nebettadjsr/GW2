# CLAUDE.md

Default guide for Claude Code. Default role: **IMPLEMENTATION MODE** (defined below).
Project planning lives entirely in `agent/PLANNER_INSTRUCTIONS.md` — switch to it only if
PROJECT PLANNING MODE is explicitly requested.

Keep context small: read only task-relevant files, prefer targeted inspection over full-file
reads, don't reopen files that haven't changed.

## Project

GW2 crafting/economy tool. Current stack: Java 25, Maven, JavaFX, PostgreSQL, GW2 API.
Target direction: containerized web app, separate frontend/backend/PostgreSQL — **not yet
implemented**. Frontend and backend frameworks are undecided; do not introduce either unless
explicitly requested.

## Documentation Map

Each file below is the **sole owner** of its content — read it when the task touches that
topic, update it only when the task materially changes information it owns. Never duplicate
one file's facts into another.

| File | Owns |
| --- | --- |
| `docs/DOMAIN_SPEC.md` | Authoritative domain rules |
| `docs/CURRENT_STATE_SPEC.md` | Descriptive current behavior |
| `docs/CURRENT_ARCHITECTURE.md` | Current structure/runtime architecture |
| `docs/TARGET_ARCHITECTURE.md` | Intended architecture, TBD technologies |
| `docs/TEST_STRATEGY.md` | Testing methodology/layers (never live test counts) |
| `docs/ROADMAP.md` | Phases, dependencies, exit criteria |
| `docs/KNOWN_PROBLEMS.md` | Defects, conflicts, risks, technical debt |
| `docs/CODING_GUIDELINES.md` | Behavioral/coding standards — read before non-trivial work |
| `docs/crafting/` | Human-readable crafting guide; policy owned by `TARGET_ARCHITECTURE.md` §35 |
| `agent/stories/BACKLOG.md` | Executable queue, priority, status (index only, not story content) |
| `agent/stories/STORY-*.md` | Scope, acceptance criteria, result, blockers (one canonical file per story) |
| `agent/user-decisions/UD-*.md` | Human decisions, OPEN/RESOLVED status |
| `agent/PROJECT_STATE.md` | Planner-only continuity state — implementation must not write here |

If information is derivable from its owner, don't store a second copy anywhere else.

## Roles

- **IMPLEMENTATION MODE** — default, defined below.
- **PROJECT PLANNING MODE** — `agent/PLANNER_INSTRUCTIONS.md`, only when explicitly requested.
- **PROJECT HEALTH REVIEW** — bounded assessment, scope/rules in `TARGET_ARCHITECTURE.md` §34.
  Report findings via the normal story/result mechanisms; don't treat it as license to fix
  things or expand tests beyond that scope.

## Story System

- `agent/CURRENT_STORY.md` points to the active story; `agent/stories/` holds canonical story
  files; `agent/stories/archive/` holds archived milestones.
- `agent/runtime/artifacts/CLAUDE_RESULT.md` is your implementation result output.
- **Never select or activate the next story.** Selection is deterministic and Python-owned.
- Do not modify `CURRENT_STORY.md`.

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

**After:** Update the story file's status/result, `CLAUDE_RESULT.md`, and that story's single
BACKLOG entry. Include the same `## Follow-up Findings` section in the story and result so the
planner can disposition out-of-scope observations during its next normal pass. Update another
doc only if this story materially changed information that doc
owns (see Documentation Map) — e.g. an architecture change updates `CURRENT_ARCHITECTURE.md`,
a domain change updates `DOMAIN_SPEC.md`, a user-visible crafting rule change updates
`docs/crafting/` per §35. Never write completed-story history or live test counts into
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

Domain behavior changes need automated tests. Bug fix: reproduce with a test where practical,
make the smallest fix, run the tests covering it. Refactor: cover with tests, refactor,
verify unchanged behavior. Don't weaken or delete tests to force a pass unless the spec
changed. Match test scope to the change — a non-UI logic change doesn't need JavaFX/TestFX
coverage just for completeness. See `TEST_STRATEGY.md` §31.5/§33 and `CODING_GUIDELINES.md`
for methodology and effort calibration.

**Run only the tests that cover what you are changing** — one test, one class, at most the
directly related suite. Do not run the full backend/frontend/harness suite locally: GitHub
Actions is the authoritative full-regression gate and runs it on the pushed commit
(`TEST_STRATEGY.md` §20, §36). Report the exact commands you ran and their real output.
Exception: a change with genuinely unbounded blast radius (shared fixture, build/dependency,
repository-wide rename) is worth a broad local run before it is pushed.

Layers the gate cannot run stay local and explicit when a story touches them: TestFX UI
verification (§32, needs a real desktop), browser smoke scripts (§12.2), real-database `*IT`
checks (§34, §35.2) and live GW2 API smoke (§31.4).

## Version Control and CI

The orchestrator commits and pushes a story once the evaluator accepts it, then waits for
that commit's CI result. Don't commit or push yourself unless asked — see
`CODING_GUIDELINES.md` §8. If CI fails you get the failing tests back as a short report:
reproduce them with the narrowest command, fix the cause, and stop; the harness re-pushes
and re-runs the gate. CI runs on Linux against an empty PostgreSQL database and a
placeholder GW2 API key, so a test that needs your machine's data, display or network is
itself the defect.

## Working Habits

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