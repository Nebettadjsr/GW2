# Repository instructions

These shared instructions apply to every agent, including Claude, planners,
supervisors, QA and reviewers. Read them before the role-specific instructions.
GW2 develops through GitHub issues, branches, PRs, CI and native Codex review.
[Development workflow](agent/DEVELOPMENT_WORKFLOW.md) describes that process;
[agent/](agent/README.md) routes current roles.

## Role routing

- Supervisor (the interactive session with the owner): [supervisor contract](agent/SUPERVISOR_INSTRUCTIONS.md).
- Planner (explicitly assigned): [planner contract](agent/PLANNER_INSTRUCTIONS.md).
  Planning does not include implementation or starting the programmer.
- Programmer (Claude in GitHub Actions): [CLAUDE.md](CLAUDE.md) and the approved issue contract.
- Reviewer (native Codex PR review) follows the Code Review Rules below. Review is not
  a planning invocation.

Roles are responsibilities, not models; the current assignment is in [agent/README.md](agent/README.md).

The owner's current request and approved GitHub issues define work.
The pinned GitHub issue "Backlog: unfinished owner goals" (#398) is only a short reminder of
unfinished longer-term goals. GitHub issues/PRs own execution state and evidence.

## Scope and the harness boundary

The programmer (Claude) and the planner work only on the GW2 application and its application
documentation. The harness is not part of it: `.github/`, `agent/`, `tasks/`, `.claude/`,
`AGENTS.md`, `CLAUDE.md`, the rule documents (`docs/CODING_GUIDELINES.md`, `docs/TEST_STRATEGY.md`,
`docs/FRONTEND_UX_GUIDELINES.md`) and the quality-gate configuration (`backend/config/`, the
ArchUnit tests and violation store, `frontend/eslint.config.js`). Read the rule documents and follow
them; propose a change through `## Harness feedback`, never edit them. Programmer and planner never change
harness files and never plan or write stories about them. The programmer has no need to read
`agent/` or `.github/`; the planner reads its own contract but does not change it.

The boundary is enforced in code (`.github/scripts/harness_paths.py`): changes to these paths made
by a story run are discarded and listed in the PR, such a PR is not merged automatically, and the
merge gate treats dependency manifests (`backend/pom.xml`, `frontend/package*.json`) as supervised
as well.

The harness is improved only by the owner or a supervisor session. Programmer and planner report
proposals instead of fixing: the run report's `## Harness feedback` section (what was wrong,
missing or unclear, and what they would change, or `none`) becomes an issue labelled
`harness-feedback`. The planner ignores issues labelled `harness-feedback` and `docs-drift`.
Supervisors correct the harness when the same deviation occurs twice, one deviation is costly,
review findings recur or a drift finding confirms it. Prefer a deterministic check over prose,
make each correction its own pull request with the evidence, and remove rules that no longer
earn their place.

## Work contract

- Read the assigned issue, dependencies and relevant owning docs/source. One
  issue gets one branch and PR. Do not implement before dependencies merge.
- Normally implement code and meaningful tests together. A scoped approved issue
  or session may assign independent QA to tests/fixtures; honor that override.
  Run narrow checks and report exact results; CI runs complete default suites.
- Issue acceptance criteria and owning product docs govern behavior. Ask genuine
  Product Owner questions in the issue/PR using `needs-decision`; never invent
  product rules.
- Accept sound minor implementation differences when all criteria hold. Record
  a small nonblocking correction as follow-up work. Missing criteria/material
  defects block acceptance.
- Keep live state in GitHub. Do not edit historical status fields or recreate
  an executable file-based story/decision queue.

## Owning documentation

This is the shared, binding ownership map. Role files must reference it rather
than maintain another copy. Read the relevant owner before changing its subject;
update it when a delivered change affects its lasting rules or instructions.

