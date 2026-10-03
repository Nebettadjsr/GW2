# Confirmed Bug Registry

This directory is the permanent, version-controlled registry for confirmed software defects.
It complements the current-defect summary in `docs/KNOWN_PROBLEMS.md`: each confirmed defect
gets one detailed record here, while known-problems remains an index of active risks and
conflicts.

## Lifecycle

1. **Reported:** Create a record from `BUG-REPORT-TEMPLATE.md` with observed behavior, exact
   reproduction steps, inputs/settings, severity, and discovery date. Mark unknown root cause as
   `Not established`; do not infer it.
2. **Investigating:** Record evidence and narrow down the affected feature/component and likely
   cause. Preserve fixtures in `docs/bugs/fixtures/` or the relevant test fixture location, and
   link them from the record.
3. **Correcting:** Link the story/issue/code change. The correction must address the root cause.
4. **Regression verified:** Add a regression test based on the original observed failure and
   expected requirement. Record its path/name and test command/result.
5. **Closed:** Close only after the regression test passes and relevant checks complete. If a
   regression test cannot reasonably be added, document the reason, compensating verification,
   and explicit human-approved exception; link the approval.

Suggested filenames are `BUG-YYYY-NNN-short-description.md`, with a unique increasing number
for the year. Do not create retrospective records for historical bugs unless a current work
item explicitly requires them.

## Permanent fixtures

Place compact, stable reproduction inputs under `docs/bugs/fixtures/` when they are useful
outside one test suite. Prefer test-local fixtures when the data is meaningful only to that
test. Do not store credentials, account tokens, personal data, or large generated data. Each
fixture must be referenced by its bug record and include provenance/licensing details when
derived from an external system.
