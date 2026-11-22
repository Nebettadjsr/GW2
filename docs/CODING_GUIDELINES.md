# Coding Guidelines

Binding implementation rules for the Java 25/Spring Boot backend, the Vue 3/TypeScript
frontend (including its CSS), PostgreSQL/JDBC/Flyway persistence and application support code
(Bash and Node scripts, Docker/Compose/Nginx). Python and YAML under `.github/` belong to the
harness, not to the programmer. Build manifests and lockfiles own exact dependency versions; keep
the existing stack. Sections 1–6 apply across languages; the later sections cover the
language-specific details. Agent roles and delivery rules belong in [AGENTS.md](../AGENTS.md),
not in this document.

This is a rule document and part of the harness: the programmer and planner read it and follow it,
and propose changes through `## Harness feedback`; the owner or a supervisor session edits it. Write
rules only where a reviewer would otherwise have to guess; the programmer is skilled and needs no
instruction for the obvious.

These bias toward caution over speed. For genuinely trivial tasks (a one-line fix, a typo),
use judgment — don't ritualize the process past the point it adds value.

## 1. Evaluate, Decide, Proceed

**Most changes are local — treat them that way.** A typical bugfix, small feature slice, or
change confined to one component: just implement it per Sections 2–6, verify it (Section 4),
and move on. Do not read the backlog or unrelated parts of the codebase for a
change like this — that's wasted context/tokens for something that doesn't need it.

**Only widen the lens for changes that are genuinely cross-cutting**, specifically:
- adding a new dependency/library, or a new external tool/service integration;
- introducing or changing a shared contract used across modules/components — DTOs, public
  interfaces, API shapes, database schema, event/message formats;
- an architectural decision (new layer, new pattern, changed module boundary);
- a change you can already tell will touch multiple existing components.

For those, actually evaluate the options against the project before choosing: how each fits
`ARCHITECTURE.md`, how it interacts with the other parts of
the codebase it will be shared with, and — only when directly relevant, not as a routine
scan — whether it conflicts with clearly related planned work. Prefer the approach that
minimizes conflicts with other in-flight/planned work and minimizes refactor work later, not
necessarily the fastest one to write now. State the reasoning and any assumption made in the
  PR description or review discussion.

If a simpler approach than the one requested exists (local or cross-cutting), say so in the
report and use it, rather than building the more complex thing just because it was implied.

Use `needs-decision` for a genuine missing Product Owner decision or a contradiction in the
issue's acceptance criteria. Resolve ordinary implementation choices with a reason in the PR.

## 2. Simplicity First

Use the smallest clear design that preserves the required behavior. Shorter code is useful
when it removes concepts, duplication or work; compressed expressions are not an improvement.

- No features beyond what was asked.
- Prefer the plain solution that a new maintainer understands on first read. No clever tricks,
  deep generics, reflection or metaprogramming unless the problem requires them; use a newer
  language feature when it makes the code shorter and clearer, not because it is new. Standard
  library and existing dependencies come before new ones.
- Introduce an abstraction only when it clarifies a real responsibility, reused policy or
  external/test boundary. Avoid forwarding wrappers and speculative configuration.
- Keep validation, overflow bounds, cancellation and failure recovery that protect actual
  boundaries. Remove a defensive path only after establishing that it cannot occur.
- Prefer a straightforward loop or expression over extra layers. Share genuinely identical
  rules; do not merge code that merely looks similar but has different semantics.
- When equally correct designs are feasible, weigh maintainability and concrete performance:
  algorithmic complexity, repeated scans, allocations, SQL/HTTP work and resource lifetime.
  Do not claim a speedup without evidence or add caching/concurrency without a real need.

## 3. Surgical Changes

Keep each change focused on its approved goal. A simplification task may remove proven dead
code and unnecessary structure within its section; an unrelated bug fix should stay narrow.

- Avoid adjacent formatting/style rewrites. Existing working code can be simplified when
  that is the task, but a preferred style alone is not a reason to change it.
- Before deleting code, check callers, framework wiring, reflection/serialization and tests.
  Report findings outside the section rather than silently widening the refactor.
- When your own change orphans an import/variable/function, remove it. Don't remove
  pre-existing dead code unless asked.
- Test: every changed line should trace directly to the task at hand.

## 4. Goal-Driven Execution

Define success criteria. Loop until verified. Never mark something done without proving it.

- Turn vague asks into verifiable goals. The implementing agent writes meaningful checks:
  invalid-input tests for validation, regression tests for confirmed defects, and appropriate
  refactoring checks, alongside the production change.
