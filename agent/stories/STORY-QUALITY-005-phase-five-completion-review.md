## Story ID

STORY-QUALITY-005

## Title

Review Phase 5 project health before milestone completion

## Status

TODO

## Milestone

milestone-05

## Goal

Perform the bounded PROJECT HEALTH REVIEW required before Phase 5 closure and record concrete findings and their disposition for planner assessment.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5 exit criteria.
- docs/TARGET_ARCHITECTURE.md section 21, PROJECT HEALTH REVIEW policy.
- docs/TEST_STRATEGY.md sections 12 and 36.

## Context

The Phase 5 exit criteria require a bounded health review before milestone completion. The review should occur after the integrated frontend work and real-user performance assessment are substantially complete. It records findings; it does not itself close the milestone.

The former STORY-UI-003 dependency was dropped from this story on 2026-10-03. That story is
SUPERSEDED rather than DONE, so keeping it as a dependency would hold this review blocked
permanently. Its legacy JavaFX presentation defect stays recorded as `docs/KNOWN_PROBLEMS.md` KP-09
and is resolved by JavaFX removal, not by Phase 5 closure.

## Acceptance Criteria

1. Perform the bounded review under TARGET_ARCHITECTURE section 21 and record evidence, concrete findings, and limitations in this story Result.
2. Assess findings against Phase 5 scope and identify explicit disposition or destination for each blocking finding and any proposed transfer.
3. Keep review scope bounded; do not automatically create remediation work or claim milestone completion.

## Required Tests

- Assess relevant existing verification evidence and run only targeted checks needed to resolve concrete review questions; report evidence and limitations.

## Constraints

- Non-Goals: no broad bug hunting, automatic broad regression testing, arbitrary test expansion, speculative cleanup/refactoring, or later-milestone architecture implementation.
- Planned future replacement alone is not evidence of current debt.
- Preserve review first, remediation later; unresolved findings may remain for a subsequent planning pass.

## Dependencies

- STORY-PERF-002
- STORY-WEB-019
- STORY-SYNC-005
- STORY-WEB-018
- STORY-WEB-020
- STORY-WEB-021
- STORY-WEB-022
- STORY-WEB-027
- STORY-WEB-028

## Definition of Done

The bounded health review, evidence, findings, limitations, and explicit disposition of blocking findings and proposed transfers are recorded in Result.

## Result

**Review executed; Phase 5 / milestone-05 is NOT declared closed.** `TARGET_ARCHITECTURE.md` §21
reserves closure, blocking-finding disposition and any criterion transfer for the planner/user. No
production code, no test and no other document was changed by this story (see §7 for why no
documentation correction was made here). Ten findings are recorded for disposition (§5); **two of
them (F1, F2) bear directly on exit criterion (a)** and need an explicit planner decision before
closure. The other eight do not falsify a Phase 5 exit criterion.

**Reviewed tree:** HEAD `007270f` plus the working tree, which contains **no product file at all** —
`git status --porcelain` lists only `agent/CURRENT_STORY.md`, `agent/logs/*`,
`agent/product-owner-requests/Request-013-…`, `agent/stories/BACKLOG.md`, two story files and
`agent/qa-plans/QA-STORY-QUALITY-005.json`. The commit carrying this story therefore sweeps in no
maintainer source edit (`tasks/lessons.md`, "My story changed no source file"), and the narrow local
suites that would otherwise be owed are not owed here.

### 1. Phase 5 exit criteria (`ROADMAP.md` §9) — per-criterion assessment

Assessment is restricted to Phase 5. No later phase was inspected or planned.

**(a) "Verify the full browser-navigation-to-complete-Crafting-Profit-page performance requirement
against the real user database; see `STORY-PERF-002` and `TEST_STRATEGY.md` §34." — VERIFIED AS
MEASURED, WITH TWO OPEN QUESTIONS (F1, F2).** `STORY-PERF-002` recorded 15 openings over three
cache states through `frontend/scripts/profit-page-perf.mjs`, maximum **4 411 ms against §17's
7 000 ms** (63 % of budget), on the real user PostgreSQL database with the production bundle, scope
ALL, 3 176 rows calculated, 1 728 855-byte response, completion detected by quiescence plus a real
displayed-value read and a DOM-row-count-equals-`rowCount` assertion. That satisfies §34's
methodology in full. Two things are nevertheless unresolved: the measurement is revision `e2caa4a`
(2026-09-29) and the shared calculation path has changed since (**F1**), and the story records
explicit user acceptance as OUTSTANDING while the documents disagree on whether that gate still
exists (**F2**).

