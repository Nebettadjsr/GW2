# GW2 active roadmap

Updated: 2026-11-07. Owner: GitHub Codex planner; changes are reviewed by the interactive Codex supervisor.

**Workflow execution authorized by the Product Owner on 2026-11-07; interactive Codex supervises one released story at a time.** Draft order, active handoffs and execution evidence live in [the backlog](../agent/BACKLOG.md) and GitHub issues/PRs. The Product Owner owns product intent and material hosting/budget decisions.

## Roles

- Interactive Codex supervises readiness, progress, failures and acceptance.
- Codex on GitHub plans milestones and bounded issue contracts.
- Claude implements released stories and their tests.
- Codex PR review and CI check delivered changes; planning is an explicit separate invocation.

Contracts and invocation details: [agent roles](../agent/README.md). These files do not install a scheduler or start a planner/Claude run.

## Milestone 1: measurable fit within Oracle Always Free

### Goal

Implement the missing KPIs and repeatable qualification needed for the final product to operate within Oracle Always Free while preserving correctness, account isolation and a usable desktop experience. Include material protection and the Characters view in final qualification, not just the current application. Report supported capacity and limitations honestly; measuring usage does not itself guarantee free hosting.

### Confirmed hosting target

The Product Owner confirmed Oracle Always Free on 2026-11-07; Cloudflare is not the requested hosting target. Preserve the portable single-host frontend/backend/PostgreSQL deployment in `TARGET_ARCHITECTURE.md` section 12.

Qualification target: ARM64 Ampere A1, combined 2 OCPU / 12 GB RAM for the entire deployment, including OS, Docker, PostgreSQL, backend, frontend and monitoring overhead. This is an allocation ceiling, not permission for Java alone to use 12 GB. Confirm the actual tenancy's available resources and shared storage/network usage before qualification.

Oracle's [Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), checked 2026-11-07, specify 1,500 OCPU-hours and 9,000 GB-hours monthly, equivalent to 2 OCPU / 12 GB, plus 200 GB combined boot/block storage across the tenancy. The planner must include storage, transfer, logs and any other required services in the budget; workload, latency and safety-headroom targets remain to be agreed. No target host is currently verified by this setup.

### Sequence

1. Reconcile existing measurements/reports and open work; identify gaps.
2. Verify available Oracle resources and agree workload/KPI budgets with units, quota periods and operating headroom.
3. Extend existing instrumentation/reporting only where needed.
4. Run reproducible correctness, multi-account, desktop, sustained-load and recovery qualification with simulated upstream failures.
5. Produce a concise usage/headroom report and supervisor health review. Propose optional improvements separately; do not implement them automatically.

### Evidence and exit criteria

- Selected topology is compatible with the application or a separately approved adaptation plan exists. All required runtime/storage/network components and free-tier limits are accounted for.
- Metrics cover relevant CPU, memory, database/pool/storage, latency/errors, transfer/request quotas and upstream/cache activity. Provider-specific counters supplement host metrics where applicable.
- Every result includes commit, configuration, environment, workload, account count, duration, units and measurement boundaries; missing data is explicit. Compare current measurements with budgets and show headroom.
- Cold/warm catalogs, concurrent distinct accounts, refreshes, slow/429/5xx upstreams and restart recovery have meaningful evidence. Use disposable data and fake upstreams; preserve account isolation and domain behavior.
- Desktop response/rendering behavior is included; mobile optimization is outside this milestone.
- Per-service and whole-deployment evidence comes from the selected target environment before claiming free-tier fit. Development-machine results are labelled experiments.
- Existing coverage and performance reports remain usable. Monitoring overhead and log/storage growth are bounded and measured.
- Supervisor reviews CI, code review, local-only evidence, remaining risks and workload limits. Unknown or excessive usage is a reported gap, not a passed milestone. The Product Owner decides adoption of further optimizations.

### Reuse existing work

Start from [performance measurement guidance](PERFORMANCE_MEASUREMENTS.md), [quality metrics](QUALITY_METRICS.md) and [the optimization investigation](OPTIMIZATION_INVESTIGATION_REPORT.md). Do not reimplement completed pooling, simulation-state reuse, recipe-payload reuse, correctness fixes or Bank/Materials refresh improvements without current evidence of a remaining gap.

KPI contracts and proposed story order: [active backlog](../agent/BACKLOG.md).

## Milestone 2: protect selected materials from Crafting Profit

### Confirmed product intent

Users saving for a legendary or another goal can protect owned item/material types selected in Bank and Materials from Crafting Profit consumption. Buying replacements remains allowed when buying is enabled. Implement selection, request/settings transport and authoritative backend calculation behavior together; hiding results alone does not meet the goal.

### Confirmed product/UI contract

- Product behavior: [DOMAIN_SPEC.md section 9.1](DOMAIN_SPEC.md). Protect all owned copies while preserving normal acquisition rules for replacements.
- Selection UI: [FRONTEND_UX_GUIDELINES.md section 2.1](FRONTEND_UX_GUIDELINES.md). Tile checkboxes plus explicit confirmation, presets for every tier T1–T6 and room for more, Profit toggle/count, short empty-selection warning and links back to relevant selection pages.
- Remember the committed selection and activation setting per account on the same browser. No cross-device synchronization is required.

These choices were confirmed by the Product Owner. The planner can now refine the bounded story contracts, including a UI review of exact preset membership, labels and interactions. Individual stories still require supervisor release under the execution authorization recorded in the backlog.

### Acceptance and resource constraints

