## Story ID

STORY-QUALITY-003

## Title

Review Phase 3 project health before milestone completion

## Status

DONE

## Milestone

milestone-03

## Goal

Perform the bounded PROJECT HEALTH REVIEW required before Phase 3 closure, assessing application-service extraction evidence and reporting concrete findings for subsequent planner disposition.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, Phase 3 objective and exit criteria supplied to this planning pass.
- docs/TARGET_ARCHITECTURE.md section 8 (application layer), section 33 (performance acceptance gate), and section 34 (PROJECT HEALTH REVIEW policy).
- agent/stories/STORY-APP-009-extract-bank-and-material-storage-use-cases.md, acceptance criteria and completion evidence when available.
- agent/stories/STORY-PERF-001-crafting-profit-page-load-budget.md, Result as owner of real-database measurements and dated user acceptance.

## Context

The supplied backlog records the application extractions, including STORY-APP-009, as complete. The supplied Phase 3 roadmap records the performance gate as satisfied. This existing review is the remaining queued Phase 3 assessment and does not itself close the milestone.

## Acceptance Criteria

1. Begin the review only after STORY-APP-009 is complete. Assess each supplied Phase 3 exit criterion against concrete implementation and recorded verification evidence; distinguish confirmed completion from uncertainty.
2. Assess the named crafting-profit, discovery, account-refresh, global-refresh, Trading Post refresh and graph-rebuild application boundaries, presentation delegation, the single Ectoplasm calculation/service, and resolution of the old AccountRefreshService dead-call situation. Include APP-009's bank/material-storage boundaries and the completed setup and auto-refresh extractions within this milestone's scope.
3. Confirm that STORY-PERF-001's Result contains real-user-database complete-page measurements meeting section 33 and explicit subsequent dated user acceptance tied to the tested revision/results. Assess the recorded evidence without substituting automated success for acceptance or requiring a fresh benchmark without a concrete reason.
4. Assess roadmap, architecture, documentation, known-problem/debt and verification health within Phase 3 under section 34. Compare relevant CURRENT_ARCHITECTURE and KNOWN_PROBLEMS statements with evidence; correct only small, clearly evidenced documentation errors at their authoritative owner.
5. Record evidence, corrections, concrete findings, affected components, impact, verification limitations and whether each finding blocks milestone completion in Result. Any proposed criterion transfer must identify its destination and rationale and remain subject to explicit planner/user disposition.
6. Report the overall milestone-health assessment without declaring milestone closure, changing the current phase, creating remediation stories or treating unresolved findings as implicitly accepted.

## Required Tests

- Assess existing story results, tests and saved verification evidence for confidence in Phase 3 boundaries and preserved behavior.
- Run targeted checks only to answer concrete review questions; record the question, evidence and limitations. Do not automatically run every suite or add tests because a review occurred.

## Constraints

- Assessment first; substantial implementation, remediation, refactoring and test work require separate normal planning after the review.
- Explicit Non-Goals: no broad bug hunting, automatic broad regression testing, arbitrary test expansion, test-strategy redesign, speculative cleanup/refactoring, or later-milestone architecture implementation.
- Remain within Phase 3. Planned future replacement alone is not evidence of current debt. Do not inspect or plan later roadmap phases.
- Preserve authoritative document ownership and use existing Result/workflow reporting mechanisms.

## Dependencies

STORY-APP-009 must be complete before execution. Earlier completed Phase 3 stories provide review evidence.

## Definition of Done

The bounded assessment and any small authoritative documentation corrections are recorded, concrete findings and verification limits have explicit impact assessments, and the subsequent planner can decide whether Phase 3 exit criteria are satisfied or separately scoped work is needed. Review completion alone does not close the milestone.

## Result

DONE — bounded PROJECT HEALTH REVIEW executed per `TARGET_ARCHITECTURE.md` §34. **This review does
not close Phase 3, does not change the current phase, creates no remediation story, and treats no
finding below as accepted.** Overall assessment: Phase 3 is **healthy and its stated exit criteria
are satisfied by evidence**, with one finding (F1) that the planner should dispose of before closure
and three non-blocking findings.

### AC 1 — Dependency and exit-criterion assessment

`STORY-APP-009` is DONE (its story file and its `BACKLOG` entry both record completion), so the
review's precondition is met. Each supplied Phase 3 exit criterion (`ROADMAP.md` §7), assessed
against source read in this pass, not against story claims alone:

