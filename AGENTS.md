# Repository instructions

These shared instructions apply to every agent, including Claude, planners,
supervisors, QA and reviewers. Read them before the role-specific instructions.
GW2 develops through GitHub issues, branches, PRs, CI and native Codex review.
[Development workflow](agent/DEVELOPMENT_WORKFLOW.md) describes that process;
[agent/](agent/README.md) routes current roles.

## Role routing

- Interactive Codex supervisor: [supervisor contract](agent/SUPERVISOR_INSTRUCTIONS.md).
- Explicitly assigned Codex planner: [planner contract](agent/PLANNER_INSTRUCTIONS.md).
  Planning does not include implementation or starting Claude.
- Claude programmer: [CLAUDE.md](CLAUDE.md) and the approved issue contract.
- Native Codex PR review follows the Code Review Rules below. Review is not
  a planning invocation.

The owner's current request and approved GitHub issues define work.
`agent/BACKLOG.md` is only a short reminder of unfinished longer-term goals.
GitHub issues/PRs own execution state and evidence.

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
- `docs/TEST_STRATEGY.md`: testing methods and coverage policy.
- `docs/FRONTEND_UX_GUIDELINES.md`: browser UX conventions.
- `docs/CODING_GUIDELINES.md`: implementation conventions.
- `docs/KNOWN_PROBLEMS.md`: unresolved defects and concrete improvement opportunities.
- `agent/DEVELOPMENT_WORKFLOW.md`: implemented agent/GitHub process.
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

CI runs Maven/Vitest, type/build, workflow checks and the curated controlled
browser journeys. Default CI includes isolated database-backed `*Test` cases;
`*IT`, live API and real-backend browser checks need their explicit setup/evidence.
Explain removed or weakened assertions. Never invent test results.

Preserve unrelated maintainer edits, keep diffs focused and stop owned processes.
Return deliverables to the main project. Use ignored output/OS temporary storage
for disposable logs/builds; clean up completed temporary worktrees. Preserve
evidence needed by a report without retaining unrelated scratch output.

## Code Review Rules

- Compare the PR with its linked issue and owning product docs. Flag missing
  criteria that affect observable behavior; cite the criterion and conflicting
  code/test.
- Flag removed, skipped or weakened assertions when required behavior is left
  unverified without a sound reason or replacement evidence.
- Flag new frontend calculation of authoritative domain results, direct frontend
  GW2 API calls or exposed backend secrets, except the documented Ectoplasm
  Salvage calculation.

Repository files are UTF-8. On Windows use `apply_patch` for non-ASCII edits;
PowerShell 5.1 may corrupt characters piped into another program.
