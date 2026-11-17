# AR-010 - Account schema and legacy migration

## Status

RESOLVED

## Architecture Question

Decide the Phase 6 account-ownership persistence boundary and a repeatable migration path from existing single-user data, including selection of the currently undecided schema migration tool. Specify how valid legacy data is safely associated with its verified stable GW2 account identity without loss or assignment to the wrong account.

## Context and Constraints

TARGET_ARCHITECTURE section 7 requires one shared PostgreSQL database, shared reference/economy data and account-scoped synchronized data; versioned schema upgrades are required but their technology remains undecided in section 16. CURRENT_ARCHITECTURE sections 7-8 describe manual schema maintenance, inconsistently scoped account records, selected identity-aware data such as Luck, transactional source publication and repeatable-read calculation snapshots.

Establish the ownership classification and relations for synchronized account records, source completion/version state and activity tracking, preserving global item/recipe/TP data and the crafting graph. Define safe legacy identity association, incomplete/ambiguous association handling, upgrade sequencing and failure/retry behavior without inventing the legacy account's identity. Preserve valid existing data and atomic source publication. Identify any genuine human choice through the architect's normal decision process.

Account activity must support identification of inactive records. The established policy defers automatic deletion until measured database/storage impact justifies it; do not introduce a cleaner or invent an inactivity threshold. Keep account ownership at application/persistence boundaries rather than duplicating domain rules. AR-008 independently settles request authorization; this request can decide persistence and migration mechanics using the already required stable GW2 account ownership principle.

## Authoritative References

- docs/ROADMAP.md, Phase 6 Objective, Exit Criteria and High-Level Stories (supplied planning excerpt).
- docs/TARGET_ARCHITECTURE.md, sections 6.2-6.4, 7, 7.1, 8, 9.1 and 16.
- docs/CURRENT_ARCHITECTURE.md, sections 5, 7 and 8.

## Blocked Work

Stories for account-scoped schema/repositories, non-destructive legacy migration, source freshness/activity persistence and cross-account persistence/integration verification. Scope implementation only after the migration technology and ownership contract are established.

## Blocking User Decision

None.

## Architect Decision

Class C — architecturally significant technical decision. Select the open-source Flyway Java engine with its PostgreSQL module and versioned SQL migrations. Explicit legacy schema preflight/baselining, transactional upgrades and checksum validation replace manual/runtime DDL. Liquibase is the viable runner-up; its richer changelog model offers no required advantage for this small PostgreSQL/JDBC codebase. Ordered SQL is the simpler fit by engineering judgment. No new service, paid feature or Product Owner decision is required. ADR-010 records the alternatives and primary technology evidence.

The normative persistence contract is in `docs/TARGET_ARCHITECTURE.md` sections 7.1-7.4: registry keyed by verified GW2 GUID, explicit scope and composite ownership constraints throughout account records, account/source completion generations, atomic source publication and repeatable-read capture, plus durable monotonic activity without automatic cleanup. Reference/economy data and the crafting graph remain shared; domain calculations receive scoped inputs without new ownership rules.

Preserve legacy records and relations in a restricted archive before constrained live-schema adoption. Promote only source generations with trustworthy identity provenance; GUID-keyed Luck does not prove ownership of unrelated unscoped records. The inspected unscoped schema contains no historical identity link. A key validated today cannot establish that link retroactively. Verified new synchronization populates live sources while ambiguous historical records remain preserved and excluded from calculations. Identity/completion conflicts fail closed without losing originals; a source-level import ledger and publication exclusion make retries idempotent and prevent overwriting newer live generations. Preserve completion times only when proven, never infer success from empty tables or adoption time.

## Resolution

> **Amended by UD-016:** the database is a disposable cache. The archive, provenance, reconciliation and
> backup parts of this resolution are withdrawn; legacy tables are reset and re-synchronized
> (`TARGET_ARCHITECTURE.md` section 7.4). The Flyway selection and GUID-scoped schema stand.

Updated `docs/TARGET_ARCHITECTURE.md` sections 7 and 16 and added `docs/architecture/decisions/ADR-010-account-persistence-and-legacy-migration.md`. Current-state documentation is unchanged because no implementation or deployed schema changed. No human choice remains for this technical contract; Blocking User Decision remains None.

Planner owns bounded work for the real legacy-schema inventory, Flyway startup/adoption gate, archive/provenance reconciliation, scoped schema/repositories, source generations/activity and cross-account migration/publication verification. Reconcile roadmap references that still call the tool undecided and ensure the Phase 6 schema gate precedes account-isolated runtime use. QA independently prepares concrete test plans under the existing strategy and metrics. No implementation, tests, stories, backlog or planner state were changed by this pass.

Unresolved historical ownership is a supported preserved migration outcome, not permission to assign data to a guessed account. Actual database schema drift or records lacking recoverable identity/completion evidence must be surfaced by migration diagnostics; this run did not inspect a live database. No cleaner, inactivity threshold, additional authentication model or distributed coordination is introduced. Authorization remains owned by target section 8.1; browser credential handling remains section 8.2.