| Exit criterion | Assessment | Evidence |
|---|---|---|
| §33 performance gate | **Confirmed satisfied** (see AC 3) | `STORY-PERF-001` Result + `UI-001-STORY-PERF-001` |
| Bounded PROJECT HEALTH REVIEW before closure | **Satisfied by this story** | this Result |
| Application services for profit, discovery, account refresh, global refresh, TP price refresh, graph rebuild | **Confirmed satisfied** | `src/main/java/application/` holds exactly ten services; all six named use cases present, plus `InitialSetupService`, `EctoSalvageService`, `BankContentsService`, `MaterialStorageService`. Every one has a production call site (checked by repository-wide search): `CraftingGraphRebuildService` only via `GlobalDataRefreshService`, as documented — no dead service. Coverage of `TARGET_ARCHITECTURE.md` §8's example list is complete (`CalculateCraftingProfit`, `GetCraftingDiscoveryCandidates`, `RefreshAccountData`, `RefreshTradingPostPrices`, `RebuildCraftingGraph`, `GetBankContents`, `GetMaterialStorage`, `CalculateEctoLuckCost`). |
| `Gw2App` handlers / `EctoView` / `Main.java` no longer call `sync.*`/`repo.*`/`craft.*` | **Confirmed satisfied** | `Gw2App.java` imports only `application.AccountRefreshService`/`GlobalDataRefreshService`/`InitialSetupService`; `EctoView.java` imports only `application.EctoSalvageService`; no `sync.`/`repo.`/`craft.` import or fully-qualified reference in either; no `Db.`/`DriverManager`/`java.sql` in either. `Main.java` does not exist. |
| Two Ectoplasm implementations converge into one service with one calculation | **Confirmed satisfied** | `ecto.EctoSalvageCalculator` is the only Ecto calculation in `src/main/java` (repository-wide search for the calculator and for Dust/Luck arithmetic); it is reached only through `application.EctoSalvageService`, which evaluates all four buy/sell combinations; `EctoView` only formats (`fillProfitGrid`/`fillLuckGrid`). |
| `AccountRefreshService` dead-call situation resolved | **Confirmed satisfied** | no top-level `AccountRefreshService.java` exists; the single implementation is `application/AccountRefreshService.java`, wired to `Gw2App`'s Sync Account button and to both crafting views' auto-refresh timers. |

No skipped or obsolete criterion was found. One roadmap-health observation, **reported not changed**:
the four implementation criteria above are implemented but carry no `**(Done)**` marker, unlike
Phase 2's §6 list. Marking them is part of the planner's closure assessment, so this review
deliberately left `ROADMAP.md` §7's criterion markers untouched.

### AC 2 — Named boundaries and presentation delegation

Verified by reading imports and call sites in every default-package class:

- **Confirmed at the application boundary:** crafting profit (`CraftingProfitController` →
  `CraftingProfitService`), discovery (`CraftingDiscoveryController` → `CraftingDiscoveryService`),
  account refresh, global refresh, graph rebuild, TP price refresh (both distinct variants), initial
  setup, Ectoplasm, and APP-009's bank/material-storage reads. No default-package class imports
  `sync.*` any more, and no view contains SQL, a `java.sql` import or a `DriverManager` call —
  `BankView`/`MaterialsView` included.
- **Gap, still open (F2):** both crafting **views** still construct `repo.CharacterRepository` and
  query it directly for selector contents — `CraftingProfitView:163` (`loadAllCharacterCrafting`) and
  `CraftingDiscoveryView:141/181` (`loadAllCharacterCrafting`, `loadAllCharacterNames`). Already
  recorded as CH-E2 in `KNOWN_PROBLEMS.md` §4.4; independently reconfirmed here.
- **Gap, still open (F3):** `CraftingProfitController.UiRow.totalProfitCopper` (from
  `CraftResult.totalProfitCopper`) is not passed into `CraftingProfitView.CraftRow`; the view's own
  `totalProfitCopper` property (line 110) is never set, and the "Total profit" column (line 596) plus
  both sort comparators (lines 968/976) recompute `craftableCount * profitCopper` themselves. The
  domain formula is currently the same (`CostEvaluator:26`, `CraftingPlanner:131`), so **no current
  numeric divergence is evidenced** — the defect is duplicated ownership, matching CH-E2's own
  inferred-risk framing.
- **Observation (F4):** the application layer hands persistence-owned row types to presentation:
  `BankContentsService.getBankContents()` returns `List<BankRepository.BankSlotRow>` and
  `MaterialsView` imports `repo.MaterialStorageRepository.MaterialStorageRow`, so both views compile
  against `repo.*` types. Per §34 this is **not** called current debt merely because Phase 4 will need
  transport DTOs; it is recorded so the Phase 4 API boundary inherits it knowingly.

