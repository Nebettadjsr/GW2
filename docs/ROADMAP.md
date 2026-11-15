# GW2 active roadmap

Updated: 2026-11-13. Owner: GitHub Codex planner; changes are reviewed by the interactive Codex supervisor.

**Workflow execution authorized by the Product Owner on 2026-11-07; interactive Codex supervises one released story at a time.** Draft order, active handoffs and execution evidence live in [the backlog](../agent/BACKLOG.md) and GitHub issues/PRs. The Product Owner owns product intent and material hosting/budget decisions.

## Roles

- Interactive Codex supervises readiness, progress, failures and acceptance.
- Codex on GitHub plans milestones and bounded issue contracts.
- Claude implements released stories and their tests.
- Codex PR review and CI check delivered changes; planning is an explicit separate invocation.

Contracts and invocation details: [agent roles](../agent/README.md). These files do not install a scheduler or start a planner/Claude run.

## Milestone 1: public-production readiness on Oracle Always Free

### Goal

Prepare the application for a public, containerized deployment on Oracle Always Free while preserving correctness, account isolation and a usable desktop experience. Public-production readiness includes material protection, Characters, owner administration and automatic inventory freshness, plus the deployment items below. It does not require a capacity or resource-measurement programme.

### Capacity experiments ended

The Product Owner ended the resource/capacity experiment programme (2026-11-14). Capacity discovery, workload profiling, recorded-interaction replay, VM/container/JVM resource observation and the informational Performance workflow are **retired and are not launch requirements**. Their tooling was removed by STORY-LAUNCH-001 (audit: [launch tooling audit](investigations/launch-001-tooling-audit.md)); earlier reports and measurements are kept only as historical evidence and prove no capacity. The related issues #116, #154, #189 and #191 are not to be repeated. Supervisor-recorded superseding authorization is in the [active backlog](../agent/BACKLOG.md).

### Confirmed hosting target

The Product Owner confirmed Oracle Always Free on 2026-11-07; Cloudflare is not the requested hosting target. Preserve the portable single-host frontend/backend/PostgreSQL deployment in `TARGET_ARCHITECTURE.md` section 12.

Target: ARM64 Ampere A1, combined 2 OCPU / 12 GB RAM for the entire deployment, including OS, Docker, PostgreSQL, backend, frontend and monitoring overhead. This is an allocation ceiling, not permission for Java alone to use 12 GB. Oracle's [Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), checked 2026-11-07, specify 1,500 OCPU-hours and 9,000 GB-hours monthly (2 OCPU / 12 GB) plus 200 GB combined boot/block storage. No target host is currently verified.

### Sequence

1. Keep ordinary correctness coverage (Maven, Vitest, type-check, build, account isolation, focused browser and container smoke) green.
2. Complete the accepted feature milestones below (material protection, Characters, owner administration, inventory freshness).
3. Resolve the open deployment facts (operator identity, legal facts, domain, Oracle region, deployment credentials) with the Product Owner.
4. Run a production-style stack smoke on the target host as a deployment gate; this is later work and is not part of the cleanup story.
5. Supervisor reviews CI, code review, local-only evidence and remaining risks. Unknown facts are reported gaps, not passed gates.

### Exit criteria

- The selected topology runs the application on the Oracle host with all runtime, storage and network components accounted for.
- Production images contain no test recorder, proxy, timing overlay, debug route or test-only bypass.
- Default CI (Maven suite, Vitest coverage, type-check, build, workflow-helper checks) passes; account isolation and real-database checks pass locally.
- Operator and legal information, domain and credentials are supplied by the Product Owner and are not invented.

Historical documents ([performance measurements](PERFORMANCE_MEASUREMENTS.md), [workload profile](PERFORMANCE_WORKLOAD_PROFILE.md), [optimization investigation](OPTIMIZATION_INVESTIGATION_REPORT.md)) are not launch requirements. Completed pooling, simulation-state reuse, recipe-payload reuse and Bank/Materials refresh improvements stay as delivered.

## Milestone 2: protect selected materials from Crafting Profit

### Confirmed product intent

