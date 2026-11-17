# Supervised work and handoffs

Updated 17 November 2026. GitHub issues, PRs, reviews and CI own execution state. This file records current owner authorization and handoff constraints. The [roadmap](../docs/ROADMAP.md) owns future scope; [Development workflow](../docs/DEVELOPMENT_WORKFLOW.md) explains the implemented process.

## Current owner authorization

The owner explicitly authorized repository documentation cleanup and update, followed by resuming the workflow to raise test coverage and reconcile known problems. Archive the legacy stories, architecture requests, user decisions and rationale; preserve current rules in maintained documents. Consolidate architecture if the current/target split no longer serves the delivered application. Document the actual agent workflow and audit current app behavior against the domain specification.

Complete the documentation work before activating the next implementation run. The coverage request continues existing #21/#111 scope; it is not authorization to introduce new authentication policy, redesign accepted UI or recreate a capacity programme.

## Confirmed earlier scope

- Public Home, legal pages, calculation bounds and the Bank/Materials reservation correction are approved and deployed. The owner confirmed the finished behavior works. Earlier local-only holds for those completed releases are superseded.
- The owner approved the existing visible Oracle release process. A deployment uses an explicitly selected reviewed commit and protected host configuration; merges alone do not deploy. New UI layouts still return to the owner in the local application.
- The owner explicitly requested the detailed private deployment guide on 17 November. Its credential-bearing file is ignored and restricted locally; do not copy its contents into Git, issues or workflow documentation.
- Pre-release load/capacity measurements, VM work, additional-user-key searches and broad analysis work are withdrawn. Preserve concrete future optimization opportunities and use actual production observations without claiming a supported-user capacity.
- Preserve unrelated maintainer changes, normal local application sessions and account data. No real-account mutations are authorized for verification. Hosting/provider changes, paid services, billing and genuine product choices remain owner decisions.

## Coverage handoff

The first released slice is [#111](https://github.com/Nebettadjsr/GW2/issues/111), account HTTP mapping and session-recovery coverage, with independent QA tests in [PR #242](https://github.com/Nebettadjsr/GW2/pull/242). GitHub owns its current review/merge state. Use the full CI measurements in [Quality metrics](../docs/QUALITY_METRICS.md) for subsequent selection; focused counters do not replace that baseline. Keep [#21](https://github.com/Nebettadjsr/GW2/issues/21) open for remaining relevant gaps. [#60](https://github.com/Nebettadjsr/GW2/issues/60) and [#228](https://github.com/Nebettadjsr/GW2/issues/228) remain separate maintenance candidates.

A released handoff identifies goal, dependencies, acceptance criteria, meaningful checks, current documentation and remaining decisions. The supervisor releases one dependency-complete issue at a time after inspecting that handoff. A draft or an `auto` label alone does not start Claude.

For this supervised session, independent QA owns test and fixture authorship. Claude implements any explicitly required production scope and runs checks; the supervisor does not silently replace either role. This is a scoped override of the general programmer-authors-tests workflow. Preserve assertions and explain every test correction. Require current-head CI, completed native review and necessary local evidence before merging.

## Acceptance and continuity

Do not mark source-audit findings resolved without current implementation and verification evidence. Keep remaining defects and performance opportunities in their owning documents. Record actual CI/review/deployment results in GitHub and observed runtime state in CURRENT_STATE_SPEC, rather than accumulating a second execution ledger here.

The [previous handoff](../docs/archive/agent/BACKLOG-2026-11-16.md) retains the detailed historical Bank/Materials request. Old harness records are in [the archive](../docs/archive/README.md) and are not executable instructions.