### AC 3 — §33 performance acceptance gate

**Confirmed present and sufficient, without requiring a fresh benchmark.** `STORY-PERF-001`'s
Result records real-user-database, real-UI, navigation-to-complete-page measurements — first opening
after startup 2 772 ms, repeats 1 658 / 1 406 ms, **maximum 2 772 ms against the 7 000 ms limit**,
from a 43 245 ms baseline — with environment, data scale, settings, cache state, per-run figures and
the maximum all recorded, and completion detected by quiescence including row-object identity, the
settle window excluded, and a real displayed-value read. That matches `TEST_STRATEGY.md` §34's
procedure point for point, and the acceptance runs were taken after instrumentation removal.

Explicit dated user acceptance exists and is **subsequent to and separate from** the authorizing
request: `agent/user-interventions/UI-001-STORY-PERF-001.md` records `Status: RESOLVED`,
`User Resolution: ACCEPTED — 2026-09-23`, with resolution notes reproduced verbatim in the story
Result and tied to those results and to the tested revision (working tree on `a693d9d`). No
automated signal was substituted for acceptance. Independent corroboration found incidentally:
`target/surefire-reports/CraftingProfitEquivalenceRealDbIT.txt` (03:46, from the story's own
before/after run) shows the single difference class being exactly the disclosed, accepted
`missingToBuy` iteration order (`{19761=62,24353=184}` vs `{24353=184,19761=62}` — same keys, same
quantities), consistent with "zero differences in any computed value".

No concrete reason to re-measure was found: the page-open path still carries PERF-001's fixes
(`sameScope` at `CraftingProfitView:477`), `STORY-APP-008`'s change sits on the 90-second auto-refresh
task (line 434, inside the scheduler task), i.e. outside the measured navigation window, and
`STORY-APP-009` touched only `BankView`/`MaterialsView`. So no fresh benchmark was run.

### AC 4 — Health assessment and documentation corrections

**Roadmap health:** exit criteria as assessed above; Phase 3 scope shows no drift (every DONE story
in this milestone maps to a Phase 3 criterion or high-level story). The objective's phrase
"replacing the current pattern of UI classes … calling `sync.*`/`repo.*`/`craft.*` directly" is not
fully achieved because of F2/F3 — see the transfer proposal in AC 5.

**Architecture health:** `TARGET_ARCHITECTURE.md` §8 still expresses the intended direction and needs
no change; §10's repository-interface target remains a future step (concrete classes with constructor
seams today) and is explicitly not treated as current debt. `CURRENT_ARCHITECTURE.md` §5.4 was
compared line by line against `Gw2App`/the services and is accurate.

**Documentation corrections made** (small, clearly evidenced, at the authoritative owner):

1. `CURRENT_ARCHITECTURE.md` §4 dependency block — the `Gw2App / InitialSetupService /
   AccountRefreshService --imports--> sync.*, repo.*` line named two classes that no longer exist and
   imports `Gw2App` no longer has.
2. `CURRENT_ARCHITECTURE.md` §4 bullet "Views call controllers; controllers call `repo.*` … Views do
   not call `repo.*` or `craft.*` directly …" — both halves were wrong and the first contradicted the
   two bullets directly above it; rewritten to state that the controllers delegate and that the views
   still query `repo.CharacterRepository` and use `craft.*` result/settings types, with a pointer to
   §4.4 (CH-E2).
3. `CURRENT_ARCHITECTURE.md` §4 bullet asserting `Gw2App`/`InitialSetupService`/`AccountRefreshService`
   call `sync.*` and `repo.CraftingGraphCache` directly — stale since APP-004/005/007.
4. `CURRENT_ARCHITECTURE.md` §5.1 — "the §33 gate additionally requires the Product Owner's
   confirmation" read as still outstanding; confirmation was given 2026-09-23.
5. `CURRENT_ARCHITECTURE.md` §6 — "Eleven named Application Layer boundaries" corrected to **ten**
   (its own list names ten, and `src/main/java/application/` contains exactly ten); the dependent
   "`STORY-APP-008` added no tenth boundary" clause reworded to "no boundary of its own".
6. `KNOWN_PROBLEMS.md` §9 — "Still-open work: §7.8's orphaned dead code" was the document's only
   open-work statement while its own §10 records CH-01–CH-22 undispositioned; added that pointer.
