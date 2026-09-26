# Coding Guidelines

Binding rules for generated/edited code across this project — the Java client/backend and the
Python agent orchestrator (`agent/runtime/`) alike. Sections 1–6 apply to any language used
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
story result/report.

If a simpler approach than the one requested exists (local or cross-cutting), say so in the
report and use it, rather than building the more complex thing just because it was implied.

Reserve stopping the story (marking it BLOCKED per CLAUDE.md, with the conflict reported) for
genuine blockers only: the Definition of Done is actually contradictory or unspecified, or
every available approach risks materially wrong or hard-to-reverse changes to shared state.
This should be rare — most ambiguity gets resolved by picking the best-reasoned option and
documenting why, not by stopping.

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

- Turn vague asks into verifiable goals: "add validation" → write tests for invalid input,
  then make them pass; "fix the bug" → write a test that reproduces it, then make it pass;
  "refactor X" → tests pass before and after.
- For multi-step work, state a brief plan first (step → how you'll verify it), and check in
  before implementing anything non-trivial (3+ steps or an architectural decision). For
  larger stories, track it in `tasks/todo.md` with checkable items; mark items off as you go.
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

**Planning:** for non-trivial tasks (3+ steps or an architectural decision), state a short
plan and check in before implementing — see Section 4.

**Subagents:** offload research, exploration, and parallel analysis to subagents to keep the
main context window clean. One task per subagent, for focused execution.

**Version control:** the orchestrator owns committing and pushing. When the evaluator
accepts a story, `agent/runtime` stages everything outstanding, commits it as
`implemented <STORY-ID>: <title>`, pushes it, and waits for the GitHub CI gate
(`docs/TEST_STRATEGY.md` §36). One commit per verified story, not one per file
touched — so do not commit during implementation, and never push, unless a human
explicitly asks for it. A commit is a verification point, not a save button.

Two consequences worth keeping in mind while implementing:

- whatever is in the working tree when a story completes gets committed, so leave
  no scratch files, and never disable a `.gitignore` rule to get something in;
- a red pipeline comes back to you as a short list of failing tests, and is fixed
  under `TEST_STRATEGY.md` §21 like any other failure — reproduce the named tests
  locally with the narrowest command that covers them (§20), fix the cause, and
  the harness pushes again.

Self-improvement (updating `tasks/lessons.md` after a correction, reviewing it at session
start) is defined in `CLAUDE.md`, not here.