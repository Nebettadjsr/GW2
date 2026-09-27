# Contributing to Nebet's GW2 Tool

Thanks for considering a contribution.

The project is under active development and is also used as an experiment in agentic software development. Contributions are welcome, but changes should preserve the documented domain behavior and architectural boundaries.

## Before changing code

For non-trivial changes, review the relevant project documentation first:

- `docs/DOMAIN_SPEC.md` — authoritative crafting/economy behavior
- `docs/TARGET_ARCHITECTURE.md` — intended architecture and dependency direction
- `docs/KNOWN_PROBLEMS.md` — currently known unresolved problems
- `docs/TEST_STRATEGY.md` — testing expectations
- `docs/CODING_GUIDELINES.md` — implementation conventions

If a proposed change intentionally alters a documented domain rule or architecture decision, describe that explicitly in the issue or pull request instead of silently changing behavior.

## Development setup

The backend is Java-based and uses Maven through the included Maven wrapper. PostgreSQL is required for integration/runtime behavior.

Typical backend verification:

```bash
./mvnw test
```

The browser frontend lives in `frontend/` and uses Vue 3, TypeScript, and Vite.

Typical frontend verification:

```bash
cd frontend
npm ci
npm test
npm run build
```

Some integration and browser smoke tests require a running backend, PostgreSQL, retained test data, or other setup. See the README and `docs/TEST_STRATEGY.md` before assuming every test can run in an isolated checkout.

## Issues

Before opening an issue:

1. Check whether a similar issue already exists.
2. Include enough information to reproduce a bug.
3. For behavior/calculation problems, include the relevant item, recipe, character scope, settings, or other inputs where possible.
4. Do not post Guild Wars 2 API keys, database credentials, or other credentials in issues or logs.

Feature requests should explain the user problem or use case, not only a proposed implementation.

## Pull requests

Keep pull requests focused on one coherent change.

A pull request should explain:

- what changed;
- why it changed;
- which tests or checks were run;
- whether domain behavior, persistence, API contracts, or architecture were affected;
- any known limitations or follow-up work.

Do not mix unrelated cleanup or refactoring into a behavioral fix unless the changes genuinely cannot be separated.

## Tests

Add or update tests when changing behavior.

Prefer:

- small deterministic unit tests for domain rules;
- PostgreSQL integration tests for persistence behavior;
- API tests for backend contracts;
- frontend tests for rendering and interaction rather than reimplementing backend calculations.

The repository's GitHub Actions CI is the authoritative full regression gate for pushed changes. Local development should still run the targeted tests needed to verify the code being changed.

## Guild Wars 2 and ArenaNet

This is an unofficial community project and is not affiliated with or endorsed by ArenaNet.

Avoid committing proprietary game assets or other material that the project does not have permission to redistribute. Use only assets and data that are permitted for the project's intended distribution.
