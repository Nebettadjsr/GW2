# Coding Guidelines

Binding implementation rules for the Java 25/Spring Boot backend, Vue 3/TypeScript frontend,
PostgreSQL/JDBC/Flyway persistence and application support code. Build manifests and lockfiles
own exact dependency versions; keep the existing stack. Sections 1–6 apply across languages;
the later sections cover the stack-specific details. Agent roles and delivery rules belong
in [AGENTS.md](../AGENTS.md), not in this document.

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
constants/enums.

**Comments — as few as possible, as many as necessary.** Only comment the *why*, not the
*what*: non-obvious intent, pitfalls/edge cases, side effects, workarounds for library bugs
(link the issue if there is one), invariants the compiler/interpreter won't catch. Public-API
docs on reusable modules: brief, contract-only (params, return, errors, thread-safety), no
prose. Never comment self-explanatory code, and never leave commented-out old code — delete
it, Git has the history.

**Error handling:** never swallow an error silently — handle it meaningfully, wrap it with
more context, or deliberately propagate it. Fail fast: validate preconditions up front rather
than letting a bad value surface deep in the call stack. Don't use a bare null/None (or
equivalent silent "nothing here") as a "not found" signal where the language gives you an
explicit way to say so (e.g. `Optional<T>` for a Java domain return). Preserve established
SQL/JSON null and unavailable-value semantics at boundaries; do not change them through a
mechanical null-to-Optional rewrite. Never convert unknown data into a false/zero fact.

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
- Use the existing executor/admission model unless the task requires a change. Virtual
  threads suit blocking I/O, not faster CPU calculations; preserve cancellation, single-writer
  limits, interruption and shutdown. See the [Java 25 virtual-thread guide](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html).
  This build does not enable preview features; do not require
  [`StructuredTaskScope`, a Java 25 preview API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html),
  or enable preview as incidental cleanup.
- Text blocks (`"""..."""`) for multi-line strings (SQL, JSON, error messages).
- `var` only when the right-hand side makes the type unambiguous at a glance — otherwise
  write the explicit type.
- Streams for transformation/filtering where they read cleanly; a plain loop is fine when
  it's clearer — readability beats "elegant."
- Dependency injection instead of `new`-ing dependencies inside business logic, to keep things
  testable. Keep Spring wiring at the composition/transport boundary, outside pure domain code.
- Use the Maven wrapper and existing BOM-managed Spring/Jackson/Flyway/test dependencies.
  HTTP Jackson and upstream JSON adapters have different roles; preserve their mappings
  rather than changing serializers or dependency versions during cleanup.
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
  clean them up with their owning lifecycle. Preserve account epochs, cancellation, request
  ordering/coalescing, KeepAlive activation and reservation autosave semantics.
- Keep templates and CSS readable. Preserve accepted desktop layout, keyboard/focus behavior,
  stable list keys and the documented narrow-screen notice/scrolling; no mobile redesign.
- Use the locked npm/Vite/vue-tsc/Vitest/Playwright tooling. Reuse behavior/component tests
  and controlled browser journeys; do not introduce another store, router or test framework
  solely to simplify a local section.

## 9. PostgreSQL, JDBC and Flyway

- Keep SQL parameterized and resource ownership explicit with try-with-resources. Reuse the
  scoped repository/publication boundaries; never broaden an account query during cleanup.
- Preserve transaction ownership, original connection mode, atomic publication, last-good
  generations and rollback/cancellation behavior. Read real PostgreSQL tests before changing
  these paths; a mocked connection does not prove SQL semantics.
- Avoid extra queries or loading broad data merely to shorten Java. Preserve ordering,
  unknown/null facts and snapshot consistency. Schema changes use existing Flyway conventions.
  PostgreSQL remains a disposable cache, without new preservation/backup machinery.

## 10. Application support code

- Keep Docker/Compose/Nginx configuration explicit and use the existing entry points.
  Preserve private backend/database ports, required production settings, canonical Origin,
  restricted DB credentials and the protected host override. Static config simplification
  needs the relevant render/proxy checks; it does not imply a live deployment.
- Prefer existing Node ES modules, Python standard-library helpers and small shell commands
  to new dependencies or orchestration layers. Preserve quoting, exit status, bounded waits
  and cleanup of owned resources. Never interpolate credentials into logs or tracked files.
- Update existing owning developer docs only for lasting changes to behavior, structure or
  operation. Put per-section findings, trade-offs and exact checks in the issue/PR; do not
  add an audit report, decision file or coverage snapshot to the repository for each refactor.