- `docs/DOMAIN_SPEC.md`: domain behavior.
- `docs/crafting/GLOSSARY.md`: player-facing crafting terms.
- `docs/CURRENT_STATE_SPEC.md`: observed runtime/build/deployment facts.
- `docs/ARCHITECTURE.md`: current structure, selected boundaries and open choices.
- `docs/TEST_STRATEGY.md`: testing methods and coverage policy (rule document, harness).
- `docs/FRONTEND_UX_GUIDELINES.md`: reusable browser UX rules (rule document, harness).
- `docs/UI_SPEC.md`: page-specific browser behavior and accepted layouts.
- `docs/CODING_GUIDELINES.md`: implementation conventions (rule document, harness).
- `docs/KNOWN_PROBLEMS.md`: unresolved defects and concrete improvement opportunities.
- `agent/DEVELOPMENT_WORKFLOW.md`: implemented agent/GitHub process.
- `agent/VIDEO_COMPARISON.md`: how that process relates to the reference workflow, and the open improvements.
- `docs/archive/agent/user-decisions/` and
  `docs/archive/architecture/decisions/`: historical decision rationale.

Distinguish observed facts, inferences and recommendations. Existing code proves
current behavior, not intended behavior. Avoid duplicating authoritative rules.

## Keep the repository useful

- `docs/` holds application documentation; agent instructions/process belong in
  `agent/` and the root instruction files.
- Update an existing owning document. Create a file only when useful lasting
  information has no suitable home; a task, test run or small decision alone
  does not justify another document.
- Keep test counts, coverage snapshots, release receipts, acceptance histories
  and task reports in GitHub/CI or ignored local output. Documentation explains
  current behavior and how to work with it.
- Keep the backlog short: unfinished goals and issue links, without completed
  work, authorization transcripts or a second issue tracker.
- Remove obsolete references when files move or disappear. Do not recreate the
  removed roadmap, metrics ledger or runbooks as replacement documents elsewhere.

## Architecture constraints

The backend owns authoritative business logic and GW2 API access; the frontend
does not call that API directly. Deployment secrets remain backend-only.
Read-only browser-held GW2 keys follow the current Architecture account/session
rules (historical UD-018): ordinary retention, account separation and basic
hygiene, without high-sensitivity credential infrastructure.

Domain logic must not depend on JavaFX, HTTP, JSON transport models,
PostgreSQL/JDBC/SQL, Docker or GW2 API response models. External systems belong
behind adapters. PostgreSQL is a disposable cache: lost/inconsistent data is
re-fetched (historical UD-016). Ectoplasm Salvage is the explicit documented
browser-calculation exception. Do not silently settle an unresolved technology
choice. Prefer incremental changes and clear, simple responsibility boundaries.

## Verification and repository care

CI runs Maven (with the quality gates) and Vitest, type/build, workflow checks, the curated
controlled browser journeys and a container smoke, plus a bounded public API compatibility
check. Default CI includes isolated database-backed `*Test` cases; `*IT`, other live API
checks and real-backend browser checks need their explicit setup/evidence.
Explain removed or weakened assertions. Never invent test results.

Preserve unrelated maintainer edits, keep diffs focused and stop owned processes.
Return deliverables to the main project. Use ignored output/OS temporary storage
for disposable logs/builds; clean up completed temporary worktrees. Preserve
evidence needed by a report without retaining unrelated scratch output.

## Automated quality gates

Architecture and code rules are enforced by tools, not only by review. They run in the
default `./mvnw test` (Checkstyle, Error Prone, ArchUnit) and `npm run lint` (ESLint) and
in CI. Rule sources: `backend/config/checkstyle/`, `backend/src/test/java/architecture/`,
`frontend/eslint.config.js`; explanation in `docs/CODING_GUIDELINES.md` section 11.
Fix the code, never the gate: do not edit those files, extend the ArchUnit violation store,
add suppressions or loosen the ESLint configuration (rules, severities, `--max-warnings`) to make a change pass. Such changes
are harness paths (see Scope and the harness boundary): propose a change, never make it.

## Code Review Rules

- Compare the PR with its linked issue and owning product docs. Flag missing
  criteria that affect observable behavior; cite the criterion and conflicting
  code/test.
- Flag removed, skipped or weakened assertions when required behavior is left
  unverified without a sound reason or replacement evidence.
- Flag any change that weakens a quality gate (see Automated quality gates) or extends its
  recorded violations without an approved reason in the issue.
- Flag new frontend calculation of authoritative domain results, direct frontend
  GW2 API calls or exposed backend secrets, except the documented Ectoplasm
  Salvage calculation.

Repository files are UTF-8. On Windows use `apply_patch` for non-ASCII edits;
PowerShell 5.1 may corrupt characters piped into another program.
