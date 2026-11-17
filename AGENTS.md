# Repository instructions

GW2 develops through GitHub issues, branches, PRs, CI and native Codex review.
Select current work from GitHub. [Development workflow](docs/DEVELOPMENT_WORKFLOW.md)
is the detailed process reference; [agent/](agent/README.md) routes current roles.
Historical artifacts are under [docs/archive/](docs/archive/README.md); the
retired executable Python harness is recoverable from tag `pre-github-flow`.

## Role routing

- Interactive Codex supervisor: [supervisor contract](agent/SUPERVISOR_INSTRUCTIONS.md).
- Explicitly assigned Codex planner: [planner contract](agent/PLANNER_INSTRUCTIONS.md).
  Planning does not include implementation or starting Claude.
- Claude programmer: [CLAUDE.md](CLAUDE.md) and the approved issue contract.
- Native Codex PR review follows the Code Review Rules below. Review is not
  a planning invocation.

`docs/ROADMAP.md` owns product direction; `agent/BACKLOG.md` owns draft order,
authorization and handoffs. GitHub issues/PRs own execution evidence.

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

## Authoritative documents

- `docs/DOMAIN_SPEC.md`: domain behavior.
- `docs/crafting/GLOSSARY.md`: player-facing crafting terms.
- `docs/CURRENT_STATE_SPEC.md`: observed runtime/build/deployment facts.
- `docs/ARCHITECTURE.md`: current structure, selected boundaries and open choices.
- `docs/ROADMAP.md`: product direction and exit criteria.
- `docs/TEST_STRATEGY.md`, `docs/QUALITY_METRICS.md`,
  `docs/FRONTEND_UX_GUIDELINES.md`, `docs/CODING_GUIDELINES.md`: respective
  verification, coverage, UX and implementation conventions.
- `docs/DEVELOPMENT_WORKFLOW.md`: implemented development process.
- `docs/archive/agent/user-decisions/` and
  `docs/archive/architecture/decisions/`: historical decision rationale.

Distinguish observed facts, inferences and recommendations. Existing code proves
current behavior, not intended behavior. Avoid duplicating authoritative rules.

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

CI runs Maven/Vitest, type/build and workflow checks. Default CI includes isolated
database-backed `*Test` cases; `*IT`, browser smoke and live API checks need
explicit applicable evidence when the issue depends on them. Explain removed or
weakened assertions. Never invent test results.

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
