# Supervised work and handoffs

Updated 17 November 2026. GitHub issues, PRs, reviews and CI own execution state. This file records current owner authorization and handoff constraints. The [roadmap](../docs/ROADMAP.md) owns future scope; [Development workflow](../docs/DEVELOPMENT_WORKFLOW.md) explains the implemented process.

## Current owner authorization

The owner authorized repository documentation cleanup and update, followed by resuming useful
coverage work and reconciling known problems. Documentation cleanup is merged in #241; the
first backend account-boundary slice #111 is delivered in #242. Their execution evidence
remains in GitHub.

On 17 November the owner additionally approved meaningful real-browser user journeys and the
[tool viewport policy](../docs/DOMAIN_SPEC.md#supported-browser-viewport), with public-reading
exceptions. [#243, STORY-COV-006](https://github.com/Nebettadjsr/GW2/issues/243) is the authorized
bounded implementation handoff. It is separate from the broader backend request #21, which
remains open. No new authentication policy, accepted-page redesign, provider change or retired
capacity programme is authorized.

## Confirmed earlier scope

- Public Home, legal pages, calculation bounds and the Bank/Materials reservation correction are approved and deployed. The owner confirmed the finished behavior works. Earlier local-only holds for those completed releases are superseded.
- The owner approved the existing visible Oracle release process. A deployment uses an explicitly selected reviewed commit and protected host configuration; merges alone do not deploy. New UI layouts still return to the owner in the local application.
- The owner explicitly requested the detailed private deployment guide on 17 November. Its credential-bearing file is ignored and restricted locally; do not copy its contents into Git, issues or workflow documentation.
- Pre-release load/capacity measurements, VM work, additional-user-key searches and broad analysis work are withdrawn. Preserve concrete future optimization opportunities and use actual production observations without claiming a supported-user capacity.
- Preserve unrelated maintainer changes, normal local application sessions and account data. No real-account mutations are authorized for verification. Hosting/provider changes, paid services, billing and genuine product choices remain owner decisions.

## Desktop-browser handoff

[Issue #243](https://github.com/Nebettadjsr/GW2/issues/243) owns acceptance criteria, source/test
scope and current execution status. Its prerequisites #111/#242 and #240/#241 are merged.
Provide the notice through the real App capability boundary and preserve existing account,
crafting and character state on resize. The curated aggregate is `npm run smoke:ci`
(`node scripts/run-desktop-journeys.mjs`) after build in the existing required frontend CI job.
The notice, `smoke:ci` and the CI step are implemented on the #243 branch. Independent QA
passes 30 focused tests, a fresh build and ten browser journeys at `906a808`, including the
review-driven pending-Profit, Ecto and native-modal resize regressions. Local owner visual
review remains pending; require current-head CI/review before acceptance and consult the PR
for their live status. This change is not deployed.

Cover public reading/connection, saved reservations into Profit/search/detail, recoverable
failure/retry, account isolation and the viewport boundary through real Chromium with controlled
HTTP responses. These journeys establish UI/request behavior, not backend economics or a
line/branch percentage. Remove unsupported phone/tablet tool-layout repetitions while retaining
their relevant desktop feature assertions and existing keyboard/focus/zoom/short-height coverage.
Public reading and the notice remain readable and keyboard-accessible below the tool boundary.

The issue is held with `pairing` during preparation and acceptance. Independent QA owns all
frontend tests, scripts, fixture/helpers and mobile-test removals. Claude may inspect/run them
but implements only the released production UI/package/CI and documentation scope. A separate
documentation pass maintains the owning specification/testing/process files; no archived UD/ADR
queue is reactivated. The new notice requires concrete local owner visual approval before
acceptance; do not replace that review with browser screenshots or automated assertions.
Keep the ordinary local app and accepted preview intact. Merge/public deployment remain separate.

[#21](https://github.com/Nebettadjsr/GW2/issues/21) stays open for later backend gaps selected
from full current CI reports, not the browser journey count. [#60](https://github.com/Nebettadjsr/GW2/issues/60)
and [#228](https://github.com/Nebettadjsr/GW2/issues/228) remain separate candidates.

A released handoff identifies goal, dependencies, acceptance criteria, meaningful checks, current documentation and remaining decisions. The supervisor releases one dependency-complete issue at a time after inspecting that handoff. A draft or an `auto` label alone does not start Claude.

For this supervised session, independent QA owns test and fixture authorship. Claude implements any explicitly required production scope and runs checks; the supervisor does not silently replace either role. This is a scoped override of the general programmer-authors-tests workflow. Preserve assertions and explain every test correction. Require current-head CI, completed native review and necessary local evidence before merging.

## Acceptance and continuity

Do not mark source-audit findings resolved without current implementation and verification evidence. Keep remaining defects and performance opportunities in their owning documents. Record actual CI/review/deployment results in GitHub and observed runtime state in CURRENT_STATE_SPEC, rather than accumulating a second execution ledger here.

The [previous handoff](../docs/archive/agent/BACKLOG-2026-11-16.md) retains the detailed historical Bank/Materials request. Old harness records are in [the archive](../docs/archive/README.md) and are not executable instructions.