**(b) "Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (§21)." —
SATISFIED by this story's execution.** Closure itself remains the planner's/user's decision.

**(c) "Vue 3 and TypeScript are selected for the browser frontend (ADR-001)." — SATISFIED.**
`docs/architecture/decisions/ADR-001-frontend-framework-and-language.md` is `ACCEPTED — 2026-09-24`,
`UD-008` is RESOLVED, and the implementation matches: `frontend/package.json` `vue ^3.5.43`,
`typescript ^5.9.3`, `vite ^8.3.1`, with `npm run build` type-checking through `vue-tsc --noEmit`
first. `TARGET_ARCHITECTURE.md` §16 carries it under **Decided**.

**(d) "The browser renders Crafting Profit, Discovery, resolution details and domain states using
backend-provided authoritative values (§10.2–10.3)." — SATISFIED.** Three independent lines of
evidence:
- *Import/arithmetic inspection.* No file in `frontend/src/crafting/` combines two backend monetary
  fields. The only arithmetic on a `*Copper` value in the whole feature area is
  `ProfitSettingsForm.vue:27` (`settings.maxBuyCopper / 10000`), which converts an *input* control's
  copper into gold for display. `ResolutionTreeNode.vue` renders `node.requested`, `node.value`,
  `node.children`, `node.recipeKnowledge`, `node.characterName` as given, in the order given — §10.2's
  "no tree quantity or cost may be derived or scaled in the frontend" holds.
- *Component level.* `useProfitTableView.ts:8` and `CraftingProfitTable.vue:19` state and enforce that
  `totalSellValueCopper`/`totalProfitCopper` are sorted and shown as supplied, never defaulted to zero
  or recomputed; `TEST_STRATEGY.md` §12.1 rule 2 keeps the fixtures' supplied totals deliberately
  inconsistent with their own parts, so a recomputing frontend fails rather than agrees.
- *Real browser.* `STORY-WEB-032`'s `npm run smoke:profit` capture (2026-10-10, exit 0, 35 steps)
  shows the backend tree rendered whole and in order with one effective value per requirement, no raw
  resolver codes, and the root-sourcing sentence derived from the backend's own `treeBasis`.

**(e) "Normal feature workflows refresh their needed account and price data; System Status is
reserved for diagnostics and explicit maintenance refreshes." — SATISFIED (and see F4/F5 for two
consequences).** `web.AccountDataFreshnessInterceptor` enforces a per-source freshness policy
(`gw2.account-data.max-age-ms`, default 900 000) ahead of `/api/account/bank|materials|luck`,
`/api/crafting/profit|discovery` (+`/resolution`) and `/api/crafting/selector-options`, starting or
reusing the shared `ACCOUNT_SYNC` task; `application.TradingPostPriceRefreshService` reuses quotes
younger than ten minutes and fetches only missing/stale required IDs, with no full-catalog timer;
`GlobalDataRefreshScheduler` runs the global check on a configurable fixed delay. The browser's
System Status destination offers exactly two manual operations (full account refresh, global-data
check) and no feature flow requires it. `CURRENT_ARCHITECTURE.md` §5–§7 and
`CURRENT_STATE_SPEC.md` describe this accurately.

**(f) "Crafting Profit, Discovery, and other backend-owned domain results are not independently
recalculated in the browser." — SATISFIED.** Same evidence as (d), plus: the only computation the
browser performs over calculation results is local *filtering and matching* over rows the backend
already returned, and the one indirect-match path (`/api/crafting/ingredient-search`) returns recipe
IDs only, which the browser intersects with rows it already has — it never widens scope and never
transfers a tree (`CURRENT_ARCHITECTURE.md` §4). The frontend-owned Ecto exception is criterion (g).

