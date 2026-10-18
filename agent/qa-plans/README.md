# Persistent QA plans

One validated `QA-STORY-<AREA>-<NUMBER>.json` plan is retained per story in
this directory. The orchestrator creates or refreshes it from the QA agent's
structured result before the first coding attempt. The file is committed with
the story's work and is the coding agent's binding test/expectation contract.
QA exclusively designs, writes and maintains all tests and fixtures. Claude implements
production behavior and runs the required checks to satisfy the story's exit criteria.
Specifications never delegate test authorship to Claude.

The schema is versioned (`schema_version: 1`) and records:

- story ID and QA model/reasoning configuration;
- plan outcome: `READY`, `NO_TESTS_NEEDED`, or `NEEDS_USER`;
- rationale, acceptance checks, complete-result invariants and test levels;
- `interface_contract`: the names the prepared tests rely on, which the coder must use
  verbatim (deviations are reported and aligned by QA, see `QA_INSTRUCTIONS.md`);
- existing tests reviewed, created or updated QA-owned test paths and precise test
  specifications with expected outcomes;
- pre-implementation test command/result, coverage review and external facts
  with source URLs;
- clarifications and their standard Product Owner decision IDs;
- protected test SHA-256 values and any independent post-implementation reviews.

For a durable attempt already underway when the QA gate is introduced, the
harness persists a `legacy_attempt` compatibility plan instead of rerunning
pre-implementation QA or coding. Such plans document why no new acceptance
tests are prepared and require post-implementation review of the completed
attempt.

`NO_TESTS_NEEDED` requires a specific story-based justification. `NEEDS_USER`
blocks only the affected story until the referenced `UD-*` decision is resolved;
the harness then requeues it and reruns QA against the answer. Do not edit a QA
plan during implementation. QA may amend its own expectation only through a
documented QA rerun and independent review.

`READY` means the required test code is prepared or sufficient existing tests are
identified. Any remaining test-writing work belongs to a QA invocation. A read-only
post-implementation review reports gaps; it does not transfer test maintenance to Claude.

An evidenced `REVISE_TESTS` review instead schedules a separate guarded application-test
repair. The refreshed plan records its `test_repair` request ID and approved findings,
preserves earlier review records, and refreshes protected hashes/snapshots. A matching
validated result can be reused after a service restart without repeating the writer.
Evaluation and publication/CI must run again after corrections.

Raw intermediate outputs, retry counters, and byte-for-byte test backups live
in ignored `agent/runtime/artifacts/`; this directory contains the durable
plan and review record.
