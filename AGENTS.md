# Repository instructions

GW2 uses GitHub issues, branches, pull requests, CI, and Codex review for development. The previous Python story harness is preserved by the `pre-github-flow` tag. Select current work from GitHub issues.

## Work contract

- Read the linked issue, its dependencies, and only the relevant authoritative docs and code. One issue gets one branch and PR. Do not implement an issue before its dependencies are merged.
- Implement code and meaningful tests together. The old separation between QA and coding agents no longer applies. Run narrow local checks, report exact results, and let CI run the full default suites.
- An issue's acceptance criteria and owning product documents govern behavior. Ask for a Product Owner decision in the issue or PR with `needs-decision` when requirements are genuinely open. Do not invent a product rule.
- A reviewer may accept an implementation that differs slightly from the proposed approach if every acceptance criterion is met and the result is sound. Create a follow-up issue for a small, nonblocking correction. A missing criterion or material defect blocks merging.
- Do not edit historical story status, backlog state, or runtime artifacts to represent GitHub progress. GitHub issues, PRs, and CI are the live state.

## Authoritative documents

`docs/DOMAIN_SPEC.md` owns domain behavior; `docs/crafting/GLOSSARY.md` owns player-facing terms. `docs/CURRENT_STATE_SPEC.md` and `docs/CURRENT_ARCHITECTURE.md` describe what exists. `docs/TARGET_ARCHITECTURE.md` records selected target architecture and unresolved choices. `docs/ROADMAP.md` owns phases and exit criteria. `docs/TEST_STRATEGY.md`, `docs/QUALITY_METRICS.md`, `docs/FRONTEND_UX_GUIDELINES.md`, and `docs/CODING_GUIDELINES.md` own their respective methods and conventions. Historical decisions are under `agent/user-decisions/` and `docs/architecture/decisions/`.

Distinguish observed fact, inference, and recommendation. Current implementation is evidence of current behavior, not intended behavior. Do not duplicate authoritative information across documents.

## Architecture constraints

The backend owns authoritative business logic and GW2 API access. The frontend does not call the GW2 API directly. Server deployment secrets remain backend-only; browser-held read-only GW2 keys follow UD-018 and `docs/TARGET_ARCHITECTURE.md` section 8.2. Treat those keys proportionately: ordinary retention and account separation, without adding high-sensitivity credential infrastructure. Domain logic must not depend on JavaFX, HTTP, JSON transport models, PostgreSQL/JDBC/SQL, Docker, or GW2 API response models. External systems belong behind clear boundaries. PostgreSQL is a disposable cache of GW2 API data (UD-016); lost or inconsistent data is re-fetched. Ectoplasm Salvage is the explicit browser calculation exception documented in `docs/TARGET_ARCHITECTURE.md` section 4.2. Do not silently settle a target technology marked TBD. Prefer incremental changes and the simplest design that preserves correctness and testability.

## Verification and repository care

CI runs the Maven and Vitest suites. Real-database `*IT`, browser smoke, and live GW2 API checks require explicit local evidence when an issue depends on them. Explain removed or weakened assertions in the PR. Never invent test results. Preserve unrelated maintainer edits, keep diffs focused, and stop any process you start.

## Code Review Rules

- Compare a PR with its linked issue and the owning product documents. Flag a missing acceptance criterion when it changes observable behavior; cite the criterion and the conflicting code or test.
- Flag removed, skipped, or weakened assertions when they leave required behavior unverified and the PR gives no sound reason or replacement evidence.
- Flag new frontend calculations of authoritative domain results, direct frontend calls to the Guild Wars 2 API, or exposed backend secrets. The backend owns those responsibilities, except for the documented Ectoplasm Salvage calculation.

Repository files are UTF-8. On Windows, use `apply_patch` for edits containing non-ASCII text; PowerShell 5.1 may corrupt characters piped into another program.