Users saving for a legendary or another goal can protect owned item/material types selected in Bank and Materials from Crafting Profit consumption. Buying replacements remains allowed when buying is enabled. Implement selection, request/settings transport and authoritative backend calculation behavior together; hiding results alone does not meet the goal.

### Confirmed product/UI contract

- Product behavior: [DOMAIN_SPEC.md section 9.1](DOMAIN_SPEC.md). Protect quantities independently in Bank and Materials while preserving character inventory and normal acquisition rules for replacements.
- Selection UI: [FRONTEND_UX_GUIDELINES.md section 2.1](FRONTEND_UX_GUIDELINES.md). Tile checkboxes plus explicit confirmation, presets for every tier T1–T6 and room for more, Profit toggle/count, short empty-selection warning and links back to relevant selection pages.
- Remember the committed selection and activation setting per account on the same browser. No cross-device synchronization is required.

These choices were confirmed by the Product Owner. The planner can now refine the bounded story contracts, including a UI review of exact preset membership, labels and interactions. Individual stories still require supervisor release under the execution authorization recorded in the backlog.

### Acceptance and resource constraints

- Bank and Materials edit independent selections by item type within each store; real inventory quantities remain visible and unchanged. Bulk/save/discard actions stay on their own page, preserve absent-item preferences, and apply consistently to repeated stacks in that store.
- The backend applies the approved restriction throughout Crafting Profit, including recursive ingredients. List/detail calculations, costs, quantities, shopping lists and any cached results must agree with the same effective settings.
- Feature disabled or an empty list preserves existing results. Account changes must not reuse another account's selections or stale in-flight results. Discovery and Ecto are outside the requested scope.
- Preserve existing selection/navigation, sorting, filtering, refresh and item-link interactions; define accessible keyboard operation and a visible enabled/count summary. Consult the Product Owner for unresolved UI decisions rather than inventing them.
- Prefer a compact, validated item-ID selection and constant-time membership checks within existing calculation boundaries. Reuse existing request/account state; no new service, polling loop or per-item upstream fetch is justified by this feature. Persistence architecture follows the answer above.
- Preserve bounded selection payloads and account isolation. Do not introduce per-account catalog copies or shared mutable calculation state. Observe actual operational health after launch; a new feature-overhead benchmark is not a launch requirement.

Status: the paired selection and Crafting UI approved on 2026-11-08 is integrated with its regression and documentation baseline, including source-specific protection, ingredient checkbox eligibility and category-local preset placement. Detailed production preparation is in the [backlog](../agent/BACKLOG.md).

## Milestone 3: Characters overview and equipment-first details

### Goal and scope

Add a GW2efficiency-inspired roster with concise information and click-through character details. Primary purpose: inspect armor/weapons/other equipment to see ascended/legendary items and remaining gear gaps. Include character inventory and builds. Story is deferred by Product Owner decision (2026-11-07). Discovered recipes are excluded; map completion is deferred for a later Product Owner decision.

Owning scope: [DOMAIN_SPEC.md section 56](DOMAIN_SPEC.md). UI intent: [FRONTEND_UX_GUIDELINES.md section 2.2](FRONTEND_UX_GUIDELINES.md). Do not infer every statistic, sharing/export option or character renderer from the supplied screenshots.

### Sequence and exit criteria

1. Audit existing character synchronization/storage and current API data/permissions for equipment, inventory and builds. Include all saved available equipment/build tabs using adjacent in-game-style tabs, in original slot order, with the in-game active tab initially selected. Story remains outside this sequence.
2. Prepare a concrete overview/detail UI proposal for Product Owner review, prioritizing equipment quality visibility. Set bounded read/refresh and cache contracts; reuse current account and shared metadata infrastructure.
3. Implement reviewed data/API and equipment/overview stories, then inventory/build details. Every story includes meaningful tests; pending API/product choices block only the affected work.
4. Verify account isolation, metadata-based rarity, missing versus empty slots, template-reference deduplication, refresh/recovery and navigation. Do not change existing crafting inventory totals through duplicated template equipment.
5. Preserve page-scoped loading, retained data and bounded metadata/upstream work. Do not add per-character polling, a new service or eager loading of unrelated details. Monitor real production use through the existing operations facilities.

