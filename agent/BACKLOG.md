# Active planning backlog

**Status: ACTIVE SUPERVISION. Execution authorized by the Product Owner on 2026-11-07.**

Authorization: start the agreed workflow, supervise progress and stop only for necessary product/UI decisions or missing access. Supervisor: interactive Codex. Planner: Codex on GitHub. Programmer: Claude. Release one approved story at a time; no new paid API integration is authorized.

Initial audit: no open PRs; old open issues #21, #60, #111 and #116 remain. Removed `auto` from old coverage story #111 so the selector cannot run it ahead of the new correctness priority. Historical requests remain open; they are not newly activated. Current step: publish the role/specification setup and obtain the GitHub planner's first story contract for STORY-VALUE-001. No product implementation has been dispatched at this point.

## Activation and state

- Planner drafts/refines; supervisor reviews and releases. Only approved GitHub issues become executable work for Claude.
- States: DRAFT, BLOCKED, READY, ACTIVE, REVIEW, DONE. Draft rows require supervisor release; the active handoff is recorded above.
- READY requires an approved contract, resolved decisions and merged dependencies. DONE requires accepted implementation, actual verification and merged evidence.
- Adding the existing `auto` issue label makes a story eligible for the current selector/merge gate; only do so after explicit execution authorization and supervisor review. Markdown states are not an enforced workflow switch.
- When execution starts, record authorization here, audit old open issues/PRs, link approved issues to the entries, and reconcile status from GitHub. Do not silently activate historical queued issues.

## Proposed order

| ID | Outcome / responsible role | Dependencies | State |
| --- | --- | --- | --- |
| PLAN-HOST-001 | Planner verifies Oracle ARM64 target resources and whole-stack free-tier budget | Actual tenancy/host facts needed for qualification; provider is decided | DRAFT |
| PLAN-KPI-001 | Planner inventories existing measurements and proposes KPI contracts and workloads | PLAN-HOST-001 for provider-specific limits; inventory can be drafted independently | DRAFT |
| PLAN-VALUE-001 | Planner prepares correctness contract for recursive valuation of owned bound/non-tradable materials, including daily materials | DOMAIN_SPEC.md sections 11.1–11.2; inspect current bound-stock bypass | DRAFT |
| STORY-VALUE-001 | Claude corrects valuation and verifies list/detail cost/profit agreement, cycle handling, yields and performance | Approved PLAN-VALUE-001 contract | DRAFT |
| STORY-KPI-001 | Claude adds only missing resource measurements and machine-readable budget comparison | Approved PLAN-KPI-001 | DRAFT |
| STORY-KPI-002 | Claude adds repeatable desktop and multi-account/fault qualification workloads | STORY-KPI-001 | DRAFT |
| STORY-KPI-003 | Claude exposes a concise capacity/health summary and documented reproduction path | STORY-KPI-001, STORY-KPI-002 | DRAFT |
| REVIEW-KPI-001 | Supervisor validates real target-host evidence and reports free-tier fit/gaps | Previous KPI stories, target-host access | BLOCKED |
| PLAN-FEATURE-001 | Planner refines material-protection stories and prepares concrete UI review | Confirmed domain/UX contracts; inspect current code | DRAFT |
| STORY-MAT-001 | Claude implements approved backend settings/validation and recursive calculation restrictions, with tests | Approved PLAN-FEATURE-001 story contract; STORY-VALUE-001 | DRAFT |
| STORY-MAT-002 | Claude implements tile checkboxes, confirmation, tier presets, per-account browser retention and Profit toggle/warning/links | UI design review, STORY-MAT-001 request contract | DRAFT |
| STORY-MAT-003 | Claude completes list/detail consistency, navigation/account-switch regression and feature overhead evidence | STORY-MAT-001, STORY-MAT-002; comparable KPI workload | DRAFT |
| PLAN-CHAR-001 | Planner audits character API/storage coverage and prepares equipment-first UI review with in-game-style tabs | Confirmed DOMAIN_SPEC.md section 56 and UX section 2.2 | DRAFT |
| STORY-CHAR-001 | Claude adds reviewed scoped character read/refresh contracts and missing data/metadata support | Approved PLAN-CHAR-001 data contract | DRAFT |
| STORY-CHAR-002 | Claude implements character roster and equipment-focused detail view | STORY-CHAR-001, approved UI design | DRAFT |
| STORY-CHAR-003 | Claude implements character inventory and build details with navigation and account-isolation regressions | STORY-CHAR-001, STORY-CHAR-002; approved template scope | DRAFT |
| STORY-CHAR-004 | Claude adds agreed story details supported by the API | PLAN-CHAR-001 feasibility evidence and approved story scope | BLOCKED |
| REVIEW-CHAR-001 | Supervisor reviews correctness, desktop behavior and Oracle resource evidence including both new features | Character/material stories and KPI qualification workloads | DRAFT |
| PLAN-STATUS-001 | Planner audits current global routes/status projections and defines owner/admin and automatic inventory-freshness contracts | DOMAIN_SPEC.md section 57; TARGET_ARCHITECTURE.md section 8.3 | DRAFT |
| STORY-STATUS-001 | Claude implements backend owner authorization and owner/generic status and task access contracts | Approved PLAN-STATUS-001; verified owner GUID/config before enabling privileges | DRAFT |
| STORY-STATUS-002 | Claude implements ordinary versus owner System Status views and global refresh controls | STORY-STATUS-001, reviewed UX; bounded full-catalog refresh contract | DRAFT |
| STORY-STATUS-003 | Claude replaces Bank/Materials refresh buttons with retained-snapshot automatic stale refresh and updating/failure hints | Approved PLAN-STATUS-001; shared freshness/task coverage and grant policy | DRAFT |

