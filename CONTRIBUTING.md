# Contributing to GW2 Crafting Tool

Contributions and concrete improvement suggestions are welcome. The project is
a live community tool and an experiment in agent-assisted development.

## Start with the issue and owning docs

For a substantial change, agree on its problem/acceptance criteria in an issue.
Read the relevant [domain rules](docs/DOMAIN_SPEC.md),
[architecture](docs/ARCHITECTURE.md), [known problems](docs/KNOWN_PROBLEMS.md),
[test strategy](docs/TEST_STRATEGY.md) and
[coding conventions](docs/CODING_GUIDELINES.md). Existing code alone does not
authorize a new product rule.

[Development workflow](docs/DEVELOPMENT_WORKFLOW.md) explains the complete
issue/agent/branch/PR/CI/review process, including what is automatic and what
requires an explicit session. Human contributions use the same acceptance and
review standards; they do not need to run Claude.

## Development checks

The backend uses Java 25 and the included Maven wrapper. From `backend/`:

```bash
./mvnw test
```

On Windows, use `.\mvnw.cmd test`. Some `*Test` cases require isolated
PostgreSQL; default CI supplies PostgreSQL 17. Never point test schemas at
production. Explicit `*IT`, live GW2 API and browser checks have additional
setup described in the Test strategy.

The browser frontend uses Vue 3, TypeScript, Vite and Node 22. From `frontend/`:

```bash
npm ci
npm test
npm run build
```

The build includes type-checking. Run focused checks for affected behavior
locally; GitHub CI provides the complete default regression run. Coverage is a
warning KPI, not proof of correctness or a reason to pad tests.

## Issues and pull requests

Before opening an issue, check for existing work. Give reproducible steps and,
for calculation bugs, the relevant item/recipe/settings/scope. Feature requests
should explain the user's problem. Do not publish API keys or credentials.

One coherent issue belongs on one branch and PR. Wait for its dependencies.
Explain intentional domain/API/architecture changes rather than silently changing
behavior. A PR should include:

- The issue link, what changed and why.
- Acceptance-criteria evidence.
- Tests added/changed and exact checks/results.
- Any removed/weakened assertions, with reasons and replacement evidence.
- Local database/browser/live checks still needed.
- Known gaps and focused follow-up work.

Implement meaningful regression and boundary tests with behavior changes.
Prefer domain units, real-PostgreSQL persistence checks, API contracts and actual
frontend interactions over reproducing calculations in frontend tests.

Review compares issue, owning docs, actual diff, tests and current CI.
Material defects/unmet criteria block acceptance. Sound minor differences from
a proposed implementation can be accepted; small nonblocking improvements can
be separate issues. New UI layouts need the owner's local visual acceptance
unless that concrete scope is already approved for publication.

Keep unrelated cleanup out of a behavior fix, preserve maintainer edits and stop
processes you start. Production release is separate from merging.

## ArenaNet and Guild Wars 2

This is an unofficial community project, unaffiliated with and not endorsed by
ArenaNet. Respect applicable asset/data permissions; do not commit material the
project is not allowed to redistribute.
