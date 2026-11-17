# ADR-010 — Account persistence and legacy migration

## Status

ACCEPTED, amended by UD-016

**Amendment (UD-016):** the database is a disposable cache of GW2 API data. The tool choice (Flyway,
versioned SQL, startup gate, GUID-scoped schema) stands. The legacy-data contract in this ADR
(restricted archive, provenance/import ledger, reconciliation, restorable backup, unassociated-legacy
quarantine) is withdrawn: legacy cached tables are reset and re-synchronized from the GW2 API
(`TARGET_ARCHITECTURE.md` section 7.4). Read the text below with that exception.

## Context

AR-010 is Class C: a migration-tool selection and lasting persistence strategy. Phase 6 already requires shared PostgreSQL with isolated GW2 account data. The supported user model is established, not changed by this decision. No Product Owner decision is required.

Observed evidence: `CURRENT_ARCHITECTURE.md` sections 5, 7 and 8 describe unscoped legacy account data, transactional source publication and repeatable-read calculations. Inspection of the manual schema, `db/manual` additions, `AccountSyncStateRepository`, `AccountLuckRepository` and `AccountSync` confirms source-only completion keys, singleton material state and GUID-keyed Luck. Luck's identity cannot establish ownership of other tables. No live database was inspected; its exact schema, contents and historic ownership remain unknown.

## Constraints

Target sections 6-9 require backend-owned scope, one shared PostgreSQL database, stable verified GW2 identity, transient keys, global reference/economy data, atomic last-good source views and deferred evidence-based cleanup. Phase 6 requires preservation of existing valid data. Domain calculations remain account-agnostic. The runtime is a single backend process; no new service or distributed job infrastructure is justified. Test strategy requires isolated real PostgreSQL for SQL invariants and offline fixtures for normal API verification; quality metrics remain binding.

## Decision

Select the open-source Flyway Java engine and PostgreSQL module with versioned SQL migrations. Establish explicit account ownership, durable source generations/activity, and a preserved legacy archive with evidence-based association. `TARGET_ARCHITECTURE.md` sections 7.1-7.4 own the normative contract; this ADR records rationale rather than a second schema specification.

## Rationale

Engineering judgment: a linear, reviewable SQL history best fits a single-maintainer Java/Spring Boot application already using PostgreSQL-specific JDBC and SQL, developed one bounded story at a time. The project needs reproducible creation, upgrades and validation, not a portable database abstraction or generated schema-diff workflow. Keeping migrations in the backend adds no independent process and does not change frontend data shapes, HTTP boundaries or domain rules. No page-load benefit or benchmark is claimed; migrations run before serving work.

Spring Boot documents both Flyway and Liquibase integration and recommends one schema-initialization mechanism. Flyway documents SQL checksum validation and transaction handling, including exceptions for PostgreSQL statements that cannot execute in transactions. This supports a deliberately transactional cutover and fail-closed history validation. Its baseline documentation warns that automatic baselining removes protection against migrating the wrong database; explicit schema preflight is essential here. See the linked primary documentation below.

Both candidates have maintained first-party documentation and an existing Spring Boot integration path. No relative adoption, release-speed or long-term support guarantee is assumed. Use compatible pinned versions and review upgrades; this decision does not select a floating latest release or depend on commercial support/features. PostgreSQL support is a separate Flyway dependency. Carrying that dependency is smaller, in my judgment, than maintaining a custom migration runner, history validator and concurrency/error handling.

Safe ownership is more important than making every historical row immediately usable. A present credential proves present account access, not the identity of previous unscoped writes. Preserving unresolved rows separately allows migration without either inventing identity or deleting valid history. Fresh verified synchronization restores usable account sources while the original data remains available for later evidence-based reconciliation. Schema progress and identity association therefore have separate retry boundaries.

## Alternatives Considered

- **Liquibase (runner-up):** supports SQL as well as modeled XML/YAML/JSON changelogs and checksums, and integrates with Spring Boot. It could satisfy this project. Its richer changelog model is useful where structured cross-database changes or more varied deployment histories are needed; those are not documented requirements here. Formatted SQL avoids XML but still introduces changeset identity/changelog conventions. Flyway's ordered SQL model is the simpler fit by engineering judgment, not a claim that Liquibase requires XML or is unreliable.
- **Manual SQL plus a custom version table:** avoids a library but makes this project own validation, ordering, locking and failed-run semantics. Existing manual variants already demonstrate the need for controlled adoption. Reject the maintenance burden.
- **Assign all legacy rows to the configured key, sole Luck ID, or first visitor:** convenient but cannot establish historical ownership. Reject because it can expose or corrupt another account's data.
- **Discard/re-fetch all account data:** operationally simple but violates preservation; upstream may no longer provide a historical record. Re-fetch is appropriate for live unproven sources only when originals remain archived.
- **Per-account databases, application users, row-level security as the primary boundary, or distributed migration/job services:** unnecessary for the documented single-backend shared-database boundary. Explicit scope plus constraints fits without those additional mechanisms.

## Consequences

Migration dependencies, explicit legacy preflight and archive storage become maintenance responsibilities. A supported legacy schema inventory must be established from real deployments before writing adoption migrations. Unknown schema drift stops adoption; unresolved identity does not justify guessing. Archive growth is an acknowledged preservation cost, not evidence authorizing cleanup.

Fresh installation and legacy adoption converge on one migration-managed schema. Legacy runtime DDL and incompatible old writers must be retired at cutover. Replacement keys reuse one account registry entry; scoped source state preserves successful-empty semantics and independent source freshness. Existing transactional publication and snapshot guarantees remain required.

Changing migration tools later is possible by explicitly reconciling schema/history; PostgreSQL SQL stays reusable, but an applied history cannot be casually rewritten. The more costly commitment is account ownership and preservation, justified by the existing isolation requirement. Recovery uses a restorable backup and compatible application version; no automated destructive rollback is promised.

Planner follow-up: derive bounded implementation/verification work from the target contract, including actual legacy inventory, adoption, scoped repositories, source versions/activity and retry/isolation verification. Reconcile roadmap wording that still treats migration-tool selection as undecided, and account for the schema gate needed by Phase 6. No stories or roadmap state are changed here; QA owns the per-story plan.

## References

- `agent/architect-requests/AR-010-account-schema-and-legacy-migration.md`.
- `docs/TARGET_ARCHITECTURE.md` sections 6-9, 13 and 16; `docs/CURRENT_ARCHITECTURE.md` sections 5, 7-8; `docs/ROADMAP.md` Phase 6 and Phase 7 migration prerequisite.
- `docs/TEST_STRATEGY.md` sections 9, 19 and 31; `docs/QUALITY_METRICS.md` coverage KPI.
- [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
- [Flyway Java API and open-source edition](https://documentation.red-gate.com/flyway/reference/usage/api-java) and [PostgreSQL module documentation](https://github.com/flyway/flyway/blob/main/documentation/Reference/Database%20Driver%20Reference/PostgreSQL%20Database.md).
- [Flyway validation](https://documentation.red-gate.com/flyway/reference/commands/validate), [transaction handling](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html), and [baseline-on-migrate warning](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting).
- [Liquibase SQL changelogs](https://docs.liquibase.com/community/user-guide-5-0-4/sql-changelog-example) and [changeset checksums](https://docs.liquibase.com/community/user-guide-5-0-4/what-is-a-changeset-checksum).