7. `ROADMAP.md` §3 Phase Overview — still showed Phase 0 "in progress" and Phases 1–3 "not started",
   contradicting §4's and §6's own status lines and the archived milestone-00/01/02 entries. Phases 0–2
   corrected to complete; Phase 3 marked "in progress — current milestone", which states the existing
   position and does not change or close a phase. §7's per-criterion markers were left untouched.

**Known-problem/debt health:** §2.2 (resolved by APP-009), §3.6, §4.3, §4.4 and §8's
`AccountRefreshService` item were each re-checked against source and their resolved status holds.
§7.9's resolved status is supported (AC 3). No resolved item is still marked open, and the two
presentation-boundary gaps F2/F3 are already recorded (CH-E2) rather than missing. §7.8's orphaned
code (`api.Gw2PriceFetch`, `model.Price`) remains open and out of Phase 3's scope.

**Verification health:** meaningful for this milestone. Every one of the ten application services has
its own fake-collaborator test class; persistence adapters have disposable-schema integration tests;
presentation delegation has real-window TestFX checks.

### AC 4/AC 5 — Targeted checks run for this review

Two bounded Maven runs, both via `./mvnw -o`; no test was added, changed or weakened.

1. *Question: does the default suite pass on the current combined working tree?* — `./mvnw -o test`:
   **160 tests, 0 failures, 0 errors, BUILD SUCCESS**, matching `STORY-APP-009`'s recorded figure.
