# GW2 repository guide

GW2 is a crafting and economy application. The active browser UI is Vue/TypeScript; the backend is Java 25, Maven, Spring Boot, PostgreSQL, and the Guild Wars 2 API. The intended hosted, containerized platform is still being built. Read only the documentation and code relevant to the issue at hand.

## Work from GitHub issues

An issue states the goal, acceptance criteria, constraints, and dependencies. Work on one issue per branch and pull request. Do not start an issue whose listed dependencies are unmerged. Link the issue in the PR. If a requirement needs a Product Owner decision, ask on the issue or PR and apply `needs-decision`; do not invent product behavior. Record a small, nonblocking improvement as a follow-up issue rather than expanding the current PR. An acceptance criterion or material correctness/security gap is blocking.

Implementing agents may write production code, tests, and fixtures together. Run the narrow relevant checks locally and let CI run the complete Maven and Vitest suites. In the PR, list checks actually run, local-only checks still needed, and the reason for any removed or weakened assertion. Respond to review findings on the same branch.

## Documentation ownership

- `docs/DOMAIN_SPEC.md`: product and domain behavior.
- `docs/crafting/GLOSSARY.md`: canonical player-facing crafting terms.
- `docs/CURRENT_STATE_SPEC.md` and `docs/CURRENT_ARCHITECTURE.md`: observed current behavior and structure.
- `docs/TARGET_ARCHITECTURE.md`: selected target architecture and open technical choices.
- `docs/ROADMAP.md`: phase sequence and exit criteria.
- `docs/FRONTEND_UX_GUIDELINES.md`: browser UX requirements.
- `docs/TEST_STRATEGY.md` and `docs/QUALITY_METRICS.md`: test layers, coverage, and reporting.
- `docs/PERFORMANCE_MEASUREMENTS.md`: reproducing and reading the informational application resource measurements.
- `docs/CODING_GUIDELINES.md`: implementation conventions.
- `agent/stories/` and `agent/user-decisions/`: historical story and decision records; active work is tracked in GitHub issues.

Read the owning document before changing its subject. Update current documentation when delivered behavior changes. Existing code proves current behavior, not intended behavior.

## Domain and architecture

The backend owns authoritative business logic. The frontend does not call the GW2 API directly, and secrets stay backend-only. Domain logic must not depend on JavaFX, HTTP, JSON transport models, PostgreSQL/JDBC/SQL, Docker, or GW2 API response models. External systems use clear adapters. PostgreSQL is a disposable cache of GW2 API data under UD-016; missing or inconsistent data is re-fetched. Ectoplasm Salvage has the explicit browser calculation exception in `docs/TARGET_ARCHITECTURE.md` section 4.2 and `docs/DOMAIN_SPEC.md`.

Do not silently choose a technology that `docs/TARGET_ARCHITECTURE.md` marks TBD. Preserve existing behavior unless the issue or authoritative spec changes it. Prefer incremental changes with clear responsibility boundaries.

## Checks and review

Use `docs/TEST_STRATEGY.md` to choose meaningful tests. Backend CI runs Maven; frontend CI runs Vitest, type checking, and build. Real-database `*IT`, browser smoke, and live GW2 API checks are local-only unless an issue explicitly adds a suitable CI environment. Never claim an unrun check passed.

Reviewers compare the PR with its issue, the owning docs, the test diff, and the actual CI result. They may accept a sound implementation that differs in minor details from the proposed approach when the acceptance criteria are met; record a small follow-up issue if useful. Request changes for unmet criteria, unsafe behavior, or inadequate verification.

Keep the PR focused and report observed facts separately from inferences and recommendations. Preserve unrelated maintainer changes. Stop processes you start before finishing.