IDs are reserved draft contracts, not GitHub issue numbers. No implementation issue has been created. Existing selector compatibility is retained by the `STORY-KPI-NNN` naming; publication/labels still require release.

## KPI story drafting criteria

For STORY-KPI-001: identify missing measurements before adding instrumentation; report per-service and whole-stack CPU/memory, JVM heap/GC, database connections/storage, requests/latency/errors, upstream traffic and cache/refresh activity where applicable. Include units, boundaries, duration, commit, workload, environment and unavailable reasons. Compare only metrics supported by the selected hosting service's budget. Never silently turn an unmeasurable value into zero or claim a pass.

For STORY-KPI-002: real public catalog topology with synthetic independent accounts, cold/warm cases, calculations, refreshes, sustained concurrent use, restart recovery and simulated slow/429/5xx upstreams. Preserve domain results and account separation; no live ArenaNet stress testing. Separate browser measurements from server and provider usage. Proposed load tiers, duration and success thresholds require an approved contract rather than invention by Claude.

For STORY-KPI-003: agree the presentation with the supervisor, preferably extending existing reports/System Status instead of adding a monitoring platform. Show observed usage, budget, headroom, freshness and test context. Preserve existing coverage KPIs and keep historical experimental numbers distinct from current-host measurements. No account data or keys in exported metrics.

## Material-protection planning handoff

Correctness first: `CraftingResolver.resolveNeed` currently calculates fallback owned value only for `usedSellable()`; usable bound stock bypasses that fallback. The supplied Mithrillium screenshot shows zero value. Reproduce the affected path and prepare STORY-VALUE-001 against the clarified domain rule before releasing material protection. Existing recursive fallback helpers are present; reuse or correct them rather than assuming a wholly new valuation subsystem is needed. No corrected profit or runtime cost has been measured yet.

Confirmed intent and feature-level criteria: `docs/ROADMAP.md`, Milestone 2; domain contract: `docs/DOMAIN_SPEC.md` section 9.1; UI contract: `docs/FRONTEND_UX_GUIDELINES.md` section 2.1. Core product questions are resolved. Draft contracts still require planner refinement and supervisor release; they are not implementation authorization.

Directly relevant current code: `frontend/src/account/BankScreen.vue`, `MaterialsScreen.vue`, `InventoryTile.vue`, `BankSlotTile.vue`; `frontend/src/crafting/ProfitSettingsForm.vue`, `useCraftingProfit.ts`; `backend/src/main/java/craft/CraftingSettings.java`; `backend/src/main/java/application/CraftingProfitService.java`. Existing owned inventory combines bank, material storage and character inventories (`docs/DOMAIN_SPEC.md` section 9); excluding only a displayed bank stack could leave other copies available. Current settings contain no protected-item list.

Planner must cover before release: all owned copies unavailable, replacements allowed under existing acquisition rules; recursive availability and intermediate alternatives; effective settings in list/detail/calculation identity; pending versus committed selection; exact tier-preset membership; verified-account browser retention and account-switch cleanup; stale-response handling; default toggle behavior and visible UX feedback. Do not delete or alter cached inventory rows to represent a preference. Any future result cache must distinguish effective exclusion settings.

