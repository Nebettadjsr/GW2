## Story ID

STORY-PERF-004

## Title

Verify real-data cached Profit page and asynchronous refresh acceptance

## Status

BLOCKED

## Milestone

milestone-05

## Goal

Establish reproducible Phase 5 performance and workflow evidence for the implemented cached-result contract, replacing historical fresh-cache-only acceptance assumptions.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 performance and health-review exit criteria.
- docs/TARGET_ARCHITECTURE.md sections 9.1, 17 and 21.
- docs/TEST_STRATEGY.md sections 12, 34, 35.2 and 36.
- docs/DOMAIN_SPEC.md sections 2.2.1, 25, 39 and 40.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- agent/user-decisions/UD-013-phase-five-performance-user-acceptance.md and UD-015-trading-post-refresh-performance-budget-scope.md.
- agent/stories/STORY-QUALITY-005-phase-five-completion-review.md, Result F1/F2/F5.
- agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md, Result and F001-F003 dispositions.
- agent/stories/STORY-APP-014-cached-profit-refresh-coordination.md and STORY-WEB-034-provisional-profit-refresh-workflow.md.

## Context

Historical fresh-input runs met seven seconds but did not implement the now-decided stale-while-revalidate contract. The expired-TP run measured 17,206 ms. UD-013 removes manual acceptance, while UD-015 requires separate initial cached-result and asynchronous refresh evidence. The completed health review remains the bounded assessment; this story supplies changed-contract evidence for the planner's closure review, not another broad review.

## Acceptance Criteria

1. Measure the current production browser build and real user PostgreSQL data, default All scope, first startup opening and repeat navigation in the required browser/application cache states. Record revision, environment, effective settings, data counts, individual readings and maxima without disclosing credentials/private contents.
2. Demonstrate the initial complete usable cached page within seven seconds for fresh inputs, expired account inputs, expired TP quotes and both expired. Include full requested results, required controls, interactive displayed-value reads and quiescence tied to the initial calculation context/input versions. First-row timing or a loading placeholder is not completion.
3. Separately measure refresh start to terminal outcome, refreshed calculation/rendering and navigation to final result. Confirm backend freshness on final captured inputs and correct indicator termination; do not extend the initial sample through a later replacement or invent an additional refresh-speed threshold.
4. Exercise overlapping insufficient-scope account work and changed TP demand, plus missing-input and failure/timeout recovery. Confirm retained cached results and bounded termination. Clearly label controlled-boundary correctness checks separately from real-data timing; if no usable cache exists, report readiness wait and eventual calculation/rendering as an unavailable cached-page scenario rather than seven-second compliance.
5. Verify economic/domain equivalence from the implementation reference and unchanged-snapshot HTTP/application comparison: no result truncation, fee/ownership/quantity change or frontend recalculation. Settings and valid view state survive final replacement, with row/detail version compatibility.
6. Record findings, limitations and explicit assessment of health-review F1/F2/F5 and PERF-003 F001/F002 against this evidence. Report a breach or unavailable environment as such; do not declare phase completion, waive criteria or request an obsolete manual acceptance gate.

## Required Tests

- Explicit real-browser navigation-to-complete-page measurements and real-database equivalence checks under TEST_STRATEGY sections 34 and 35.2; CI cannot run these.
- Focused checks of measurement context/version separation and actual effective-input capture when extending the existing harness for this contract. Use controlled page transitions to ensure later refresh replacement is timed separately and a placeholder cannot pass.
- Reuse delivered deterministic refresh/recovery regressions; run only targeted checks needed to resolve concrete acceptance questions, not full local suites. Review affected reports when measurement code changes, without estimating coverage or changing scope.

## Constraints

- Read real data for acceptance; never reduce/replace it or point destructive fixture setup/teardown at the user database. Preserve private comparison references outside version control.
- Measurement harness changes must be necessary to verify the new contract, not cosmetic reporting cleanup. No production remediation, broad bug hunt, speculative cleanup, later-phase work or agent/build/CI changes.
- Required full CI is owned by the pipeline after acceptance. No manual Product Owner confirmation is needed for compliant evidence under UD-013.

## Dependencies

- STORY-APP-014
- STORY-WEB-034

## Definition of Done

Reproducible current-contract measurements, equivalence evidence, scenario outcomes and limitations are recorded in Result. Concrete findings are reported through the normal workflow for planner disposition. A breach or lack of access must remain visible; this story cannot manufacture acceptance. Any affected targeted harness tests pass and relevant coverage gaps are recorded. The planner retains milestone closure authority.

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-APP-014, which is not DONE (currently TODO); depends on STORY-WEB-034, which is not DONE (currently BLOCKED)
