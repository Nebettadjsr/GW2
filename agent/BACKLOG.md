# Active planning backlog

**Status: ACTIVE SUPERVISION. Execution authorized by the Product Owner on 2026-11-07.**

The Product Owner resumed supervision on 2026-11-08 after approving the directly paired UI, including the final Builds page with Equipment-width trait strips and cropped skill pictures. Preserve that baseline and its reconciled tests/documentation while continuing the dependency-complete sequence below. This supersedes earlier draft layout proposals and shared-selection assumptions. No repeated approval is needed for the already accepted layouts.

Authorization: start the agreed workflow, supervise progress and stop only for necessary product/UI decisions or missing access. Supervisor: interactive Codex. Planner: Codex on GitHub. Programmer: Claude. Release one approved story at a time; no new paid API integration is authorized.

Historical migration context is in setup PR [#131](https://github.com/Nebettadjsr/GW2/pull/131). Reconcile legacy issues #21, #60, #111 and #116 on GitHub before selecting them; their presence does not authorize release.

Planning handoff: the Characters sequence is Inventory UI #162, Builds core #161, referenced Builds metadata/images #164, then Builds UI #166. Release each only after its actual merged dependencies and contract have been reviewed. The full trait-row choice is resolved in #164/#166; retain their bounded specialization-choice metadata scope. System Status owner boundary #152 and the remaining KPI contracts follow. Earlier material contracts remain linked below for context. Read GitHub issues, PRs, CI and reviews for execution state and evidence before release; this file does not mirror completion, merge hashes, test outcomes or running attempts. Local measurement evidence is not Oracle qualification.

## Immediate integration handoff

- Current supervisor release: #180, three distinct owner-only full-catalog operations. Its planner contract and technical addendum were inspected against the merged #152 owner/task boundary. The Product Owner selected full item/recipe detail re-downloads, including unchanged IDs; ordinary scheduler checks and feature-scoped demand retain their existing behavior. Preserve all approved layouts. Actual owner GUID provisioning and final System Status UI remain separate work.
- PR #170 / issue #169 is the approved paired baseline. The old left-facts layout and no-backend-change limits were superseded by direct instructions. UX guidelines §§2.1–2.4 and DOMAIN_SPEC §§9.1, 56–57 own the final contracts; consult the PR's actual acceptance evidence rather than reopening its completed reconciliation.
- Preserve the narrow parser behavior in PR #175 / issue #174: only omitted land elite is nullable; aquatic/heal/utilities and invalid present values stay strict.
- Keep regression coverage for finite `coversRequest:false` retries, simultaneous missing Equipment/Builds recovery, stale character/account responses and selection return races in both the managed screen and composable paths. Passive attributes require compatible build/equipment parent coverage; duration symbols/order follow the in-game reference.
- Current-state docs must distinguish implemented scoped freshness, equipment projections and the approved Builds view from the still-outstanding final Oracle qualification and owner administration, incomplete passive trait coverage and unqualified live economics/Oracle capacity. Do not repeat historical NOT_IMPLEMENTED claims for delivered character features.
- Update obsolete manual-refresh, shared-selection, nested-tab and left-facts assertions with meaningful behavioral coverage. Keep account isolation, source coverage, retained snapshots, finite recovery and duplicate-stack tests. Do not skip or weaken tests merely to make CI green.
- Record remaining Mystic Forge work through `agent/product-owner-requests/mystic-forge-crafting.md`; the checkbox is a disabled placeholder, not authorization to implement it now. Prepare the live verification work in #154 at a suitable point; the owner executes game actions after a concrete experiment plan exists.

## Activation and state

- Planner drafts/refines; supervisor reviews and releases. Only approved GitHub issues become executable work for Claude.
- Planning dispositions: DRAFT, BLOCKED, READY, PUBLISHED and DEFERRED. Accepted PLAN entries refer only to planning artifacts. Runtime progress lives on GitHub.
- READY requires an approved contract and resolved decisions; verify actual merged dependencies on GitHub before release. PUBLISHED means only that an issue contract exists, not that it is implemented or released.
- Adding the existing `auto` issue label makes a story eligible for the current selector/merge gate; only do so after explicit execution authorization and supervisor review. Markdown states are not an enforced workflow switch.
- Record authorization here, audit old open issues/PRs on GitHub and link published contracts. Do not mirror execution status or silently activate historical queued issues.

## Proposed order

| ID | Outcome / responsible role | Dependencies | Planning handoff |
| --- | --- | --- | --- |
| PLAN-HOST-001 | Planner verifies the Oracle provider budget and records target-host qualification gaps | Provider limits recorded; actual tenancy/host facts remain required only for qualification | DONE — planning artifact accepted in #134 |
| PLAN-KPI-001 | Planner inventories existing measurements and proposes bounded KPI contracts and workloads | PLAN-HOST-001 provider limits; real-host access is not required for local reporting work | DONE — planning artifact accepted in #134 |
| PLAN-VALUE-001 | Planner prepares correctness contract for recursive valuation of owned bound/non-tradable materials, including daily materials | DOMAIN_SPEC.md sections 11.1–11.2 | DONE — contract accepted as #132 |
| STORY-VALUE-001 | Claude corrects valuation and verifies list/detail cost/profit agreement, cycle handling, yields and performance | Approved issue #132 | PUBLISHED — #132 |
| STORY-KPI-001 | Claude adds whole-host Linux CPU/memory observations, boundaries/provenance and safe 2 OCPU/12 GB comparison to the existing report | Accepted contract #134; STORY-VALUE-001 | PUBLISHED — #134 |
| STORY-KPI-002 | Claude adds repeatable desktop and multi-account/fault qualification workloads | STORY-KPI-001 | DRAFT |
| STORY-KPI-003 | Claude exposes a concise capacity/health summary and documented reproduction path | STORY-KPI-001, STORY-KPI-002 | DRAFT |
| STORY-KPI-004 | Claude adds safe JVM heap/committed/max and GC observations and relates them to container/host memory without treating 12 GB as a JVM allowance | STORY-KPI-001 | DRAFT |
| STORY-KPI-005 | Claude adds project-scoped storage, database, volume and log accounting; tenancy-wide allocation remains explicit | STORY-KPI-001 | DRAFT |
| STORY-KPI-006 | Claude adds transfer-boundary observations without treating Docker/internal traffic as billable Oracle egress | STORY-KPI-001 | DRAFT |
| REVIEW-KPI-001 | Supervisor validates real target-host evidence and reports free-tier fit/gaps | STORY-KPI-001–006, qualification workloads/reporting, all final features, target-host/tenancy access | BLOCKED |
| PLAN-FEATURE-001 | Material-protection planning | Owning domain/UX contracts | ACCEPTED — published contracts #136, #138, #139, #143, #145, #146; paired corrections in #169 |
| STORY-MAT-004 | Claude exposes the verified account GUID additively in session responses for browser preference partitioning | STORY-KPI-001; approved #136 | PUBLISHED — #136 |
| STORY-MAT-001 | Claude implements backend settings/validation and recursive calculation restrictions, with real list/detail and overhead evidence | STORY-VALUE-001, STORY-KPI-001, STORY-MAT-004; approved #138 | PUBLISHED — #138 |
| STORY-MAT-002 | Per-account browser pending/committed selection | STORY-MAT-001, STORY-MAT-004 | PUBLISHED — #139 |
| STORY-MAT-003 | Bank/Materials checkboxes and confirmation | STORY-MAT-002 | PUBLISHED — #143 |
| STORY-MAT-005 | Verified T1–T6 and four-item presets | STORY-MAT-003 | PUBLISHED — #145; category placement refined in #169 |
| STORY-MAT-006 | Profit activation, count, links and request integration | STORY-MAT-001, STORY-MAT-003 | PUBLISHED — #146 |
| STORY-MAT-007 | Reserved integrated verification draft | Current source-specific implementation and #169 reconciliation | DEFERRED — inspect #169 evidence before drafting any distinct remaining gap |
| PLAN-CHAR-001 | Character API/storage and equipment-first planning | DOMAIN_SPEC §56 and UX §2.2 | ACCEPTED — published contracts below; final paired layouts approved |
| STORY-CHAR-001 | Scoped roster and character core reads | Approved contract | PUBLISHED — #141 |
| STORY-CHAR-002 | Equipment-first roster/detail UI | STORY-CHAR-001, reviewed UI | PUBLISHED — #156 |
| STORY-CHAR-003 | Original combined inventory/build draft | Later bounded contracts below | DEFERRED — split into #159, #162, #161, #164 and #166; do not release this old combined draft |
| STORY-CHAR-004 | Reserved character Story outcome | A new Product Owner decision is required to resume | DEFERRED — Product Owner confirmed 2026-11-07 |
| STORY-CHAR-005 | Independent saved-equipment data | Approved contract | PUBLISHED — #148 |
| STORY-CHAR-006 | Bag-layout inventory data | Scoped character core | PUBLISHED — #159 |
| STORY-CHAR-007 | Saved Builds data | Scoped character core | PUBLISHED — #161 |
| STORY-CHAR-008 | Bag-layout Inventory UI | STORY-CHAR-006 | PUBLISHED — #162 |
| STORY-CHAR-009 | Shared Builds reference metadata/images | STORY-CHAR-007 | PUBLISHED — #164 |
| STORY-CHAR-010 | Full-trait-row Builds UI | STORY-CHAR-007, 008, 009, 011 | PUBLISHED — #166; final layout approved, supervisor RELEASED remaining acceptance evidence |
| STORY-CHAR-011 | Approved paired UI and regression/documentation reconciliation | STORY-CHAR-008 and integrated current master | PUBLISHED — #169 / PR #170; preserve its accepted baseline |
| STORY-CHAR-012 | Character playtime, deaths and next birthday | Scoped character core | PUBLISHED — #171 |
| STORY-CHAR-013 | Narrow missing land elite compatibility | Saved Builds data | PUBLISHED — #174 |
| STORY-CHAR-014 | Equipment details and base/equipment totals | Saved equipment data | PUBLISHED — #176; paired stats refinements in #169 |
| REVIEW-CHAR-001 | Supervisor reviews correctness, desktop behavior and Oracle resource evidence including both new features | Character/material stories and KPI qualification workloads | DRAFT |
| PLAN-STATUS-001 | Owner/admin planning | DOMAIN_SPEC §57; TARGET_ARCHITECTURE §8.3 | ACCEPTED first bounded contract — #152; later contracts remain drafts |
| STORY-STATUS-001 | Backend owner policy, existing global action/task authorization and generic/detailed status | Approved #152; synthetic principals suffice for implementation | PUBLISHED — #152; actual owner configuration needed only to enable privilege |
| STORY-STATUS-004 | Distinct owner-only full-item, full-recipe and full-tradable-TP operations | STORY-STATUS-001; resolved full-detail semantics and inspected owner/task seams | PUBLISHED — #180; supervisor RELEASED; no UI or live refresh authorization |
| STORY-STATUS-002 | Ordinary/owner System Status UI | STORY-STATUS-001, STORY-STATUS-004, UI review | DRAFT |
| STORY-STATUS-003 | Reserved automatic inventory-freshness draft | #169 delivers paired page-scoped refresh | DEFERRED — reconcile #169 before releasing any distinct remaining gap |

Story IDs are distinct from GitHub issue numbers. Published contracts are linked above; unpublished rows remain drafts. Never reuse a published ID for a different outcome. Existing selector compatibility is retained by `STORY-AREA-NNN` naming; publication/labels still require supervisor release.

## KPI story drafting criteria

For STORY-KPI-001: extend the existing container report only with whole-host Linux CPU/memory observations, architecture/boundary/provenance, safe 2 OCPU/12 GB comparison and explicit unavailable/not-qualified behavior. Preserve warning-only CI. JVM details, storage/log accounting and transfer accounting are reserved for STORY-KPI-004, STORY-KPI-005 and STORY-KPI-006 respectively; internal Docker traffic is not billable-egress evidence.

For STORY-KPI-002: real public catalog topology with synthetic independent accounts, cold/warm cases, calculations, refreshes, sustained concurrent use, restart recovery and simulated slow/429/5xx upstreams. Preserve domain results and account separation; no live ArenaNet stress testing. Separate browser measurements from server and provider usage. Proposed load tiers, duration and success thresholds require an approved contract rather than invention by Claude.

For STORY-KPI-003: agree the presentation with the supervisor, preferably extending existing reports/System Status instead of adding a monitoring platform. Show observed usage, budget, headroom, freshness and test context. Preserve existing coverage KPIs and keep historical experimental numbers distinct from current-host measurements. No account data or keys in exported metrics.

## Material-protection planning handoff

The valuation prerequisite is tracked in #132/#133. Consult its actual acceptance evidence before releasing dependent work; do not repeat an old zero-valued-bound-stock investigation without checking the current code. Live-account profit and Oracle capacity require their own evidence.

Confirmed intent and feature-level criteria: `docs/ROADMAP.md`, Milestone 2; domain contract: `docs/DOMAIN_SPEC.md` section 9.1; UI contract: `docs/FRONTEND_UX_GUIDELINES.md` section 2.1. Core product questions are resolved. Draft contracts still require planner refinement and supervisor release; they are not implementation authorization.

Directly relevant code: `frontend/src/account/BankScreen.vue`, `MaterialsScreen.vue`, `MaterialSelectionBar.vue`; `frontend/src/materials/materialProtectionState.ts`; `frontend/src/crafting/ProfitSettingsForm.vue`, `useCraftingProfit.ts`; `backend/src/main/java/craft/CraftingSettings.java`, `application/CraftingProfitService.java`, `repo/InventoryRepository.java`. Current settings include independent Bank/material-storage exclusions in addition to legacy global exclusions. Source-specific selection is intentional: unselected stores and character inventory remain usable.

Planner must preserve: all copies within a selected store unavailable, replacements allowed under existing acquisition rules; recursive availability and intermediate alternatives; effective source settings in list/detail/calculation identity; per-page pending/committed selection; exact preset membership and category placement; verified-account retention and account-switch cleanup; stale-response handling; default activation and compact UX feedback. Do not alter cached inventory rows to represent a preference. Any result cache must distinguish effective exclusion settings.

Use bounded item-ID sets per store and existing account/request boundaries, with no new service. Browser-local, per-account retention is approved; cross-device synchronization is not needed. Record functional and CPU/memory/latency evidence rather than promising that the feature is free.

## Characters planning handoff

Milestone 3 scope is in `docs/DOMAIN_SPEC.md` section 56; UI intent is in `docs/FRONTEND_UX_GUIDELINES.md` section 2.2. Prioritize equipment so users can identify ascended/legendary items and gear gaps, then inventory and builds; story requires API feasibility. Discovered recipes are out and map completion is deferred. The supplied screenshots are interaction references, not a requirement to replicate all fields or controls.

Current character services include core summaries, bag manifests, saved equipment/build tabs, shared reference metadata and item/upgrade projections. Audit those existing publications and account boundaries before adding another fetch/storage path; the original flat-record feasibility assessment is historical. Equipment references must never duplicate owned crafting stock.

ArenaNet's [template API announcement](https://en-forum.guildwars2.com/topic/65255-api-updates-december-18-2019/) documents equipment/build tabs and warns that several tabs may reference one owned item. It is historical API evidence, not a substitute for checking the current schema. Keep template associations distinct from actual inventory quantities. Product Owner deferred Story after the API coverage assessment; do not substitute backstory or partial quest history.

Template scope is confirmed: all saved available tabs in original slot order, initially selecting the in-game active tab. Equipment selectors now sit right of the Equipment/Inventory navigation; Builds keeps adjacent saved-build tabs. Selections are independent and read-only. Overview, Equipment and Inventory layout approval is complete; Builds is implemented and its layout was visually accepted by the Product Owner on 2026-11-08; final Oracle qualification remains outstanding. Reuse shared metadata, bounded account data and lazy details; include the feature in final Oracle qualification.

## System Status and inventory-refresh handoff

The Product Owner wants owner-only global operations/details, ordinary users' own connection/data-age/refresh controls and generic shared health, and automatic stale refresh in Bank/Materials. Owning contracts are `DOMAIN_SPEC.md` section 57, `FRONTEND_UX_GUIDELINES.md` sections 2.3–2.4 and `TARGET_ARCHITECTURE.md` section 8.3. Audit existing endpoints and task/diagnostic projections before claiming the boundary is enforced.

The paired feature pages have removed manual/reload controls and use scoped automatic refresh through the shared task mechanism. Reconcile this delivered path rather than reimplementing the old button-removal story. Verify coverage, missing grants, retained equipment and finite recovery; opening Bank, Materials or Characters must not request unrelated sources. Full-account refresh remains explicit in System Status.

Resolve the owner via a configured, verified account GUID; do not request/store a second API key for admin identification. Actual owner GUID is not yet provisioned by this setup. No broad RBAC service, user-admin editor, impersonation or paid monitoring product is requested. Full TP administration needs an explicit bounded tradable-catalog path, not an alias for refreshing a single user's price demand. Keep diagnostic KPIs owner-only where detailed; ordinary users receive generic health.

## Handoff / open decisions

- Hosting: RESOLVED by Product Owner, 2026-11-07: final product must fit Oracle Always Free. Target and verified quota references are in `docs/ROADMAP.md`; actual host access, available tenancy quota and workload/headroom remain to be established.
- Intended concurrent-user workload and acceptable latency/headroom: planner proposes against the Oracle resource baseline; Product Owner approves material tradeoffs.
- Material protection: core product/UI/retention answers are confirmed. T1–T6 presets select intermediate materials only, not basic materials or gemstones; an additional preset selects clovers, coins, ectoplasm and obsidian shards. Exact membership must be verified; the extra preset's visible label is confirmed as "Clovers, coins, ecto & obsidian". Presets preselect pending checkboxes and do not commit automatically.
- Characters: equipment first, inventory/builds next; Story is deferred, discovered recipes excluded and map completion deferred. Review genuinely unspecified visual choices without reopening approved tabs and roster/detail behavior. Drafts do not authorize implementation.
- Full-catalog administration: RESOLVED by Product Owner, 2026-11-08: item and recipe actions re-download every detail in the captured authoritative catalog, including unchanged IDs, to pick up in-place edits. Full TP refresh covers the applicable tradable catalog independently of freshness. Ordinary scheduler checks and feature-scoped demand retain their existing behavior. Binding contract and planner addendum: #180.
- Current release sequence: #180 consumes the merged #152 owner/task boundary; final owner Status UI and bounded KPI planning follow. Keep one approved implementation release at a time. Planning tasks must use a recent accepted checkout; old setup PR #131 is historical. PR-triggered runs marked action_required need supervisor approval for the trusted exact head; dispatched CI does not replace branch-protection checks. Real Oracle access is required for final qualification, not for locally testable feature work.