- Bank and Materials edit one coherent selection by item type; real inventory quantities remain visible and unchanged. Use committed selection consistently across repeated stacks and locations.
- The backend applies the approved restriction throughout Crafting Profit, including recursive ingredients. List/detail calculations, costs, quantities, shopping lists and any cached results must agree with the same effective settings.
- Feature disabled or an empty list preserves existing results. Account changes must not reuse another account's selections or stale in-flight results. Discovery and Ecto are outside the requested scope.
- Preserve existing selection/navigation, sorting, filtering, refresh and item-link interactions; define accessible keyboard operation and a visible enabled/count summary. Consult the Product Owner for unresolved UI decisions rather than inventing them.
- Prefer a compact, validated item-ID selection and constant-time membership checks within existing calculation boundaries. Reuse existing request/account state; no new service, polling loop or per-item upstream fetch is justified by this feature. Persistence architecture follows the answer above.
- Measure feature-disabled, enabled-empty and enabled-populated cases using comparable catalogs/accounts and selected-list sizes. Record calculation latency/CPU/allocation, request size and desktop interaction cost; do not claim zero overhead without evidence.
- Final Oracle qualification includes this feature under the agreed workload. Do not introduce an unmeasured per-account copy of the entire catalog or shared mutable calculation state to support it.

Status: awaiting bounded story refinement/release under active supervision. Detailed story order and decisions are in the [backlog](../agent/BACKLOG.md). Feature planning can proceed alongside KPI planning; final product qualification follows feature integration.

## Milestone 3: Characters overview and equipment-first details

### Goal and scope

Add a GW2efficiency-inspired roster with concise information and click-through character details. Primary purpose: inspect armor/weapons/other equipment to see ascended/legendary items and remaining gear gaps. Include character inventory, builds and story details to the extent supported by the API. Discovered recipes are excluded; map completion is deferred for a later Product Owner decision.

Owning scope: [DOMAIN_SPEC.md section 56](DOMAIN_SPEC.md). UI intent: [FRONTEND_UX_GUIDELINES.md section 2.2](FRONTEND_UX_GUIDELINES.md). Do not infer every statistic, sharing/export option or character renderer from the supplied screenshots.

### Sequence and exit criteria

1. Audit existing character synchronization/storage and current API data/permissions for equipment, inventory, builds and story. Include all saved available equipment/build tabs using adjacent in-game-style tabs, in original slot order, with the in-game active tab initially selected. Report unsupported story data rather than silently substituting backstory choices.
2. Prepare a concrete overview/detail UI proposal for Product Owner review, prioritizing equipment quality visibility. Set bounded read/refresh and cache contracts; reuse current account and shared metadata infrastructure.
3. Implement reviewed data/API and equipment/overview stories, then inventory/build details and supported story functionality. Every story includes meaningful tests; pending API/product choices block only the affected work.
4. Verify account isolation, metadata-based rarity, missing versus empty slots, template-reference deduplication, refresh/recovery and navigation. Do not change existing crafting inventory totals through duplicated template equipment.
5. Measure roster/detail response size, desktop rendering, metadata/upstream requests, cold/warm behavior and whole-stack CPU/memory/storage under the Oracle workload. No per-character polling loop, new service or eager download of every detail on each roster visit without measured justification.

Status: awaiting bounded story refinement/release under active supervision. Final product hosting qualification depends on this milestone as well as material protection. The backlog records story release; unreleased drafts are not executable.

## Operational follow-up: owner administration and automatic inventory refresh

Confirmed scope: [DOMAIN_SPEC.md section 57](DOMAIN_SPEC.md), [UX sections 2.3–2.4](FRONTEND_UX_GUIDELINES.md) and [owner authorization target](TARGET_ARCHITECTURE.md). Only the Product Owner's verified account gets global item/recipe/full-TP controls and detailed diagnostics. Other users get their account connection/freshness/refresh controls and generic shared health. Backend authorization is required before public deployment; a hidden button is not the acceptance criterion.

Bank/Materials move to automatic stale-data refresh with saved data retained and an updating hint. Remove both manual refresh and cached-reload buttons there; keep manual account refresh in System Status. Reuse the existing shared task/freshness model, preserve material-protection selections and prevent refresh storms across views.

Prepare separate backend authorization, status UI and inventory freshness stories. Verify owner versus non-owner direct requests, task visibility, key replacement, empty owner configuration, account switching, duplicate refresh admission and stale/error recovery. Batch and bound full-catalog admin work; include its CPU/memory/storage/upstream impact in Oracle qualification rather than assuming on-demand TP measurements represent it. Implementation follows supervisor release of a concrete contract.

## Existing architecture and historical migration

### Correctness prerequisite: owned non-tradable valuation

Product Owner clarification: owned non-tradable/bound inputs, especially daily materials, must use recursive crafting-input value before vendor fallback rather than automatic zero. The owning rule is `DOMAIN_SPEC.md` sections 11.1–11.2. Review and correct the current bound-stock bypass before accepting material-protection work; verify list/detail profit reconciliation and bounded valuation overhead. This is a draft correction, not a started implementation.

[Domain specification](DOMAIN_SPEC.md), [current architecture](CURRENT_ARCHITECTURE.md), [target architecture](TARGET_ARCHITECTURE.md), [test strategy](TEST_STRATEGY.md) and recorded user decisions remain authoritative. This roadmap does not change their business or architecture rules.

The previous Phase 0–9 migration roadmap is retained in Git history at baseline `d713eb46c03e3c5cfa3cdfc90ad5d5216b559752:docs/ROADMAP.md`. Historical phase references in ADRs describe that plan. Previous completion statements are historical evidence, not a fresh health certification.
