# CLAUDE.md

## Purpose

This file is the default operating guide for Claude Code in this repository.

Keep context usage efficient:

- Do **not** read every document for every task.
- Read only the files relevant to the current task.
- Do not repeatedly reopen unchanged documentation unless needed.
- Prefer targeted code/file inspection over broad repository-wide reading.

---

## Project Overview

This repository contains a Guild Wars 2 crafting and economy tool.

The current application is a Java desktop application using:

- Java 25 (Maven-built; standard `src/main/java`/`src/test/java` layout, JavaFX/Jackson/PostgreSQL/JUnit managed as Maven dependencies)
- JavaFX (launched via the `javafx-maven-plugin`, e.g. `./mvnw javafx:run`)
- PostgreSQL
- Guild Wars 2 API

The intended direction is a containerized web application with separate frontend, backend, and PostgreSQL containers.

Do not assume that the target architecture has already been implemented.

---

## Documentation Router

Use the following rules to decide which documentation to read.

### For crafting calculations, economic rules, recipe logic, discovery rules, material valuation, or daily-craft behavior

Read:

```text
docs/DOMAIN_SPEC.md
```

This is the authoritative source for intended domain behavior.

Do not infer domain rules from existing code when the Domain Spec defines a different behavior.

### For architecture, refactoring boundaries, web migration, containerization, or new structural work

Read:

```text
docs/TARGET_ARCHITECTURE.md
```

Use it to understand intended dependency direction and target boundaries.

Do not silently finalize technologies marked `TBD`.

### For understanding what the existing application currently does

Read:

```text
docs/CURRENT_STATE_SPEC.md
```

Use this for current behavior and current implementation context.

It is descriptive, not automatically authoritative for future behavior.

### For tests or regression-protection work

Read:

```text
docs/TEST_STRATEGY.md
```

when that file exists.

### For migration sequencing

Read:

```text
docs/MIGRATION_PLAN.md
```

only when that file exists and the task concerns migration planning or implementation order.

---

## Source of Truth Priority

When information conflicts, use this priority:

```text
1. Explicit current user instruction
2. docs/DOMAIN_SPEC.md for domain behavior
3. docs/TARGET_ARCHITECTURE.md for architectural direction
4. task-specific specifications
5. existing implementation
6. README / comments
```

Existing code is evidence of current behavior, not automatically the desired behavior.

If existing behavior conflicts with a defined domain rule, report the conflict before changing behavior.

---

## Working Rules

### Before editing

1. Understand the requested scope.
2. Read only the documentation relevant to that scope.
3. Inspect the smallest relevant part of the codebase first.
4. Identify affected tests before making changes.
5. If the task reveals a material ambiguity not covered by the specifications, stop and report it instead of inventing a rule.

### During implementation

Prefer small, focused changes.

Do not combine unrelated refactoring with a bug fix or feature unless explicitly requested.

Avoid speculative cleanup.

Do not rewrite working code merely because another design looks cleaner.

Preserve existing behavior unless:

- the task explicitly changes it, or
- it conflicts with an authoritative specification.

---

## Domain Rules

Authoritative domain rules belong in:

```text
docs/DOMAIN_SPEC.md
```

Do not duplicate the entire Domain Spec here.

Important operating rule:

> Never invent or reinterpret a domain rule to make an implementation easier.

If a new unresolved domain question appears, report it clearly and wait for a decision before implementing behavior that materially affects calculation results.

---

## Architecture Rules

The target architecture requires separation between:

```text
UI / transport
Application use cases
Domain logic
Infrastructure
```

The domain must not depend on:

- JavaFX
- frontend frameworks
- HTTP
- REST
- JSON transport models
- PostgreSQL
- JDBC
- SQL
- Docker
- Guild Wars 2 API response models

Business rules belong in the backend/domain, not in the frontend or HTTP controllers.

External systems should be accessed through clear boundaries/adapters.

---

## Frontend Technology

Frontend technology is currently undecided.

Do not choose or introduce React, Vue, TypeScript, or another frontend stack unless explicitly requested.

---

## Backend Framework

A Java backend is currently preferred, but the exact web framework is undecided.

Do not introduce Spring Boot, Quarkus, or another backend framework unless explicitly requested.

---

## Database

PostgreSQL remains the target database.

Do not embed domain behavior in SQL or repository classes.

Database access should remain an infrastructure concern.

---

## Guild Wars 2 API

The backend owns Guild Wars 2 API access.

The future frontend must not call the GW2 API directly.

Do not expose API keys or database credentials to frontend code.

---

## Project-Specific Security Policy

The following are accepted project-specific decisions and must not be repeatedly re-raised as findings in future analyses:

- The Guild Wars 2 API key used by this project is read-only and is not considered an account-takeover credential.
- The local PostgreSQL password used by this project is not considered a material security finding.

This does not relax the general rule that runtime configuration (API keys, database credentials) must not be hard-coded into source files — see the Database and Guild Wars 2 API sections above. Configuration is supplied via environment variables, with a gitignored `.env` file (see `.env.example`) as the local-development fallback.

---

## Testing Expectations

Changes to domain behavior require automated tests.

For bug fixes:

1. reproduce the bug with a test when practical,
2. make the smallest fix,
3. confirm relevant regression tests pass.

For refactoring:

1. preserve behavior with tests first,
2. refactor,
3. verify behavior remains unchanged.

Do not weaken or delete tests merely to make a change pass unless the specification itself changed.

---

## Repository Exploration

Avoid reading the entire repository by default.

Start with:

- files named in the task,
- direct callers/callees,
- relevant tests,
- relevant repository/domain classes.

Expand outward only when necessary.

For large files, inspect relevant sections rather than rereading the entire file repeatedly.

---

## Generated and External Files

Do not modify or commit generated/dependency files unless explicitly required.

Examples may include:

- third-party JAR files,
- generated caches,
- build outputs,
- IDE metadata.

When unsure whether a file is source, generated data, or external dependency, inspect repository conventions before changing it.

---

## Documentation Changes

Update documentation when a change alters:

- domain behavior,
- architecture decisions,
- public API contracts,
- migration assumptions.

Do not rewrite large documentation files for purely cosmetic reasons.

Keep documentation changes scoped to the actual decision or behavior change.

---

## Communication

When reporting work, be concise and concrete.

For implementation tasks, report:

```text
What changed
Why
Tests run
Any remaining uncertainty
```

For analysis-only tasks, clearly separate:

```text
Observed fact
Inference
Recommendation
```

Do not present an inference as an observed fact.

---

## Safety Rule for First Repository Analysis

When explicitly asked to analyze the repository without modifying code:

- do not edit source files,
- do not reformat code,
- do not update dependencies,
- do not create migrations,
- do not change configuration,
- only create requested documentation files.

---

## Default Behavior

If the user request is clear, proceed without unnecessary clarification.

If the task would require choosing a still-undecided domain or architecture rule, ask before implementing that decision.

Keep changes small, reviewable, and testable.
