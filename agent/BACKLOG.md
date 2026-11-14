# Active planning backlog

**CURRENT AUTHORIZATION — 2026-11-14, supervision resumed:** After the final direct key-help copy
correction, the Product Owner accepts the local UI and asks to continue the to-do list. Resume one
dependency-complete implementation at a time: publish the accepted paired #199/#200 refinements, check
CI/review, finish the #201/#203 history corrections, and release the bounded deletion contracts from
#204 once their prerequisites are ready. #202 remains dependent on the history foundation. The deletion
UI must remove "planned" from the safety explanation when its button is actually functional, keeping
the accepted copy and layout. This resumes supervision/feature work, not VM or load-test execution.

**Direct UI pairing — 2026-11-14:** The Product Owner explicitly asks interactive Codex to code the
current guide revision itself, without delegating to Claude. Scope is the API-key creation dialog:
four steps, an inline ArenaNet link, a spaced Steam/Epic hint, recommend all permissions without chips,
bold Copy & Paste, a separate Connect step, and a footer link to the safety explanation. Keep the true
modal/no-shift behavior. Other feature implementation stays held; this is not general workflow resumption.
The following safety-dialog revision is direct coding too: explain read-only protection, concretely name
password/email/payment exclusions, and separate browser key retention, cached game data linked by account
ID, and the API-limit/calculation reasons for caching. Remove the Replace-key explanation; keep details
collapsed initially and retained-cache behavior truthful. Do not claim that account-linked data is anonymous.
The next owner copy pass adds five short safety facts, per-tab key lifetime, ArenaNet-verified identity and
reuse of cached data with a new key for the same account. Prepare Delete wording, marked planned until the
action exists; this copy request does not activate deletion implementation. Keep browser-restoration and
the existing 15-minute session limit accurate, rather than promising immediate key removal or revocation.
The safety dialog is 30% wider (39rem), still constrained to the viewport, at the owner's request.

**Current handoff — 2026-11-14, UI pairing requested:** Finish the in-flight #199 functional correction,
then stop further implementation dispatch and return to the Product Owner for System Status UI rework.
Keep its local preview available and its PR unmerged pending visual approval. Independent owner-history
and deletion work remains unfinished; its contracts/review findings stay on GitHub for later resumption.
This handoff is not authorization to accept UI, start another story or restart resource measurements.

**Product Owner follow-up — 2026-11-14, System Status:** The API-key controls belong in one themed card:
compact help links replace routine connection prose, the input has its own row, and action buttons are
underneath. Help must be a small centered modal over a dimmed/blurred background, with no layout shift.
Use a short headline, separated key facts/numbered steps, optional collapsed detail and a separate footer;
the rejected inline paragraph block is not an accepted baseline. These instructions are binding on #199.
The owner now also requests a separate delete-data action alongside key removal. Plan actual deletion of
the current verified account's cached app data in [#204](https://github.com/Nebettadjsr/GW2/issues/204),
preserving other accounts/shared catalogs and fencing old background work. This extends the earlier scope;
it does not authorize deleting the owner's live cached data during implementation or verification.

**CURRENT AUTHORIZATION — 2026-11-14, System Status:** The owner requests compact API-key creation and
read-only/privacy help, an input visibility toggle, truthful replace/disconnect wording, and owner-only
database-size and concise activity/performance history. Distinct GW2 accounts active within five minutes
is confirmed; preserve the existing last-60-minute count separately. Reuse Oracle infrastructure monitoring
instead of logging the same CPU/memory/host metrics again. Include practical hosted log retrieval/export.
Use native planning and one scoped implementation at a time; retain final local UI review. The request does
not ask for account-data deletion: current Disconnect ends access and clears the browser key, without
deleting cached database rows. No resumption of load tests, general roadmap work or paid services is implied.