2. *Question: do this milestone's application-boundary view checks pass together on the final tree?*
   (each story ran its own ITs against a different intermediate tree; no single run covered the final
   state) — `./mvnw -o test -Dtest=Gw2AppSyncAccountIT,Gw2AppSyncGlobalDataIT,Gw2AppFirstSetupIT,
   EctoSalvageViewIT,BankViewIT,MaterialsViewIT,CraftingProfitViewTpRefreshIT,
   CraftingDiscoveryViewTpRefreshIT,CraftingProfitViewAutoRefreshIT,CraftingDiscoveryViewAutoRefreshIT`:
   **16 tests across all ten classes, 0 failures, 0 errors, BUILD SUCCESS** (the stack trace in the
   output is `MaterialsView`'s deliberate `printStackTrace` on its preserved failure path).

### AC 5 — Findings, impact and blocking assessment

**F1 — The measured-and-accepted Phase 3 implementation is not in version control.**
*Affected:* `craft/PlanState.java`, `craft/PlannerContext.java`,
`application/CraftingProfitService.java` (uncommitted modifications), plus all of APP-009's new
files (`application/BankContentsService.java`, `MaterialStorageService.java`,
`repo/BankRepository.java`, `repo/MaterialStorageRepository.java` and their tests) as untracked
files. *Evidence:* `git show HEAD:src/main/java/craft/PlanState.java` contains no `mark()`/
`rollbackTo` (0 matches) while the working tree does (4); `git diff --stat HEAD` shows +318/−35 across
those three files. HEAD is `8a1fbbe`. *Impact:* the §33 evidence and the PO's acceptance are tied to a
tree state that cannot be reconstructed from git; a clean checkout of HEAD lacks the undo-journal and
memoization work that accounted for the planner's 21.7 s → 0.5 s improvement, so HEAD alone would
**not** meet the 7-second limit (inference from the stage attribution, not a measurement). *Blocking?*
It does not falsify any exit criterion as written — measurements and dated acceptance both exist and
are correctly recorded. It is nonetheless the one finding this review recommends resolving before
closure, because milestone closure would otherwise certify a state that exists only in a working tree.
Disposition is the planner's/user's.

**F2 — Both crafting views bypass the application boundary for selector data.** *Affected:*
`CraftingProfitView.reloadDisciplineChoices`, `CraftingDiscoveryView.reloadDisciplineChoices`/
`reloadCharacterChoices`, `repo.CharacterRepository`. *Evidence:* the three call sites cited in AC 2;
`CharacterRepository.loadAllCharacterCrafting` opens its own `Db.open()` connection and runs SQL.
*Impact:* the six listed exit criteria are unaffected (they name `Gw2App`, `EctoView`, `Main.java`),
but Phase 3's **objective** and its high-level story "update JavaFX views/controllers to call
application services only" are not fully met, and Phase 4 would need this read as a use case anyway.
*Blocking?* Not against the exit criteria as written; blocking only if the planner reads the objective
strictly. Already tracked as CH-E2.

**F3 — `CraftingProfitView` recomputes total profit instead of displaying the domain value.**
*Affected:* `CraftingProfitView.CraftRow`/`colTotalProfit`/`applyClientFilterAndSort`,
`CraftingProfitController.UiRow.totalProfitCopper`. *Evidence:* AC 2 above. *Impact:* no current
wrong value; a future change to the domain's total formula would silently not reach the displayed or
sorted total, and the view carries a permanently-zero `totalProfitCopper` property. *Blocking?* No.
Already tracked as CH-E2.

**F4 — Persistence row types cross the application layer into the views.** *Affected:*
`BankContentsService`, `MaterialStorageService`, `BankView`, `MaterialsView`. *Evidence:* AC 2 above.
*Impact:* presentation compiles against `repo.*` types, which the Phase 4 HTTP boundary will have to
resolve. *Blocking?* No — and per §34 not classified as current debt.

**Proposed criterion transfer (subject to explicit planner/user disposition, not performed here):**
F2 and F3 are the unfinished remainder of Phase 3's high-level story "Update JavaFX views/controllers
to call application services only". Destination options, with rationale: (a) a new Phase 3 story
adding a character/selector read use case and passing the domain total through to `CraftRow` — keeps
the objective honest inside this milestone and is small; or (b) explicit transfer to **Phase 4**
(`ROADMAP.md` §8), whose "business rules must not be implemented in controllers" requirement and HTTP
contract would need the same selector use case regardless, making the work non-duplicated there. This
review recommends (a) for F3 (trivial, removes a live duplicate-ownership path) and treats (b) as
defensible for F2. A proposal alone satisfies no criterion.

### Verification limitations

- Review depth was source reading plus the two bounded Maven runs above; the application was not
  launched interactively, and no performance or equivalence measurement was re-run (AC 3's condition
  for that was not met — see AC 3).
- The TestFX/IT layer is excluded from `./mvnw test` by design and is headful-only, so the
  presentation-delegation evidence is not standing CI protection; it holds for the run recorded above.
- `STORY-APP-009`'s own limitations stand and were not re-derived: the bank/materials repository tests
  use representative rows on a disposable schema rather than live account data, and icon loading and
  rarity borders are unasserted.
- `CraftingProfitEquivalenceRealDbIT` remains a manual before/after tool whose reference lives only in
  `target/`; its corroborating value above comes from a retained report of the story's own run, not
  from a run of this review.
- F1's "HEAD would not meet the limit" is an inference from recorded stage attribution, not a measured
  claim.
- Python `agent/runtime` code and the §10 audit's CH-19 finding were outside this milestone's
  assessment scope.

### Planner disposition ? 2026-09-23

F2 and F3 remain current Phase 3 work: STORY-APP-010 and STORY-APP-011 respectively address the concrete gaps in the phase objective and presentation-delegation high-level story. No criterion transfer is accepted. The completed review is reused; no duplicate review is scheduled.

F1 remains pending closure assessment: the reported working-tree preservation concern is not disproved or resolved by this planning pass. Its recorded impact does not invalidate the existing performance acceptance, and no new implementation or benchmark story is justified solely by an unverified version-control state. The planner retains a continuity reminder for explicit disposition before closure.

F4 is non-blocking under section 34's recorded assessment; no current-phase remediation is commissioned for that observation. No work outside the current phase is planned. Phase 3 remains open pending the two scoped stories and subsequent closure assessment.

### Final planner disposition — 2026-09-23

F1 is explicitly accepted by the planner as a non-blocking preservation risk for milestone closure, not marked technically resolved. The review establishes that it falsifies no Phase 3 exit criterion; STORY-PERF-001 records measurements and dated Product Owner acceptance of the tested working tree, rather than a clean checkout. Current version-control state has not been inspected in this planning pass and no claim of a committed or reconstructible revision is made. Neither the supplied phase criteria nor TARGET_ARCHITECTURE.md §34 requires a commit as an additional closure gate. No implementation retry, benchmark, or human product/architecture decision is justified by this preservation observation alone.

F2 and F3 are resolved by the completed STORY-APP-010 and STORY-APP-011 respectively, as recorded in their completion evidence. Their behavior-preserving changes retain the accepted load path; no concrete reason for a fresh performance measurement is recorded. No criterion transfer is needed or accepted. The review's non-blocking assessment of F4 stands without commissioning additional work.

Together with this completed bounded review, the supplied completion evidence satisfies Phase 3's objective and all six exit criteria. The planner closes milestone-03; no new stories are created in this pass.

## Blockers

None. Review completed. F1 is recommended for planner/user disposition before Phase 3 closure; no
finding is treated as accepted by this review, and milestone closure is not claimed.
