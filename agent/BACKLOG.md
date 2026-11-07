# Active planning backlog

**Status: ACTIVE SUPERVISION. Execution authorized by the Product Owner on 2026-11-07.**

Authorization: start the agreed workflow, supervise progress and stop only for necessary product/UI decisions or missing access. Supervisor: interactive Codex. Planner: Codex on GitHub. Programmer: Claude. Release one approved story at a time; no new paid API integration is authorized.

Historical migration context is in setup PR [#131](https://github.com/Nebettadjsr/GW2/pull/131). Reconcile legacy issues #21, #60, #111 and #116 on GitHub before selecting them; their presence does not authorize release.

Planning handoff: the Characters sequence is Inventory UI #162, Builds core #161, referenced Builds metadata/images #164, then Builds UI #166. Release each only after its actual merged dependencies and contract have been reviewed. The full trait-row choice is resolved in #164/#166; retain their bounded specialization-choice metadata scope. System Status owner boundary #152 and the remaining KPI contracts follow. Earlier material contracts remain linked below for context. Read GitHub issues, PRs, CI and reviews for execution state and evidence before release; this file does not mirror completion, merge hashes, test outcomes or running attempts. Local measurement evidence is not Oracle qualification.

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
| PLAN-FEATURE-001 | Planner refines material-protection stories and prepares concrete UI review | Confirmed domain/UX contracts; inspect current code | DRAFT |
| STORY-MAT-004 | Claude exposes the verified account GUID additively in session responses for browser preference partitioning | STORY-KPI-001; approved #136 | PUBLISHED — #136 |
| STORY-MAT-001 | Claude implements backend settings/validation and recursive calculation restrictions, with real list/detail and overhead evidence | STORY-VALUE-001, STORY-KPI-001, STORY-MAT-004; approved #138 | PUBLISHED — #138 |
| STORY-MAT-002 | Claude adds per-account browser persistence, canonical state and pending/committed selection foundation | STORY-MAT-001, STORY-MAT-004; approved #139 | PUBLISHED — #139; next proposed release |
| STORY-MAT-003 | Claude adds Bank/Materials tile checkboxes, pending edits and explicit confirmation | STORY-MAT-002; approved draft #143 | PUBLISHED — #143; later release |
| STORY-MAT-005 | Claude adds verified T1–T6 intermediate-material presets and the four-item extra preset | STORY-MAT-003; membership and extra label confirmed | DRAFT |
| STORY-MAT-006 | Claude adds Profit activation/count, empty-selection warning, links and request integration | STORY-MAT-001, STORY-MAT-003; approved UX | DRAFT |
| STORY-MAT-007 | Claude verifies integrated navigation, refresh, account switching and list/detail behavior | STORY-MAT-005, STORY-MAT-006; comparable KPI workload | DRAFT |
| PLAN-CHAR-001 | Planner audits character API/storage coverage and prepares equipment-first UI review with in-game-style tabs | Confirmed DOMAIN_SPEC.md section 56 and UX section 2.2 | DRAFT |
| STORY-CHAR-001 | Claude adds compact scoped roster and selected-character core/crafting reads using existing publication state | Approved draft #141; material work first in supervisor release order | PUBLISHED — #141; later release |
| STORY-CHAR-002 | Claude implements character roster and equipment-focused detail view | STORY-CHAR-001, approved UI design | DRAFT |
| STORY-CHAR-003 | Claude implements character inventory and build details with navigation and account-isolation regressions | STORY-CHAR-001, STORY-CHAR-002; approved template scope | DRAFT |
| STORY-CHAR-004 | Reserved character Story outcome | A new Product Owner decision is required to resume | DEFERRED — Product Owner confirmed 2026-11-07 |
| REVIEW-CHAR-001 | Supervisor reviews correctness, desktop behavior and Oracle resource evidence including both new features | Character/material stories and KPI qualification workloads | DRAFT |
| PLAN-STATUS-001 | Planner audits current global routes/status projections and defines owner/admin and automatic inventory-freshness contracts | DOMAIN_SPEC.md section 57; TARGET_ARCHITECTURE.md section 8.3 | DRAFT |
| STORY-STATUS-001 | Claude implements backend owner authorization and owner/generic status and task access contracts | Approved PLAN-STATUS-001; verified owner GUID/config before enabling privileges | DRAFT |
| STORY-STATUS-002 | Claude implements ordinary versus owner System Status views and global refresh controls | STORY-STATUS-001, reviewed UX; bounded full-catalog refresh contract | DRAFT |
| STORY-STATUS-003 | Claude replaces Bank/Materials refresh buttons with retained-snapshot automatic stale refresh and updating/failure hints | Approved PLAN-STATUS-001; shared freshness/task coverage and grant policy | DRAFT |

Story IDs are distinct from GitHub issue numbers. Published contracts are linked above; unpublished rows remain drafts. Never reuse a published ID for a different outcome. Existing selector compatibility is retained by `STORY-AREA-NNN` naming; publication/labels still require supervisor release.

## KPI story drafting criteria

For STORY-KPI-001: extend the existing container report only with whole-host Linux CPU/memory observations, architecture/boundary/provenance, safe 2 OCPU/12 GB comparison and explicit unavailable/not-qualified behavior. Preserve warning-only CI. JVM details, storage/log accounting and transfer accounting are reserved for STORY-KPI-004, STORY-KPI-005 and STORY-KPI-006 respectively; internal Docker traffic is not billable-egress evidence.

For STORY-KPI-002: real public catalog topology with synthetic independent accounts, cold/warm cases, calculations, refreshes, sustained concurrent use, restart recovery and simulated slow/429/5xx upstreams. Preserve domain results and account separation; no live ArenaNet stress testing. Separate browser measurements from server and provider usage. Proposed load tiers, duration and success thresholds require an approved contract rather than invention by Claude.

For STORY-KPI-003: agree the presentation with the supervisor, preferably extending existing reports/System Status instead of adding a monitoring platform. Show observed usage, budget, headroom, freshness and test context. Preserve existing coverage KPIs and keep historical experimental numbers distinct from current-host measurements. No account data or keys in exported metrics.

## Material-protection planning handoff

The valuation prerequisite is tracked in #132/#133. Consult its actual acceptance evidence before releasing dependent work; do not repeat an old zero-valued-bound-stock investigation without checking the current code. Live-account profit and Oracle capacity require their own evidence.

Confirmed intent and feature-level criteria: `docs/ROADMAP.md`, Milestone 2; domain contract: `docs/DOMAIN_SPEC.md` section 9.1; UI contract: `docs/FRONTEND_UX_GUIDELINES.md` section 2.1. Core product questions are resolved. Draft contracts still require planner refinement and supervisor release; they are not implementation authorization.

Directly relevant current code: `frontend/src/account/BankScreen.vue`, `MaterialsScreen.vue`, `InventoryTile.vue`, `BankSlotTile.vue`; `frontend/src/crafting/ProfitSettingsForm.vue`, `useCraftingProfit.ts`; `backend/src/main/java/craft/CraftingSettings.java`; `backend/src/main/java/application/CraftingProfitService.java`. Existing owned inventory combines bank, material storage and character inventories (`docs/DOMAIN_SPEC.md` section 9); excluding only a displayed bank stack could leave other copies available. Current settings contain no protected-item list.

Planner must cover before release: all owned copies unavailable, replacements allowed under existing acquisition rules; recursive availability and intermediate alternatives; effective settings in list/detail/calculation identity; pending versus committed selection; exact tier-preset membership; verified-account browser retention and account-switch cleanup; stale-response handling; default toggle behavior and visible UX feedback. Do not delete or alter cached inventory rows to represent a preference. Any future result cache must distinguish effective exclusion settings.

Prefer one bounded item-ID set and existing account/request boundaries, with no new polling or service. Browser-local, per-account retention is approved; server preference storage/cross-device synchronization is not needed. Record functional and CPU/memory/latency evidence rather than promising that the feature is free.

## Characters planning handoff

Milestone 3 scope is in `docs/DOMAIN_SPEC.md` section 56; UI intent is in `docs/FRONTEND_UX_GUIDELINES.md` section 2.2. Prioritize equipment so users can identify ascended/legendary items and gear gaps, then inventory and builds; story requires API feasibility. Discovered recipes are out and map completion is deferred. The supplied screenshots are interaction references, not a requirement to replicate all fields or controls.

Existing `sync/CharacterSync.java`, `model/CharacterInfo.java`, `model/CharacterItemRow.java`, `parser/CharacterItemsParser.java` and `repo/CharacterRepository.java` provide reusable identity/crafting and flat bag/equipment data. The inspected records do not retain a full equipment/build-template representation or upgrade/stat details; do not assume new views can be delivered from existing rows alone. Verify API schema/version, permissions, missing metadata and account-scoped publication before choosing storage extensions.

ArenaNet's [template API announcement](https://en-forum.guildwars2.com/topic/65255-api-updates-december-18-2019/) documents equipment/build tabs and warns that several tabs may reference one owned item. It is historical API evidence, not a substitute for checking the current schema. Keep template associations distinct from actual inventory quantities. Product Owner deferred Story after the API coverage assessment; do not substitute backstory or partial quest history.

Template scope is confirmed: all saved available equipment/build tabs, adjacent like the in-game Hero panel, preserving original slot order, with the in-game active tab initially selected. Build/equipment selections are independent and read-only; inspecting another tab must not pretend to activate it in the game. Concrete overview fields and equipment layout still go through UI review before implementation. Prefer cached shared item/skill metadata, bounded account data and on-demand detail loading; no additional services or background poller. Include the feature in final Oracle resource qualification.

## System Status and inventory-refresh handoff

The Product Owner wants owner-only global operations/details, ordinary users' own connection/data-age/refresh controls and generic shared health, and automatic stale refresh in Bank/Materials. Owning contracts are `DOMAIN_SPEC.md` section 57, `FRONTEND_UX_GUIDELINES.md` sections 2.3–2.4 and `TARGET_ARCHITECTURE.md` section 8.3. Audit existing endpoints and task/diagnostic projections before claiming the boundary is enforced.

Current grey "Reload saved" buttons only reread SQL snapshots; the red account-refresh component triggers ArenaNet account synchronization. Remove both from Bank/Materials under the new contract. Reuse `useSyncOperations`, existing account freshness/source coverage and retained-view behavior, rather than layering another polling/refresh coordinator. In-flight refresh admission must be shared with Profit/System Status. Cover missing grants and avoid an automatic full-account refresh unnecessarily requiring unrelated Luck permissions.

Resolve the owner via a configured, verified account GUID; do not request/store a second API key for admin identification. Actual owner GUID is not yet provisioned by this setup. No broad RBAC service, user-admin editor, impersonation or paid monitoring product is requested. Full TP administration needs an explicit bounded tradable-catalog path, not an alias for refreshing a single user's price demand. Keep diagnostic KPIs owner-only where detailed; ordinary users receive generic health.

## Handoff / open decisions

- Hosting: RESOLVED by Product Owner, 2026-11-07: final product must fit Oracle Always Free. Target and verified quota references are in `docs/ROADMAP.md`; actual host access, available tenancy quota and workload/headroom remain to be established.
- Intended concurrent-user workload and acceptable latency/headroom: planner proposes against the Oracle resource baseline; Product Owner approves material tradeoffs.
- Material protection: core product/UI/retention answers are confirmed. T1–T6 presets select intermediate materials only, not basic materials or gemstones; an additional preset selects clovers, coins, ectoplasm and obsidian shards. Exact membership must be verified; the extra preset's visible label is confirmed as "Clovers, coins, ecto & obsidian". Presets preselect pending checkboxes and do not commit automatically.
- Characters: equipment first, inventory/builds next; Story is deferred, discovered recipes excluded and map completion deferred. Review genuinely unspecified visual choices without reopening approved tabs and roster/detail behavior. Drafts do not authorize implementation.
- Next authorized planning sequence: keep #161, #164 and #166 reconciled to actual preceding Character contracts through the GitHub planner, with one approved implementation release at a time. Planning tasks must use a recent accepted checkout; old setup PR #131 is historical. PR-triggered runs marked action_required need supervisor approval for the trusted exact head; dispatched CI does not replace branch-protection checks. Real Oracle access is required for final qualification, not for locally testable feature work.