- For multi-step work, state a brief plan (step → how you'll verify it) and keep progress
  visible in the issue or PR. Ask for human input only for a genuine product decision.
- If something goes sideways mid-task, stop and re-plan rather than pushing through.
- Given a bug report or failing CI: reproduce from the logs/errors/failing test yourself and
  resolve it — don't wait to be told how.

## 5. Structure & File Size

- Keep cohesive, clearly named units. Roughly 500 lines per file or 40 lines per method is
  a review signal, not a hard limit or a reason to manufacture files/helpers. Split when
  distinct responsibilities become clearer; keep a readable cohesive operation together.
- Follow the existing domain/application/adapter boundaries. Prefer meaningful feature names
  inside them over generic `util`/`service` dumping grounds; do not reorganize packages just
  to impose a different layout.
- Keep nesting shallow (≤2–3 levels) — guard clauses over nested conditionals.

## 6. Naming, Comments & Errors

**Naming:** expressive names instead of comments (`remainingRetryCount`, not `n # retries
left`). No abbreviations beyond well-established ones (`id`, `url`, `dto`). Boolean names read
as a question/state (`isValid`, `hasExpired`). Magic numbers/strings become named
constants/enums. Follow the conventions of the language and of the surrounding code (casing, file
and package names), and use one name for one concept across code, tests and UI text.

**Comments — as few as possible, as many as necessary.** Only comment the *why*, not the
*what*: non-obvious intent, pitfalls/edge cases, side effects, workarounds for library bugs
(link the issue if there is one), invariants the compiler/interpreter won't catch. Public-API
docs on reusable modules: brief, contract-only (params, return, errors, thread-safety), no
prose. Never comment self-explanatory code, and never leave commented-out old code — delete
it, Git has the history. Keep comments true: change them with the code. Do not put ticket or story
IDs or change history in new comments; the PR and Git hold that. A comment that explains what a
block does is a hint to extract it or rename something.

**Error handling:** never swallow an error silently — handle it meaningfully, wrap it with
more context, or deliberately propagate it. Fail fast: validate preconditions up front rather
than letting a bad value surface deep in the call stack. Don't use a bare null/None (or
equivalent silent "nothing here") as a "not found" signal where the language gives you an
explicit way to say so (e.g. `Optional<T>` for a Java domain return). Preserve established
SQL/JSON null and unavailable-value semantics at boundaries; do not change them through a
mechanical null-to-Optional rewrite. Never convert unknown data into a false/zero fact.

**Logging (backend):** new code logs through SLF4J (`LoggerFactory`), not `System.out` or
`System.err`; existing calls stay unless the task touches them. One line per event at a fitting
level (error: needs attention, warn: degraded but continuing, info: lifecycle, debug: diagnostics).
Never log API keys, tokens or personal data, and do not log and rethrow the same error.

## 6.1 Testing and quality evidence

Follow [Test strategy](TEST_STRATEGY.md) and the issue's test-ownership assignment. Normally
the implementing agent maintains the tests/fixtures too; an explicit independent-QA assignment
takes precedence. Cover changed behavior,
confirmed defects, relevant invariants, and integration boundaries without padding coverage.
Run focused checks and builds, report exact results, and explain any changed or weakened
assertion in the PR. The reviewer checks the test diff and acceptance evidence. Passing the
required CI checks on the PR head remains necessary. The 90% line/branch target is a warning.

For simplification, read the relevant tests before editing and protect complete observable
outputs, errors and boundary cases. Add a missing behavior check before a risky refactor;
do not mirror the new implementation as the oracle. Preserve existing assertions unless
their documented behavior genuinely changed. Compare public APIs, ordering, ownership,
numeric bounds and resource/lifecycle behavior, not just happy-path return values.

## 7. Java 25 Specific

- Use records, sealed hierarchies and exhaustive switches where they clarify the model.
  Preserve equality, constructor, serialization and mutability contracts; do not rewrite
  data types merely to use newer syntax.
- Prefer `Optional<T>` for method return values. Preserve existing explicit snapshot/DTO fields
  that model absence; do not rewrite their API or serialization contracts for style. Avoid adding
  `Optional` parameters or `Optional` fields in ordinary mutable state.
- Use the existing executor/admission model unless the task requires a change; its guarantees are
  in `ARCHITECTURE.md` section 6. Use no preview APIs: the build enables no preview features.
- Text blocks (`"""..."""`) for multi-line strings (SQL, JSON, error messages).
- `var` only when the right-hand side makes the type unambiguous at a glance — otherwise
  write the explicit type.
- Streams for transformation/filtering where they read cleanly; a plain loop is fine when
  it's clearer — readability beats "elegant."
- Dependency injection instead of `new`-ing dependencies inside business logic, to keep things
  testable; constructor injection, no field injection. Keep Spring wiring at the
  composition/transport boundary, outside pure domain code.
- Prefer immutable data: records, `final` fields, unmodifiable collections from public methods.
- Use the Maven wrapper and the existing BOM-managed Spring/Jackson/Flyway/test dependencies. Do not
  change serializers or dependency versions as a side effect (JSON roles: `ARCHITECTURE.md` section 2).
- No junk-drawer static utility classes for unrelated methods — put behavior where the data
  lives.
- Name tests for behavior. Group by the contract/scenario being protected; a one-to-one
  test-class/production-class mapping is not required for cross-component behavior.

## 8. Vue 3 and TypeScript

- Keep components focused on presentation and interaction; use existing composables for
  shared state/lifecycle work. Extract only when it reduces duplicated logic or clarifies
  ownership. Avoid one-use component/composable layers that merely forward props or calls.