Prefer one bounded item-ID set and existing account/request boundaries, with no new polling or service. Browser-local, per-account retention is approved; server preference storage/cross-device synchronization is not needed. Record functional and CPU/memory/latency evidence rather than promising that the feature is free.

## Characters planning handoff

Milestone 3 scope is in `docs/DOMAIN_SPEC.md` section 56; UI intent is in `docs/FRONTEND_UX_GUIDELINES.md` section 2.2. Prioritize equipment so users can identify ascended/legendary items and gear gaps, then inventory and builds; story requires API feasibility. Discovered recipes are out and map completion is deferred. The supplied screenshots are interaction references, not a requirement to replicate all fields or controls.

Existing `sync/CharacterSync.java`, `model/CharacterInfo.java`, `model/CharacterItemRow.java`, `parser/CharacterItemsParser.java` and `repo/CharacterRepository.java` provide reusable identity/crafting and flat bag/equipment data. The inspected records do not retain a full equipment/build-template representation or upgrade/stat details; do not assume new views can be delivered from existing rows alone. Verify API schema/version, permissions, missing metadata and account-scoped publication before choosing storage extensions.

ArenaNet's [template API announcement](https://en-forum.guildwars2.com/topic/65255-api-updates-december-18-2019/) documents equipment/build tabs and warns that several tabs may reference one owned item. It is historical API evidence, not a substitute for checking the current schema. Keep template associations distinct from actual inventory quantities. Story-journal coverage remains unverified; backstory is not automatically equivalent.

Template scope is confirmed: all saved available equipment/build tabs, adjacent like the in-game Hero panel, preserving original slot order, with the in-game active tab initially selected. Build/equipment selections are independent and read-only; inspecting another tab must not pretend to activate it in the game. Concrete overview fields and equipment layout still go through UI review before implementation. Prefer cached shared item/skill metadata, bounded account data and on-demand detail loading; no additional services or background poller. Include the feature in final Oracle resource qualification.

## System Status and inventory-refresh handoff

The Product Owner wants owner-only global operations/details, ordinary users' own connection/data-age/refresh controls and generic shared health, and automatic stale refresh in Bank/Materials. Owning contracts are `DOMAIN_SPEC.md` section 57, `FRONTEND_UX_GUIDELINES.md` sections 2.3–2.4 and `TARGET_ARCHITECTURE.md` section 8.3. Audit existing endpoints and task/diagnostic projections before claiming the boundary is enforced.

Current grey "Reload saved" buttons only reread SQL snapshots; the red account-refresh component triggers ArenaNet account synchronization. Remove both from Bank/Materials under the new contract. Reuse `useSyncOperations`, existing account freshness/source coverage and retained-view behavior, rather than layering another polling/refresh coordinator. In-flight refresh admission must be shared with Profit/System Status. Cover missing grants and avoid an automatic full-account refresh unnecessarily requiring unrelated Luck permissions.

Resolve the owner via a configured, verified account GUID; do not request/store a second API key for admin identification. Actual owner GUID is not yet provisioned by this setup. No broad RBAC service, user-admin editor, impersonation or paid monitoring product is requested. Full TP administration needs an explicit bounded tradable-catalog path, not an alias for refreshing a single user's price demand. Keep diagnostic KPIs owner-only where detailed; ordinary users receive generic health.

## Handoff / open decisions

- Hosting: RESOLVED by Product Owner, 2026-11-07: final product must fit Oracle Always Free. Target and verified quota references are in `docs/ROADMAP.md`; actual host access, available tenancy quota and workload/headroom remain to be established.
- Intended concurrent-user workload and acceptable latency/headroom: planner proposes against the Oracle resource baseline; Product Owner approves material tradeoffs.
- Material protection: core product/UI/retention answers are confirmed and recorded in the owning domain/UX documents. Story and concrete UI review remain preparation work.
- Characters: feature and in-game-style equipment/build tab scope confirmed; equipment first, inventory/builds next, story subject to API coverage, discovered recipes excluded and map completion deferred. Concrete UI design remains review work. Drafts do not authorize implementation.
- Next authorized action: publish/review the setup and invoke the GitHub planner for the valuation correction; release Claude only after the bounded contract is accepted. Follow with the remaining agreed work in dependency order. Request Oracle host details only when required for qualification.
