# Coding Guidelines

Binding rules for generated/edited code across this project — the Java backend, Vue frontend,
and GitHub workflow code alike. Sections 1–6 apply to any language used
here; Section 7 is Java 25 specific. Goal: maintainable, clearly structured code, no spaghetti,
no unverified "done."

These bias toward caution over speed. For genuinely trivial tasks (a one-line fix, a typo),
use judgment — don't ritualize the process past the point it adds value.

## 1. Evaluate, Decide, Proceed

**Most changes are local — treat them that way.** A typical bugfix, small feature slice, or
change confined to one component: just implement it per Sections 2–6, verify it (Section 4),
and move on. Do not read `ROADMAP.md`, the backlog, or unrelated parts of the codebase for a
change like this — that's wasted context/tokens for something that doesn't need it.

**Only widen the lens for changes that are genuinely cross-cutting**, specifically:
- adding a new dependency/library, or a new external tool/service integration;
- introducing or changing a shared contract used across modules/components — DTOs, public
  interfaces, API shapes, database schema, event/message formats;
- an architectural decision (new layer, new pattern, changed module boundary);
- a change you can already tell will touch multiple existing components.

For those, actually evaluate the options against the project before choosing: how each fits
`CURRENT_ARCHITECTURE.md`/`TARGET_ARCHITECTURE.md`, how it interacts with the other parts of
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

Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code, no "flexibility" or config that wasn't requested.
- No error handling for scenarios that can't occur.
- If it could be a third the size, rewrite it. Ask: would a senior engineer call this
  overcomplicated? If yes, simplify.

## 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

- Don't "improve" adjacent code, comments, or formatting while you're in a file for something
  else. Don't refactor things that aren't broken. Match existing style even if you'd do it
  differently.
- If you notice unrelated dead code, mention it — don't delete it unasked.
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

- Prefer many small, clearly named units over a few large ones. ~500 lines per file is a
  ceiling, not a target — if a file is foreseeably going to exceed it, split it rather than
  cram more in.
- Single responsibility per unit (class, module, function group). More than one reason to
  change it → split it.
- Organize by business domain, not purely by technical layer (`orders/pricing`, not a bare
  `util`/`service` dumping ground).
- No function/method longer than ~40 lines as a guideline — extract well-named helpers
  instead (this usually removes the need for a comment, too).
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
explicit way to say so (e.g. `Optional<T>` in Java).

## 6.1 Testing and quality evidence

The implementing agent chooses test scope and expected results under `docs/TEST_STRATEGY.md`,
and writes and maintains the tests and fixtures needed for its issue. Cover changed behavior,
confirmed defects, relevant invariants, and integration boundaries without padding coverage.
Run focused checks and builds, report exact results, and explain any changed or weakened
assertion in the PR. The reviewer checks the test diff and acceptance evidence. Passing the
required CI checks on the PR head remains necessary. The 90% line/branch target is a warning.

## 7. Java 25 Specific

- `record` for immutable data carriers instead of manual getter/setter/equals/hashCode
  boilerplate; `sealed` interfaces/classes for closed hierarchies, paired with exhaustive
  pattern-matching `switch`; record patterns to destructure instead of manual getter chains.
- `Optional<T>` only as a return type, never as a field or parameter.
- **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) for I/O-heavy concurrent
  work instead of hand-tuned platform-thread pools. **Structured concurrency**
  (`StructuredTaskScope`) when subtasks should run/fail as one unit, instead of wiring
  `CompletableFuture`s by hand. Avoid shared mutable state; where unavoidable, document why.
- Text blocks (`"""..."""`) for multi-line strings (SQL, JSON, error messages).
- `var` only when the right-hand side makes the type unambiguous at a glance — otherwise
  write the explicit type.
- Streams for transformation/filtering where they read cleanly; a plain loop is fine when
  it's clearer — readability beats "elegant."
- Dependency injection instead of `new`-ing dependencies inside business logic, to keep things
  testable.
- No junk-drawer static utility classes for unrelated methods — put behavior where the data
  lives.
- Tests mirror production structure: one test class per production class, test names
  describe behavior (`returnsEmptyList_whenNoMatch`), not implementation.

## 8. Workflow Mechanics

**Planning:** for non-trivial issues, state a short plan and verification approach in the
issue or PR. Keep choices proportional to the issue and its dependencies.

**Version control:** one issue gets one branch and pull request. Commit and push reviewable
steps on that branch. Never include scratch files, generated output, or unrelated work. The PR
contains the exact diff under review. Changes requested are fixed on the same branch; CI and
Codex review rerun on the new head. Required checks and review must pass before merge.

**Follow-up work:** record a small nonblocking correction as a linked issue. A missing
acceptance criterion, concrete defect, or unresolved Product Owner decision holds the PR.