- Keep domain calculations and GW2 access in the backend, with the documented Ecto exception.
  Reuse typed API models; avoid `any`, broad casts and duplicated response shapes that hide
  invalid states. Preserve missing/unavailable values distinctly from zero/false.
- Use computed values for derived state. Use watchers/effects for actual side effects and
  clean them up with their owning lifecycle. The browser lifecycle behaviors to preserve are in
  `ARCHITECTURE.md` section 3.
- Keep templates and CSS readable and use stable list keys. Accepted layouts and keyboard/focus
  behavior are in `UI_SPEC.md` and the UX guidelines; the supported viewport policy in
  `DOMAIN_SPEC.md` rules out a mobile redesign.
- Components use `<script setup lang="ts">` with typed `defineProps`/`defineEmits`; `const` by
  default; narrow a type instead of casting or asserting with `!`. Components are PascalCase,
  composables `useXxx`.
- CSS: scoped styles with descriptive class names; take colors, spacing and radii from the CSS
  variables in `styles.css` instead of new literals; no `!important`; no static inline styles.
- Use the locked npm/Vite/vue-tsc/Vitest/Playwright tooling. Reuse behavior/component tests
  and controlled browser journeys; do not introduce another store, router or test framework
  solely to simplify a local section.

## 9. PostgreSQL, JDBC and Flyway

- Keep SQL parameterized and resource ownership explicit with try-with-resources. Reuse the scoped
  repository/publication boundaries and never broaden an account query. The persistence behavior to
  preserve is in `ARCHITECTURE.md` section 4.
- Avoid extra queries or loading broad data merely to shorten Java.
- Schema changes are a new Flyway file `V<next number>__snake_case_description.sql`; never edit a
  migration that is on master, and keep one concern per migration. SQL identifiers are
  `snake_case`; declare the types, `NOT NULL`, keys and constraints that the code relies on; add an
  index only for a query that needs it.

## 10. Application support code

- Keep Docker/Compose/Nginx configuration explicit and use the existing entry points. The deployment
  invariants (private ports, required production settings, canonical Origin, restricted DB credentials,
  protected host override) are in `ARCHITECTURE.md` section 8. Static configuration changes need the
  relevant render/proxy checks and imply no live deployment.
- Prefer existing Node ES modules and small shell commands to new dependencies or orchestration
  layers. Preserve quoting, exit status, bounded waits and cleanup of owned resources. Never
  interpolate credentials into logs or tracked files.
- Every script starts with a short comment on purpose and usage and ends non-zero on failure.
  Bash: `#!/usr/bin/env bash`, `set -euo pipefail`, quote expansions, clean up with `trap`. Do not
  pipe a command into `grep -q` under `pipefail`: grep exiting early can kill the producer with
  SIGPIPE and fail a check whose line is present; capture the output first.
- Update existing owning developer docs only for lasting changes to behavior, structure or
  operation. Put per-section findings, trade-offs and exact checks in the issue/PR; do not
  add an audit report, decision file or coverage snapshot to the repository for each refactor.

## 11. Automated quality gates

Tools enforce what they can; review covers judgment. The gates run locally and in CI.

| Gate | Where | What it enforces |
|---|---|---|
| Architecture rules (ArchUnit) | `backend/src/test/java/architecture/ArchitectureTest.java`, run by `./mvnw test` | Domain packages (`craft`, `domain`, `model`, `tradingpost`, `luck`) have no dependency on web, adapters, Spring, JSON, JDBC or HTTP; Spring stays in `web`; nothing depends on `web`. `web` may read through repositories but never opens a connection, runs SQL (`SQLException` and `Timestamp`/`Date`/`Time` values are fine) or calls a mutating repository method (the entry point `Gw2ApiApplication` is exempt as composition root); see `ARCHITECTURE.md`. Frozen target rules: no JDBC in `application`, no package cycles. |
| Static analysis (Checkstyle) | `backend/config/checkstyle/`, bound to `validate` | Correctness-oriented rules the code already meets, plus limits for file length (1500), method length (200) and nesting. |
| Bug patterns (Error Prone) | `backend/pom.xml` compiler arguments | Error-level bug patterns fail compilation; warnings are off. |
| Lint (ESLint) | `frontend/eslint.config.js`, `npm run lint`, CI job `Frontend tests (Vitest)` | `typescript-eslint` and `eslint-plugin-vue` correctness rules. All findings are errors and no warnings are tolerated (`--max-warnings=0`). |

**Frozen rules.** `freeze(...)` rules record violations that existed when they were introduced
in `backend/src/test/resources/archunit_store`. A new violation fails the build; fixing an
old one removes it from the store automatically. Never add entries to make a change pass.
When you fix recorded violations, include the shrunk store in the same PR.

**Harness correction.** When review or CI finds the same kind of mistake twice, do not only
fix the instance. Propose a stronger rule in a follow-up issue: an ArchUnit rule, a lint
rule, or a sentence in this document. Record a durable cause in `tasks/lessons.md`.