**(g) "Ecto Salvage is frontend-owned and calculates locally from item metadata, Trading Post prices,
account Luck, and user-selected inputs; the former backend calculation route is absent." —
SATISFIED.** `frontend/src/ecto/EctoSalvageScreen.vue` computes from `/api/items/metadata`,
`/api/items/prices` and `/api/account/luck`; no `/api/ecto*` route exists in `src/main/java/web/`
(route-mapping inventory below), and both the component test
(`EctoSalvageScreen.spec.ts:104`) and the browser check (`ecto-browser-smoke.mjs:124`) assert the
removed route is *not* called. I additionally checked the point most likely to have drifted — the fee
model: the browser applies `Math.floor(expectedDust × dustPrice × 0.85)`
(`EctoSalvageScreen.vue:178,256,306`), which is what `DOMAIN_SPEC.md` §25/§46 prescribe ("copper-level
differences due only to transaction rounding, minimum fees or sale grouping need not be modeled
separately"; "do not round each Dust unit into a modeled sale transaction before scaling yield"). So
not reusing `tradingpost.TradingPostFeePolicy`'s integer rounding is spec-compliant, not a deviation.
`ADR-006` (backend ownership of this calculation) is correctly marked **SUPERSEDED** and points at
§4.2 and `DOMAIN_SPEC.md` §2.3.

**(h) "The former backend-owned Ecto calculation route is absent. Unreferenced legacy browser API
types/hooks are recorded in `KNOWN_PROBLEMS.md` KP-25; their cleanup is not required for feature
behavior." — SATISFIED.** Route absence as in (g). KP-25 re-verified item by item and found accurate
and current (**F6**): `api/Gw2PriceFetch.java` has no reference anywhere in `src/main` or `src/test`;
`model.Price` is imported only by that file (`grep -rn "import model.Price"` → one hit); `craft/
ResolvedNeedMapper` has no reference at all; `CraftingDiscoveryView#buildSearchBlob` (line 697) is
shadowed by the live `CraftingDiscoveryController.buildSearchBlob` (line 154); and
`frontend/src/api/ectoApi.ts` and `frontend/src/ecto/useEctoSalvage.ts` are referenced only by their
own definitions.

**Criterion transfers: none proposed.** Every Phase 5 exit criterion is applicable, assessed and
addressed by implementation in this phase; nothing needs a destination outside milestone-05. Two
criterion-(a) questions (F1, F2) are disposition items, not transfers.

### 2. Architecture and boundary drift (§15, §21)

**Clean on every rule the project states as a hard boundary**, verified by import inspection on the
reviewed tree:

- No file in `craft/`, `ecto/`, `luck/` or `tradingpost/` imports `javafx.*`, `org.springframework.*`,
  `java.sql.*`, `jakarta.*`, `repo.*`, `parser.*` or `api.*` — §6.1's domain-independence rule holds.
- No class in `src/main/java/web/` imports `parser.*`, `model.*` or `api.*`, so no GW2 JSON shape can
  reach an HTTP response (§6.4). Every response type is a `web.dto` record.
- No file outside `src/main/java/web/` imports `web.*` or `org.springframework.*`: the JavaFX entry
  point and the Spring entry point still cannot reach each other.
- The browser contacts only its own origin. The sole `guildwars2.com` strings in `frontend/src/` are
  `wiki.guildwars2.com` article links (`recipeLabel.ts:19`, six in `EctoSalvageScreen.vue`), and two
  account screens' tests assert that no `render.guildwars2.com` URL appears in the rendered HTML.
- Configuration is externalised, not hard-coded (`application.properties`: `server.port`,
  `gw2.global-refresh.*`, `gw2.account-data.max-age-ms`, all `${ENV:default}`), the frontend's API base
  is relative with a `GW2_BACKEND_ORIGIN`-overridable dev proxy, and no `.env` is tracked
  (`.env.example` only).

**Two deviations from §6's dependency chain, both in code the maintainer added during Phase 5**
(HTTP → Application → Domain → Ports → adapters): `web.AccountDataFreshnessInterceptor` opens its own
JDBC connection, runs an idempotent DDL check and queries a repository inside `preHandle`, and
`web.CraftingIngredientSearchApiController` holds `repo.CraftingGraphCache` and calls `load()` itself.
Neither implements a domain rule, so §6.3's "controllers must not implement business rules" holds.
Recorded as **F3**. The pre-existing `repo.*`-row-types-in-`web` coupling (Phase 3 F4, Phase 4 F4,
accepted as transitional) continues unchanged in `CraftingRowMapper`, `CraftingResolutionMapper`,
`BankContentsApiController`, `MaterialStorageApiController`, `CraftingSelectorOptionsApiController`
and the two scope mappers; it is not re-raised as new.

### 3. Duplicate or obsolete paths, and temporary migration structures

- **Confirmed-dead code**: the five KP-25 symbols plus the two dead frontend modules, all re-verified
  above (**F6**). Nothing new was found dead.
- **The Ectoplasm calculation now exists twice** — `ecto.EctoSalvageCalculator` +
  `application.EctoSalvageService` + `EctoView` for JavaFX, and the canonical TypeScript one in the
  browser (**F10**). Under this story's constraint ("planned future replacement alone is not evidence
  of current debt") this is recorded as a fact with a divergence risk, not as debt: §14 permits removing
  JavaFX code with no remaining consumer, and `EctoView` is still a consumer.
- **Legacy JavaFX overall**: 3 412 lines across the 10 classes of the default package, plus the
  `javafx-*:25.0.2` dependencies and `javafx.platform` property in `pom.xml`. Phase 5 explicitly does
  not require its removal; Phase 9 owns it. **10 of the 25 open `KNOWN_PROBLEMS.md` entries are
  JavaFX-only** — KP-08, KP-09, KP-11, KP-12, KP-19, KP-20, KP-21, KP-23, KP-26 and KP-29 (the last
  being `Gw2App.java:58`'s `/styles/dark-scroll.css` case mismatch) — so they are resolved by Phase 9
  and are not Phase 5 debt. This matches this story's Context for KP-09/`STORY-UI-003`
  (SUPERSEDED, not DONE).
- **Temporary structures that are *not* now removable**: the manual SQL under
  `src/main/resources/db/manual/` and the narrow idempotent schema checks wait on Phase 7's migration
  tool decision (`TARGET_ARCHITECTURE.md` §16 "To Be Decided"); the single-account tables wait on
  Phase 6. No Phase-5-local scaffolding was found left behind — `STORY-WEB-027` removed the tracked
  smoke control copy, and `frontend/scripts/` contains no `.tmp-control-*` or other throwaway file.

### 4. Tests, coverage and verification evidence

**Structure is appropriate to what Phase 5 changed.** The changed boundaries measure well in the only
available snapshot (`QUALITY_METRICS.md`, 2026-10-03, provisional and from a dirty tree): `web`
93.68 % line / 82.59 % branch, `web.task` 98.51/88.89, `web.dto` 100, `application` 93.82/71.25,
`craft` 92.44/84.02, `tradingpost` 100/100, `ecto` 100/100, frontend overall 92.88/85.22. The Java
packages far below the KPI (`api` 8.86, `api.tp` 3.49, `parser` 45.78, `sync` 37.53, `util` 30.00,
default 10.65) are *not* Phase-5-changed boundaries; they are the subject of the still-OPEN
`Request-013` (**F8**). Verification layers the CI gate cannot run are present and were exercised
during the phase: 13 browser-driving check scripts (12 `smoke:*` entries plus `check:icons:live`) and
the §34 performance harness `perf:profit-page`, with `STORY-WEB-032`'s and
`STORY-PERF-002`'s captures as the most recent evidence.

**Targeted checks run for this review** (two concrete questions; `TEST_STRATEGY.md` §33 bounds a
review's verification to the questions it has):

| Question | Command (from `frontend/`) | Real output |
|---|---|---|
| Is the one frontend-*owned* calculation adequately covered after the maintainer's Ecto rewrite, and what drags its area below the KPI? | `npx vitest run src/ecto --coverage --coverage.reporter=text --coverage.include='src/ecto/**'` | `Test Files 3 passed (3)`, `Tests 19 passed (19)`; area **85.38 % lines / 86.63 % branches**, of which `EctoSalvageScreen.vue` **97.23 / 92.05** and `useEctoSalvage.ts` **0 / 0** |
| What covers the browser's HTTP clients themselves? | `npx vitest run src/api --coverage --coverage.reporter=text --coverage.include='src/api/**'` | `Test Files 2 passed (2)`, `Tests 8 passed (8)`; `http.ts` 80.95 / 65.62, `syncApi.ts` 90.9 / 100, and `craftingApi.ts`, `accountApi.ts`, `systemApi.ts`, `ectoApi.ts` all **0 % lines** |

The first answers its question cleanly: the live Ecto component is above the KPI on both measures, and
the area only looks short because the dead `useEctoSalvage.ts` is measured with it (**F6**). The
second is scoped to the area's *own* tests, so it understates what the full suite reports (84.06/70.59
on 2026-10-03) — but it does establish that three of the four feature clients have no test of their
own, which with §12.1's stand-in rule means their request construction is proven only by the local,
non-CI browser checks (**F7**). Both reporters were overridden to `text`, so neither run wrote to
`frontend/coverage` and `git status` is unchanged by them.

No other suite was run: no product file changed in this tree, `TEST_STRATEGY.md` §33 makes
verification an evidence source for a review rather than a reason to run broad suites, and §36 leaves
full regression to GitHub Actions on the pushed commit.

### 5. Findings for planner/user disposition

**F1 — The Phase 5 page-budget evidence predates changes to the calculation path it measured.
(Bears on criterion (a); planner decision required.)**
*Affected:* `craft.*` resolution path, `/api/crafting/profit`, the §34 evidence record.
*Evidence:* `STORY-PERF-002` measured revision `e2caa4a` (2026-09-29). Since then, on the reviewed
tree: `f0e360e` (2026-10-03, "Fix crafting plans and add coverage quality infrastructure") changed
`craft/CraftingPlanner.java`, `craft/CraftingResolver.java`, `craft/DailyCrafts.java`,
`craft/PlanState.java`, `craft/RecipeSimulator.java`, `craft/RecipeSimulationResult.java` and
`craft/ResolvedNeed.java`; `c05080f` added `AccountDataFreshnessInterceptor`, which performs a
database round trip and an idempotent DDL check *before every* `/api/crafting/profit` call;
`5a6593b` ("Refactor crafting settings and full resolution quantities"), `bfef964`, `4ce6006` and
`8a80755` changed settings/resolution quantities, refresh automation and the search/discovery
workflow. `TEST_STRATEGY.md` §34 additionally requires, for "a performance change to shared
calculation code", a real-data equivalence check before and after; none is recorded for `f0e360e`,
which is a maintainer commit rather than a story.
*Impact:* the recorded 4 411 ms no longer describes the current code. No regression is implied — the
measurement had ~2.6 s of headroom and nothing observed suggests one — but Phase 5's own performance
criterion currently rests on a 12-day-old, 40-commit-old measurement. This is the Phase 5 analogue of
Phase 4 finding F3, and materially larger: there the delta was a dependency version, here it is the
resolver and planner themselves.
*Disposition options:* (i) accept the bounded gap, as the planner did for Phase 4 F3, recording that
the figure is historical; or (ii) commission one re-run of `npm run perf:profit-page` over its three
phases plus the §34 real-data equivalence comparison for the calculation change. This review does not
choose, and re-measuring was outside its bounds (it needs the real database, a fresh build and a
dedicated harness run).

**F2 — Explicit user acceptance of the page budget is recorded as OUTSTANDING, and the documents
disagree on whether that gate still exists. (Bears on criterion (a); needs an explicit decision.)**
*Affected:* `ROADMAP.md` §9 criterion (a), `TEST_STRATEGY.md` §34, `TARGET_ARCHITECTURE.md` §17.
*Evidence:* `STORY-PERF-002`'s Result states "**User acceptance is OUTSTANDING** … §33's explicit
user/Product Owner confirmation has not been requested or received". `TEST_STRATEGY.md` §34 still
says "The acceptance threshold, scope and **user-confirmation gate** belong to
`TARGET_ARCHITECTURE.md` §17" and "Record measurements and the outstanding/received user acceptance in
the normal executing story Result". But §17 in its current five-line form contains no confirmation
gate at all — only the threshold ("at most 7 seconds") and the budget's span; the words "accept",
"confirm" and "Product Owner" appear nowhere else in `TARGET_ARCHITECTURE.md`. Phase 3's analogous
criterion (`ROADMAP.md:193`) demanded "explicit dated user acceptance recorded in `STORY-PERF-001`";
Phase 5's (`ROADMAP.md:255`) does not mention acceptance.
*Impact:* read one way, criterion (a) has an unmet element and closure needs a Product Owner
confirmation of the measured figure; read the other way, the gate was deliberately dropped when §17
was condensed and the criterion is met as written. Both readings are defensible from the current
documents, which is exactly why it should not be settled implicitly by a closure decision.
*Destination:* planner, to either obtain the confirmation or record that §17 no longer requires one —
and then the §17/§34 wording needs reconciling at its owner (part of F9).

**F3 — The HTTP layer performs persistence access, schema DDL and refresh orchestration, skipping the
application layer. (Non-blocking; no Phase 5 criterion affected.)**
*Affected:* `web/AccountDataFreshnessInterceptor.java`, `web/CraftingIngredientSearchApiController.java`.
*Evidence:* the interceptor imports `repo.Db` and `repo.AccountSyncStateRepository`, and in
`preHandle` opens a connection, calls `AccountSyncStateRepository.ensure(con)` (idempotent DDL),
queries each required source's marker, applies `application.AccountDataFreshnessPolicy.isStale`, then
submits the `ACCOUNT_SYNC` task — with the route→source mapping as a private static method in the
transport class (lines 28–56). The search controller takes `repo.CraftingGraphCache` in its
constructor and calls `graphCache.load()` in the handler (lines 16–27). `TARGET_ARCHITECTURE.md` §6's
diagram is HTTP → Application → Domain → Ports → adapters, and §6.3 scopes the API layer to routing,
validation, DTO mapping, auth/account context and error mapping.
*Impact:* bounded today — no domain rule lives in `web`, nothing GW2-shaped leaks, and the policy
*predicate* is already in `application`. What is misplaced is infrastructure orchestration plus one
route-to-source table. The cost is that the freshness rule has no application-level use case to test
or to extend, which matters because Phase 6 must scope exactly this decision per account.
*Destination:* Phase 6 (multi-user/account isolation) owns this boundary; no refactoring is
commissioned by this review.

**F4 — An unauthenticated GET can now start a GW2 API synchronization and database writes, and KP-24
does not describe this. (Non-blocking for Phase 5; relevant before any non-local deployment.)**
*Affected:* `/api/account/bank`, `/api/account/materials`, `/api/account/luck`,
`/api/crafting/profit`, `/api/crafting/discovery` (+`/resolution`), `/api/crafting/selector-options`.
*Evidence:* `AccountDataFreshnessInterceptor.preHandle` calls
`tasks.submitOrExisting("ACCOUNT_SYNC", () -> refresh.refreshSources(stale))` whenever a required
source's marker is older than the configured max age (default 15 minutes) — i.e. a plain read can
cause upstream GW2 traffic and account-table writes. `KNOWN_PROBLEMS.md` KP-24 still models the
write-triggering surface as the four explicit trigger operations ("four is the current count") and
describes the read surface as returning "item ids, quantities and display metadata only"; its Status
line stops at `STORY-API-008`.
*Impact:* no credential is exposed and the one-unfinished-task-per-operation rule still bounds
concurrency, so the *severity* class is unchanged — but the statement that writes require an explicit
trigger route is no longer true, and the phase added further unauthenticated read routes
(`/api/items/prices`, `/api/items/metadata`, `/api/items/{id}/icon/{file}`, `/api/account/luck`,
`/api/crafting/ingredient-search`, `/api/system/status`). §20-class authentication remains undecided
and this review invents none.
*Destination:* `KNOWN_PROBLEMS.md` KP-24 (its owner) for the factual update; the access-control
decision belongs to Phase 6.

**F5 — A stale-data page open can wait for a full account synchronization, and nothing relates that to
the 7-second budget. (Unmeasured; needs disposition.)**
*Affected:* `frontend/src/api/http.ts:48–95`, the §17 budget, criterion (e)'s workflow.
*Evidence:* on an `ACCOUNT_DATA_STALE` response the client calls `waitForAccountRefresh`, which polls
the task status every 500 ms **for up to five minutes**, then retries the original request once;
failure surfaces as `ACCOUNT_SYNC_TIMEOUT`. `CURRENT_ARCHITECTURE.md` §7 documents this as intended.
§17 budgets "navigation/request initiation to complete usable page content" at 7 s and says nothing
about an embedded synchronization wait, and `STORY-PERF-002` measured on already-fresh data, before
this code existed.
*Impact:* by design, the first Crafting Profit open after 15 minutes of inactivity can take as long as
an account sync takes, which is plainly outside 7 s. Whether that is a budget violation or a case the
budget was never about is undecided. **Not measured here:** observing it requires a live GW2
synchronization against the real account (API budget plus database writes), which is outside a bounded
review's scope.
*Destination:* planner → the §17 owner, to state whether a freshness-driven sync wait is inside or
outside the performance requirement.

**F6 — The confirmed-dead code KP-25 names is still present, and two of those modules are the only
reason the frontend `ecto` area measures below the coverage KPI. (Non-blocking; criterion (h)
explicitly does not require cleanup.)**
*Affected:* `api/Gw2PriceFetch.java`, `model/Price.java`, `craft/ResolvedNeedMapper.java`,
`CraftingDiscoveryView#buildSearchBlob`, `frontend/src/api/ectoApi.ts`,
`frontend/src/ecto/useEctoSalvage.ts`.
*Evidence:* the per-symbol greps in §1(h), plus the targeted coverage run in §4
(`EctoSalvageScreen.vue` 97.23 % / 92.05 %; `useEctoSalvage.ts` 0 % / 0 %; area 85.38 % / 86.63 %).
*Impact:* KP-25 is accurate and current, so nothing in it needs correcting. The useful new fact is
that deleting the two dead frontend modules would lift the measured `ecto` area above the 90 % KPI
without writing a single test — the area's apparent shortfall is dead code, not a gap in live
behavior. `ectoApi.ts` also keeps a client for a route that no longer exists, which is the
"mistaken for live behavior" risk KP-25 infers, made concrete.
*Destination:* planner; KP-25 already owns the record and the phase does not require the cleanup.

**F7 — The browser's HTTP clients are proven only by local, non-CI checks. (Non-blocking;
verification weakness.)**
*Affected:* `frontend/src/api/craftingApi.ts`, `accountApi.ts`, `systemApi.ts`.
*Evidence:* `src/api/__tests__/` contains `http.spec.ts` and `syncApi.spec.ts` only; screens are
tested against a hand-written stand-in for the client interface (`TEST_STRATEGY.md` §12.1, and
`src/crafting/__tests__/fixtures.ts` / `src/account/__tests__/accountFixtures.ts`), so the real
clients are not exercised by the component layer. The targeted run in §4 reports 0 % lines for all
three. What does assert the real paths, methods and bodies is the browser smoke layer — which §12.2
and §36.4 keep local and out of CI.
*Impact:* a wrong path, verb or body in those three clients fails no CI job; it is caught when a human
runs `npm run smoke:*`. This is the accepted methodology rather than a defect, recorded so the limit
is visible — the same shape as Phase 4's F5.
*Destination:* planner; candidate for the quality backlog, not for Phase 5 closure.

**F8 — Product Owner `Request-013` (backend package coverage) is OPEN, and the only coverage snapshot
is provisional. (Non-blocking for Phase 5; destination already recorded.)**
*Evidence:* `agent/product-owner-requests/Request-013-raise-backend-test-coverage.md` carries planner
resolutions dated 2026-10-10 and 2026-10-11 retaining OPEN, on the grounds that the supplied Phase 5
scope does not authorize the series and no fresh CI coverage artifact was supplied.
`QUALITY_METRICS.md`'s snapshot is explicitly "measured results for the current checkout, **not a
clean-commit baseline**" from 2026-10-03, and work has landed since (the Ecto rewrite,
`STORY-WEB-029`…`032`, the n8n pipeline change that added `local_bridge` to the Python area).
*Impact:* Phase 5's own boundaries are adequately covered on the evidence available (§4), so the
request is not a Phase 5 blocker — but closing milestone-05 must not implicitly absorb it, and the
current coverage of the weak packages is genuinely unknown rather than known-at-58 %.
*Destination:* unchanged — the request stays OPEN at its own owner, awaiting an authorized planning
scope and a fresh CI baseline.

**F9 — Several documentation cross-references into `TARGET_ARCHITECTURE.md` no longer resolve.
(Non-blocking; documentation drift.)**
*Evidence:* the document has 21 sections (`# 1` … `# 21`). `docs/KNOWN_PROBLEMS.md:232` cites
"TARGET_ARCHITECTURE §36"; `docs/TEST_STRATEGY.md:1231` cites a section named "Existing JavaFX UI
Verification Capability", which no longer exists under that name; `docs/TEST_STRATEGY.md` §34's "§17
… user-confirmation gate" is the dangling pointer behind F2;
`docs/architecture/decisions/ADR-002` cites "section 13" plus "sections 12–14, 23, 33" and `ADR-003`
cites "sections 13.3-13.5", where the resolution contract now lives in §10.2 and §13 is "Testing
Architecture".
*Impact:* a reader following any of these lands on an unrelated or missing section; the §34 one can
change a closure decision (F2). Nothing is factually wrong about behavior.
*Not corrected here, deliberately:* this story's acceptance criteria ask for findings and dispositions
only (unlike `STORY-QUALITY-004`, whose AC5 explicitly licensed small documentation corrections);
`ROADMAP.md` and `docs/architecture/decisions/` are read-only for this role; and the `TEST_STRATEGY`
wording cannot be fixed until F2 is answered.
*Destination:* planner → the `KNOWN_PROBLEMS.md`/`TEST_STRATEGY.md` owners, and the architect for the
two ADRs.

**F10 — The Ectoplasm calculation exists twice, with the browser canonical. (Fact; explicitly not
debt under this story's constraint.)**
*Affected:* `ecto/EctoSalvageCalculator.java`, `application/EctoSalvageService.java`, `EctoView.java`
versus `frontend/src/ecto/EctoSalvageScreen.vue`.
*Evidence:* both exist and are reachable from their own entry points; §4.2 and `DOMAIN_SPEC.md` §2.3
make the browser's the canonical one; `ADR-006` is SUPERSEDED; `STORY-DOM-024` aligned the Java side's
fee policy and labels. I checked the likeliest divergence point and found the browser compliant with
`DOMAIN_SPEC.md` §25/§46 (see criterion (g)).
*Impact:* a future change to the shared fee or yield policy has to be made in two languages or the two
will disagree. Phase 9's JavaFX removal retires the duplicate; §14 does not permit removing it earlier
while `EctoView` consumes it. Recorded so the risk is visible, not as a remediation request.

### 6. What this review deliberately did not do

No remediation was created or performed: no production code, no test and no other document was
changed, no story was created, and no backlog or status field was touched (`BACKLOG.md`,
`CURRENT_STORY.md` and this story's Status remain the harness's). No broad bug hunt, no speculative
cleanup, no later-milestone design, and no test expansion beyond the two scoped coverage measurements
that answered stated review questions. Phase 5 closure is **not** claimed.

### 7. Verification limitations of this review

- Static reading plus import inspection for every layering, leakage and recalculation claim; two
  scoped Vitest coverage runs (quoted in full in §4) for the two questions that needed numbers.
- **No performance measurement was taken** (F1, F5): the §34 harness needs the real database, a fresh
  production build and a dedicated run, and F5's path additionally needs a live GW2 synchronization.
- No live GW2 API call, no database write, no browser run and no JavaFX/TestFX check was performed
  here. The browser evidence cited is `STORY-WEB-032`'s (2026-10-10) and `STORY-PERF-002`'s
  (2026-09-29) own recorded captures, not re-run.
- Coverage figures are either the 2026-10-03 **provisional, dirty-tree** snapshot (so current values
  for unmeasured areas are unknown, not 58 %) or the two scoped runs above, whose scope limitation is
  stated with them. No remote CI run was inspected.
- Findings rest on the reviewed tree only; `KNOWN_PROBLEMS.md` entries outside Phase 5's subject
  matter (KP-01…KP-06, KP-13…KP-22, KP-27, KP-30, KP-32) were read for relevance but not re-verified
  against code.

### 8. Phase 5 health assessment

Phase 5 is **healthy**. Seven of the eight exit criteria are assessed satisfied with concrete
evidence; the eighth, criterion (a), is satisfied *as measured* but carries two open questions — the
measurement predates changes to the calculation path it timed (**F1**) and the status of the explicit
user-acceptance gate is contradictory across the owning documents (**F2**). Every hard boundary the
project states holds: the domain imports no infrastructure, no GW2 JSON shape reaches an HTTP
response, the two entry points cannot reach each other, the browser calls only its own origin, the
browser recalculates no backend-owned result, and the one frontend-owned calculation follows the
shared fee policy the domain specifies. The boundary deviations that do exist (**F3**) are
infrastructure orchestration in the transport layer, not domain logic, and sit exactly where Phase 6
must work next. The security posture is unchanged in class but wider in fact than its record states
(**F4**, **F5**), and the known-problem register is accurate where this review touched it (**F6**
re-verified KP-25 item by item).

The items a closure decision should dispose of first are **F1** and **F2**; **F4** and **F5** need a
recorded position before any non-local deployment or performance claim; **F3**, **F6**, **F7**,
**F8**, **F9** and **F10** are accurate, bounded and open. Milestone closure is not declared here and
remains the planner's/user's decision after disposing of these findings.

## Blockers

None.

## Follow-up Findings

None. This story's deliverable *is* a findings list, so the ten review findings are recorded once, in
Result §5, with their evidence, impact and disposition destination; copying them here would create a
second, divergent copy of the same records. Nothing was observed outside the review's own scope.
