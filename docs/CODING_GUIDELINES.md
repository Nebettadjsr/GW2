# Coding Guidelines

This file defines binding rules for generated/edited code in this project (Java 25). Goal: maintainable, clearly structured code with no spaghetti code.

## 1. Structure & File Size

- **Max. 500 lines per file.** If a class is foreseeably going to exceed this, split it (Extract Class) rather than cramming it in somehow.
- **Single Responsibility per class.** One class does one thing. If a class has more than one reason to change → split it.
- Prefer many small, clearly named classes/interfaces over a few large "God Classes".
- Structure packages by business domain, not purely by technical layer (`orders.pricing`, not just `service`, `util`, `impl`).
- No method > ~40 lines as a guideline. Long methods → extract into well-named helper methods (this often replaces the need for comments, see below).
- Nesting depth ideally ≤ 2-3 (guard clauses instead of nested if/else pyramids).

## 2. Comments — as few as possible, as many as necessary

No comment that just restates what the code obviously does.

**Only comment:**
- **Why**, not **what** (the intent/reason behind a non-obvious decision).
- **Pitfalls/edge cases**: e.g. "intentionally no trim here, whitespace is significant", side effects, race conditions, edge cases, workarounds for library bugs (with ticket/link if available).
- Non-trivial invariants or preconditions the compiler doesn't enforce.
- Public API (Javadoc on public classes/methods of reusable modules): brief, describe the contract (parameters, return value, exceptions, thread-safety), no prose.

**No comment for:**
- Self-explanatory code (a good name makes a comment redundant — prefer `isEligibleForDiscount()` over `// checks if discount applies` above a cryptic condition).
- Commented-out old code (delete it, Git has the history).
- Trivial getters/setters/constructors.

## 3. Naming & Readability

- Expressive names instead of comments: `remainingRetryCount` instead of `int n // retries left`.
- No abbreviations except well-established ones (`id`, `url`, `dto`).
- Boolean names as a question/state: `isValid`, `hasExpired`.
- Magic numbers/strings → named constants or enums.

## 4. Error Handling

- No empty `catch` blocks. Either handle exceptions meaningfully, wrap them in a more meaningful exception, or deliberately rethrow — never swallow silently.
- Only use checked exceptions when the caller can realistically react to them; otherwise use an unchecked/domain-specific exception.
- Don't use `null` as a return value for "not found" → use `Optional<T>` (see below).
- Fail fast: validate preconditions early (`Objects.requireNonNull`, `IllegalArgumentException`) instead of letting errors surface deep in the call stack.

## 5. Modern Java 25 — Best Practices

**Data modeling**
- Use `record` for immutable data carriers (DTOs, value objects) instead of classic classes with getter/setter/equals/hashCode boilerplate.
- Use `sealed` interfaces/classes for closed hierarchies (e.g. result/state types), combined with **pattern matching for `switch`** (exhaustive switch without `default` when all cases are covered).
- Use record patterns to destructure in `switch`/`instanceof` instead of manual getter chains.

**Immutability**
- Fields `final` by default. Mutable state only when explicitly needed (e.g. builders, performance-critical paths).
- Return collections defensively as unmodifiable (`List.copyOf`, `Collections.unmodifiableList`) instead of exposing internal lists directly.

**Concurrency**
- **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) for I/O-heavy concurrent work instead of hand-tuning platform-thread pools.
- **Structured concurrency** (`StructuredTaskScope`) when several subtasks should run/fail as one unit — instead of manually wiring `CompletableFuture`s.
- Avoid shared mutable state; where unavoidable, clearly document why (thread-safety shouldn't rely on "forgotten" state).

**API & readability**
- `Optional<T>` only as a return type (never as a field or method parameter), to make "no value" explicit.
- Text blocks (`"""..."""`) for multi-line strings (SQL, JSON, error messages) instead of string concatenation.
- `var` only when the type is immediately obvious from context (the right-hand side is unambiguous) — otherwise write the explicit type.
- Use streams for transformations/filtering, but don't force it when a plain loop is clearer — readability beats "elegant".
- Dependency injection instead of `new`-ing dependencies in the middle of business code, to keep things testable.

**Other**
- No static utility classes as a dumping ground for unrelated methods (the "junk drawer" anti-pattern) — put behavior where the data lives.
- Tests mirror the structure: one test class per production class, test names describe behavior (`returnsEmptyList_whenNoMatch`), not implementation.

## 6. Checklist Before Commit

- [ ] No file > 500 lines
- [ ] Every class has exactly one responsibility
- [ ] Comments explain only why/pitfalls, not what
- [ ] No empty catch blocks, no `null` for "not found"
- [ ] Records/sealed types used where appropriate instead of boilerplate
- [ ] Immutability by default
- [ ] Virtual threads/structured concurrency instead of low-level threading, where concurrency is needed