Native planning handoff: [#198](https://github.com/Nebettadjsr/GW2/issues/198). Supervisor-approved
connection-help contract: [STORY-KEYHELP-001 / #199](https://github.com/Nebettadjsr/GW2/issues/199).
The amended native owner-history contract is approved as backend foundation
[STORY-OPS-001 / #201](https://github.com/Nebettadjsr/GW2/issues/201), followed by dependent owner
presentation/export/runbook [STORY-OPS-002 / #202](https://github.com/Nebettadjsr/GW2/issues/202).
UI work retains the local Product Owner visual-review gate.
No unrelated held story is reactivated. GitHub owns coding/review progress and any rework findings.

**CURRENT AUTHORIZATION — 2026-11-14:** The owner explicitly authorizes running the tests and starting
Docker and the local VM. Resume the browser/VM resource tests: collect a new baseline, verify host contention,
then run bounded concurrency experiments with the available real accounts. Keep account count distinct from
browser-session count and provisional scripted behavior distinct from recorded owner behavior. Prior readings
from 2026-11-13 remain invalid as performance baselines because the owner was playing GW2 on the host.
Stop owned measurement processes and shut down the test VM after the authorized run; preserve test evidence.
This authorization supersedes the preparation-only restriction below for this test session only.

Local measurement evidence and remaining qualification gaps are in the
[2026-11-14 resource-test report](../docs/investigations/2026-11-14-resource-test-results.md).
That bounded session has ended with the test VM Off and owned observers/runners stopped. It does not
authorize another unattended run or release unrelated implementation work.

**Previous owner correction — 2026-11-13: PREPARATION ONLY.** The owner clarified that preparing for measurements did
not authorize starting a VM or running measurements. The local `GW2-resource-test` VM has been shut down
and the identified test runners stopped. Do not start the VM, browser journeys, measurements or load tests
without a new explicit instruction. Readiness language below is historical and supplies no execution authority.
The owner was actively playing GW2 on this same host during the runs; the resulting performance numbers
are contended observations and must not be used as an application baseline or for capacity decisions.
Preserve the prepared tooling and stopped VM for later use. This correction supersedes conflicting scope
interpretations recorded below.

**Status: LIMITED EXECUTION resumed by the Product Owner on 2026-11-13: simplify measurement tooling, prepare recorded browser/VM tests, fix #188 and relevant functional prerequisites.**

The Product Owner's latest 2026-11-13 instruction extends this scope through runnable local test readiness: continue without stopping for routine implementation choices, prepare at least three browser journeys (Crafting, Bank/Materials, Characters), create and deploy the resource-constrained local VM, and verify bounded single-session dry runs with useful timing/resource output. This supersedes the earlier preparation-only restriction on VM creation. Capacity discovery remains the subsequent test, not a result to claim during preparation. No paid service or live ArenaNet stress is authorized; normal bounded account bootstrap uses the already authorized account key without exporting credentials.

The Product Owner resumed supervision on 2026-11-08 after approving the directly paired UI, including the final Builds page with Equipment-width trait strips and cropped skill pictures. Preserve that baseline and its reconciled tests/documentation while continuing the dependency-complete sequence below. This supersedes earlier draft layout proposals and shared-selection assumptions. No repeated approval is needed for the already accepted layouts.

Authorization: start the agreed workflow, supervise progress and stop only for necessary product/UI decisions or missing access. Supervisor: interactive Codex. Planner: Codex on GitHub. Programmer: Claude. Release one approved story at a time; no new paid API integration is authorized.

The Product Owner paused all work on 2026-11-12, superseding the earlier instruction to finish #184 first. The following 2026-11-13 instruction resumes only the stated preparation and functional fixes.

The 2026-11-13 follow-up explicitly resumes preparation/implementation: remove obsolete performance-only scaffolding while preserving regular correctness tests and reusable fixture/resource helpers; prepare lightweight opt-in action-to-final-data measurement and recording/replay of the owner's actual UI actions; fix the value-display issue #188 and verified functional prerequisites before capacity testing. The old #184 hybrid-driver completion requirement is superseded. The owner will provide more real account keys; no capacity claim or actual multi-account stress run is authorized by this preparation step. Include per-account database storage (separating shared catalog and allocation overhead) and backend request-rate/latency/error saturation measurements in the later test plan. One implementation story at a time, with normal CI/review and final local visual approval for #188. No paid API integration or unrelated optimization work.

Resource-test direction supplied on 2026-11-13: research the current Oracle Always Free allocation, run the complete deployed container stack inside a local VM constrained to that whole-system budget, and drive concurrent independent real browser sessions from outside the VM using scripted UI actions and realistic pauses. Correlate action-to-rendered-usability timings with VM/container/backend/database telemetry and report generator limits as well as application limits. Preserve the 2-second reference and representative account-data authorization. Existing hybrid protocol/probe experiments are exploratory inputs, not acceptance evidence for this new browser-driven VM strategy. Preparation is resumed as specified above; VM creation and actual stress execution need a concrete later scope. Local Intel VM limits approximate the resource envelope; final Oracle ARM/storage performance still needs target-host verification.

Historical migration context is in setup PR [#131](https://github.com/Nebettadjsr/GW2/pull/131). Reconcile legacy issues #21, #60, #111 and #116 on GitHub before selecting them; their presence does not authorize release.

Planning handoff: the Characters sequence is Inventory UI #162, Builds core #161, referenced Builds metadata/images #164, then Builds UI #166. Release each only after its actual merged dependencies and contract have been reviewed. The full trait-row choice is resolved in #164/#166; retain their bounded specialization-choice metadata scope. System Status owner boundary #152 and the remaining KPI contracts follow. Earlier material contracts remain linked below for context. Read GitHub issues, PRs, CI and reviews for execution state and evidence before release; this file does not mirror completion, merge hashes, test outcomes or running attempts. Local measurement evidence is not Oracle qualification.

## Immediate integration handoff

- Preserve the accepted UI baseline, including System Status owner administration and the regular-user saved account overview. The 2-second usable-page reference, realistic interactions and authorized representative account data remain selected. The 2026-11-13 VM/browser strategy supersedes #184 and unmerged PR #190. Hold #188 for final visual review; independent resource tooling in #193 can proceed against merged master without depending on its value-display DOM. Owner recordings and final capacity tests use the accepted product baseline. Actual capacity is a measurement outcome. Live full refreshes and final Oracle qualification remain separate work. Native planning handoffs, the keep/remove audit and dependency amendment are in #191; retain regular correctness tests and reusable fixtures/probes.

- #182 visual scope is accepted: own-account counts, one compact regular-user service line, and owner-only accounts/catalog/actions. Consult #182 / #183 for execution and final gate evidence; do not ask again for approval of an unchanged layout.
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
| STORY-KPI-007 | Claude adds representative offline account/catalog fixtures, expected results and an optional ignored local snapshot import | STORY-KPI-001 and accepted final feature baseline; native planner revision reviewed | PUBLISHED — #185; supervisor-approved contract; release after this planning reconciliation |
| STORY-KPI-002 | Historical hybrid capacity-driver contract | — | SUPERSEDED — #184 closed and PR #190 unmerged; do not revive |
| STORY-RESOLUTION-001 | Distinguish backend-projected value per item and inclusive total in both resolution trees | Existing shared resolution view | RELEASED — #188; local visual approval required before acceptance |
| STORY-RESOURCE-001 | Make legacy benchmarks manual-only and add an opt-in local recorder/final-render timer | Merged master; no dependency on #188 DOM or visual approval | PUBLISHED — #193, native dependency amendment in #191; no stress execution |
| STORY-RESOURCE-002 | Prepare three actual-app browser journeys and bounded VM/container/request observations | STORY-RESOURCE-001 | PUBLISHED — #196; native handoff in #191; local single-session readiness scope |
| STORY-RESOURCE-003 | Measure shared catalog and marginal account storage separately | Controlled disposable database; resource observation seams | DRAFT — physical allocation and row-data estimates stay distinct |
| STORY-RESOURCE-004 | Prepare offered/completed request rates, latency/error and saturation reporting | Recorded scenario mix and resource observation seams | DRAFT — requests, users and outbound GW2 calls stay distinct |
| STORY-RESOURCE-005 | Execute browser concurrency tests and Oracle qualification | Reviewed owner recordings, accepted product baseline, observation tools and explicit execution scope | BLOCKED — stress execution not yet authorized; Oracle access required for host qualification |
| STORY-KPI-003 | Claude exposes a concise capacity/health summary and documented reproduction path | STORY-KPI-001, STORY-KPI-002 | DRAFT |
| STORY-KPI-008 | Claude measures cold start, freshness/refresh expiry, fault, restart and owner-overlap behavior separately from steady warm capacity | STORY-KPI-002 | DRAFT — reserved; not activated |
| STORY-KPI-004 | Original JVM observation reservation | STORY-KPI-001 | DEFERRED — reconcile with STORY-RESOURCE-002 before release |
| STORY-KPI-005 | Original broad storage/log reservation | STORY-KPI-001 | DEFERRED — reconcile with STORY-RESOURCE-003 before release |
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
| PLAN-STATUS-001 | Owner/admin planning | DOMAIN_SPEC §57; TARGET_ARCHITECTURE §8.3 | ACCEPTED bounded contracts — #152, #180 and #182; both final layouts approved |
| STORY-STATUS-001 | Backend owner policy, existing global action/task authorization and generic/detailed status | Approved #152; synthetic principals suffice for implementation | PUBLISHED — #152; actual owner configuration needed only to enable privilege |
| STORY-STATUS-004 | Distinct owner-only full-item, full-recipe and full-tradable-TP operations | STORY-STATUS-001; resolved full-detail semantics and inspected owner/task seams | PUBLISHED — #180; actual owner provisioning and live refreshes remain separate |
| STORY-STATUS-002 | Ordinary/owner System Status UI | STORY-STATUS-001, STORY-STATUS-004, inspected actual routes/task/transport | PUBLISHED — #182; both final layouts accepted; consult GitHub for integration evidence |
| STORY-STATUS-003 | Reserved automatic inventory-freshness draft | #169 delivers paired page-scoped refresh | DEFERRED — reconcile #169 before releasing any distinct remaining gap |

Story IDs are distinct from GitHub issue numbers. Published contracts are linked above; unpublished rows remain drafts. Never reuse a published ID for a different outcome. Existing selector compatibility is retained by `STORY-AREA-NNN` naming; publication/labels still require supervisor release.

## KPI story drafting criteria

For STORY-KPI-001: extend the existing container report only with whole-host Linux CPU/memory observations, architecture/boundary/provenance, safe 2 OCPU/12 GB comparison and explicit unavailable/not-qualified behavior. Preserve warning-only CI. JVM details, storage/log accounting and transfer accounting are reserved for STORY-KPI-004, STORY-KPI-005 and STORY-KPI-006 respectively; internal Docker traffic is not billable-egress evidence.

For STORY-KPI-007: reuse the existing harness/report boundary, populated deterministic synthetic fixtures for default CI and an optional ignored consistent local snapshot of the authorized owner's data. Remap independent principals and preserve source/template/catalog relationships. No private data enters Git or report artifacts; no live exporter or ArenaNet load.

STORY-KPI-002 is historical. Its hybrid driver is not required for the replacement work. STORY-RESOURCE-001 first prepares real interaction recording and final-render timing; later qualification uses owner-reviewed recordings, independent browser sessions and the constrained whole-stack VM. Record every over-2-second sample and preserve correctness/account isolation. Keep cold/warm, refresh/recovery, generator and target-host boundaries explicit. No capacity claim follows from preparation alone.

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

Resolve the owner via a configured, verified account GUID; do not request/store a second API key for admin identification. Owner configuration is a local/deployment setting, never a committed real GUID; see CURRENT_STATE_SPEC for the process-environment/Spring configuration boundary. No broad RBAC service, user-admin editor, impersonation or paid monitoring product is requested. Full TP administration uses the delivered bounded tradable-catalog path. Keep diagnostic KPIs owner-only where detailed; ordinary users receive generic health.

## Handoff / open decisions

- Hosting: RESOLVED by Product Owner, 2026-11-07: final product must fit Oracle Always Free. Target and verified quota references are in `docs/ROADMAP.md`; actual host access, available tenancy quota and workload/headroom remain to be established.
- Capacity measurement: RESOLVED direction, 2026-11-12. Find simultaneous-user capacity before usable pages exceed 2 seconds, using realistic workflow cadences and reporting assumptions/ranges. Crafting includes minutes spent acting in game; account browsing is more active. The owner authorizes local actual-account data. Proposed fixed user counts, 3–5-second limits and 25% headroom are superseded; no unknown capacity prerequisite remains.
- Material protection: core product/UI/retention answers are confirmed. T1–T6 presets select intermediate materials only, not basic materials or gemstones; an additional preset selects clovers, coins, ectoplasm and obsidian shards. Exact membership must be verified; the extra preset's visible label is confirmed as "Clovers, coins, ecto & obsidian". Presets preselect pending checkboxes and do not commit automatically.
- Characters: equipment first, inventory/builds next; Story is deferred, discovered recipes excluded and map completion deferred. Review genuinely unspecified visual choices without reopening approved tabs and roster/detail behavior. Drafts do not authorize implementation.
- Full-catalog administration: RESOLVED by Product Owner, 2026-11-08: item and recipe actions re-download every detail in the captured authoritative catalog, including unchanged IDs, to pick up in-place edits. Full TP refresh covers the applicable tradable catalog independently of freshness. Ordinary scheduler checks and feature-scoped demand retain their existing behavior. Binding contract and planner addendum: #180.
- Current release sequence: hold #188 for owner visual review with no Claude attempt; #196 is the next sole coding story after the merged #193 foundation. Its native handoff in #191 prepares three actual-app journeys and bounded guest observations for the authorized local VM readiness checks. Independent planning can prepare later storage/request-rate qualification. Owner recordings and final capacity tests use the ultimately accepted product baseline; independent tooling and bounded functional dry runs do not wait for the display-only #188 review. #60 retains a verified published-empty-roster validation candidate in CachedCraftingProfitService; synchronization alone does not fix that entry point. It is a separate functional fix, not a recorder dependency or coverage-program restart. PR-triggered runs marked action_required need supervisor approval for the trusted exact head; dispatched CI does not replace branch-protection checks. Real Oracle access is required for final Oracle qualification, not for locally testable preparation.