Status: the approved roster, character summary, Equipment, Inventory and Builds layouts are integrated after CI and review, with their regression and documentation baseline. Preserve the Product Owner's 2026-11-08 visual decisions during maintenance. Story/recipes/map exclusions remain unchanged. Production deployment remains separate work.

## Operational follow-up: owner administration and automatic inventory refresh

Confirmed scope: [DOMAIN_SPEC.md section 57](DOMAIN_SPEC.md), [UX sections 2.3–2.4](FRONTEND_UX_GUIDELINES.md) and [owner authorization target](TARGET_ARCHITECTURE.md). Only the Product Owner's verified account gets global item/recipe/full-TP controls and detailed diagnostics. Other users get their account connection/freshness/refresh controls and generic shared health. Backend authorization is required before public deployment; a hidden button is not the acceptance criterion.

The paired Bank/Materials/Characters implementation uses automatic page-scoped stale refresh, retained saved data and a compact updating hint. Keep manual full-account refresh in System Status. Reconcile and verify shared admission, missing-grant/error recovery, retained inspected equipment and account switching before acceptance. Do not refresh unrelated sources when navigating a feature page.

Prepare separate backend authorization, status UI and inventory freshness stories. Verify owner versus non-owner direct requests, task visibility, key replacement, empty owner configuration, account switching, duplicate refresh admission and stale/error recovery. Batch and bound full-catalog admin work. Implementation follows supervisor release of a concrete contract.

Current sequence: the owner backend and full-catalog operations are integrated, and the System Status owner and regular-user layouts are visually accepted. The resource/capacity experiment tooling that was prepared in #193 has been retired (see Milestone 1). Deployment owner configuration and the production-style stack smoke remain separately authorized work.

System Status visual decisions: owner-only composition accepted on 2026-11-11; regular-user account overview (own-account counts, compact service line) accepted on 2026-11-12. Preserve the accepted layouts; execution and final quality evidence belong to #182 / #183.

## Deferred crafting extension and live verification

The Product Owner approved future Mystic Forge recipe support behind `Allow Mystic Forge recipes`, below `Allow daily craft`; it is currently a disabled UI placeholder. Forge steps will use that setting rather than a crafting discipline/rating. [The product request](../agent/product-owner-requests/mystic-forge-crafting.md) owns the remaining data/random-output questions. Do not activate calculation work merely because the placeholder exists.

The pre-launch propagation/economics investigation [#154](https://github.com/Nebettadjsr/GW2/issues/154), capacity planning #191, broad investigation #116 and experiment-observer fix #189 were withdrawn as not planned on 2026-11-14. Their investigations are not passed gates or prerequisites for launch. Retain concrete functional defects and optional optimization follow-ups for later prioritization.

## Existing architecture and historical migration

### Correctness prerequisite: owned non-tradable valuation

Product Owner clarification: owned non-tradable/bound inputs, especially daily materials, must use recursive crafting-input value before vendor fallback rather than automatic zero. The owning rule is `DOMAIN_SPEC.md` sections 11.1–11.2. Issue [#132](https://github.com/Nebettadjsr/GW2/issues/132) is complete through accepted PR [#133](https://github.com/Nebettadjsr/GW2/pull/133), merged as `23ce30d`, including cumulative mixed-pool rounding, cycle safety and valued-bound list/detail tests. This implementation prerequisite for material protection is satisfied.

[Domain specification](DOMAIN_SPEC.md), [current architecture](CURRENT_ARCHITECTURE.md), [target architecture](TARGET_ARCHITECTURE.md), [test strategy](TEST_STRATEGY.md) and recorded user decisions remain authoritative. This roadmap does not change their business or architecture rules.

The previous Phase 0–9 migration roadmap is retained in Git history at baseline `d713eb46c03e3c5cfa3cdfc90ad5d5216b559752:docs/ROADMAP.md`. Historical phase references in ADRs describe that plan. Previous completion statements are historical evidence, not a fresh health certification.
