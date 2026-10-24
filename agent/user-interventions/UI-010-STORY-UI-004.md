# User Intervention

## Status

RESOLVED

## Story

STORY-UI-004

## Reason

Automatic retries exhausted (2) without the evaluator confirming completion.

## Claude Response

# CLAUDE_RESULT — STORY-UI-004

Story: `agent/stories/STORY-UI-004-remove-legacy-javafx-application.md`
(matches `agent/CURRENT_STORY.md`).

Outcome: **blocked on a QA test pass.** The production side of the handed-back deficiency is done:
every application and utility class whose only caller was a retired JavaFX view is deleted, the
documentation is reconciled with the resulting inventory, and the QA-required checks plus the retained
API/application suites pass against the tree as it now stands. What is not done, and cannot be done by
this role, is the matching change to six QA-owned test files that still reference the deleted classes.
Until QA makes it, `./mvnw test-compile` fails, so Surefire — and the CI gate — cannot run at all.
No test file was created, modified or deleted in this invocation.

## The handed-back deficiency

*Remove the production code used only by the retired desktop flows, including
`application.EctoSalvageService`, `application.InitialSetupService` and `util.CoinUtils`, plus any
exclusively supporting production code; preserve components with actual backend consumers; reconcile
the affected documentation; review and run relevant retained tests and update the QA evidence.*

This reverses the previous invocation's decision to retain those three classes, which the evaluator
rejected as the primary deficiency ("desktop-exclusive application and utility code remains in
production source after its only runtime callers were removed"). They are now removed.

## Removed (5 production files)

| Removed | Why it is desktop-exclusive |
| --- | --- |
| `src/main/java/application/EctoSalvageService.java` | `EctoView` was its sole caller; no route maps to it (`Gw2ApiApplicationBootTest.theRemovedEctoSalvageRouteIsNotMapped()`), and the browser evaluates the Ectoplasm scenarios itself. |
| `src/main/java/ecto/EctoSalvageCalculator.java` | Its own javadoc named `EctoSalvageService` as the sole caller. The `ecto` package is now empty and the directory is gone. |
| `src/main/java/application/InitialSetupService.java` | The "First-time DB Setup" use case the removed home screen invoked. No other production caller; the browser has no equivalent flow and AC4 forbids adding one. |
| `src/main/java/sync/IconSyncGateway.java` | Exclusively supporting code: its javadoc states it "exists purely to give `application.InitialSetupService` a replaceable collaborator", and `InitialSetupService` was its only production consumer. |
| `src/main/java/util/CoinUtils.java` | The `"12g 34s 56c"` text parser/formatter for the desktop input fields. The browser sends numeric copper. No production consumer. |

One retained file was edited: `src/main/java/api/tp/EctoLivePriceGateway.java` — javadoc only, to stop
naming the deleted `EctoSalvageService` as the reason the seam exists and to name its actual remaining
consumer.

## Preserved, with the consumer that keeps each alive

- `api.tp.EctoLivePriceGateway` → `application.ItemReadService` → `web.ItemReadApiController`
  (`/api/items/...`, the browser's item-detail route). **Not** desktop-exclusive despite the name.
- `sync.AccountRefreshGateway` → `application.AccountRefreshService`, `application.CurrentAccountLuckService`.
- `sync.GlobalDataRefreshGateway` → `application.GlobalDataRefreshService`.
- `application.TradingPostPriceRefreshService` → `web.CraftingDiscoveryApiController`,
  `web.PriceRefreshApiController`, `web.PriceRefreshVariant`, `application.TradingPostRefreshCoordinator`.
- `sync.IconSync`, `repo.AppConfig.ICON_CACHE_DIR`, `api.tp.TpPriceApi`, `tradingpost.TradingPostFeePolicy`,
  `craft.PriceQuote` — all reached by `GlobalDataRefreshGateway`, `TpSync`, `web.Gw2ApiApplication` or the
  crafting chain. Checked individually after the deletions; none was left orphaned by them.

## Verification (this invocation)

`JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root.

1. **Production compiles without the removed classes** — `./mvnw -o clean compile` → exit 0,
   `Compiling 223 source files with javac [debug release 25] to target\classes`, `BUILD SUCCESS`
   (228 before; the five deletions account for the difference).
2. **`./mvnw -o test-compile` → exit 1.** This is the blocker, not a production defect; the six failing
   files are listed under *Blocker* below.
3. **QA-required checks pass.** Because Maven cannot reach Surefire, both were compiled and run directly,
   the path `tasks/lessons.md` documents: `javac -cp "target\classes;<test classpath>" -sourcepath
   src/test/java -d <tmp outside the repo> src/test/java/web/LegacyJavaFxRemovalTest.java
   src/test/java/web/Gw2ApiApplicationBootTest.java` (exit 0; implicit compilation produced exactly those
   two class files and read none of the broken sources), then `java -cp "<tmp>;src\test\resources;
   target\classes;<classpath>;junit-platform-launcher;junit-platform-console;junit-platform-reporting"
   org.junit.platform.console.ConsoleLauncher execute --select-class=web.LegacyJavaFxRemovalTest
   --select-class=web.Gw2ApiApplicationBootTest`. The classpath was built with
   `dependency:build-classpath -DincludeScope=test` and the 13 `javafx-*`/`testfx-*` jars were filtered
   out, so the run is stronger evidence than Surefire's would have been. Exit 0:

```text
╷
├─ JUnit Jupiter ✔
│  ├─ LegacyJavaFxRemovalTest ✔
│  │  └─ productionClasspathDoesNotContainTheLegacyJavaFxApplicationEntryPoint() ✔
│  └─ Gw2ApiApplicationBootTest ✔
│     ├─ applicationStartsAndExposesTheCraftingControllersOverAnEmbeddedServer() ✔
│     ├─ theSynchronizationTriggersAndTaskStatusRouteAreWiredToTheSharedTaskFacility() ✔
│     ├─ theSelectorOptionsRouteIsWiredToTheExistingCharacterSelectionUseCase() ✔
│     ├─ theProfitRoutesAreWiredToTheCachedUseCaseAndTheSharedRefreshCoordinators() ✔
│     ├─ theConfiguredFactoryHandsOutAFreshApplicationServicePerCall() ✔
│     ├─ theAccountReadRoutesAreWiredToTheExistingBankAndMaterialUseCases() ✔
│     ├─ theResolutionDetailRoutesAreMappedAtTheirDecidedPaths() ✔
│     └─ theRemovedEctoSalvageRouteIsNotMapped() ✔
└─ jqwik (JUnit Platform) ✔

Test run finished after 3024 ms
[         4 containers found      ]
[         4 containers successful ]
[         0 containers failed     ]
[         9 tests found           ]
[         9 tests successful      ]
[         0 tests failed          ]
```

4. **Retained behavior around the removal passes** — the same compile/run path over
   `web.CraftingProfitApiControllerTest`, `web.CraftingDiscoveryApiControllerTest`,
   `web.GlobalSyncApiControllerTest`, `web.AccountAccessContractTest`, `web.ItemReadApiControllerTest`,
   `web.PriceRefreshApiControllerTest`, `application.ItemReadServiceTest`,
   `application.TradingPostPriceRefreshServiceTest`, `application.TradingPostCoverageCoordinationTest`,
   `application.TradingPostRefreshCoordinatorTest` → exit 0:
   `[ 102 tests found ] [ 102 tests successful ] [ 0 tests failed ]`. `ItemReadApiControllerTest` and
   `ItemReadServiceTest` are the ones that matter most here: they cover the `EctoLivePriceGateway`
   consumer the removal preserved. All ten compiled without pulling in any of the six broken sources.
5. **No JavaFX anywhere** — `grep -rln "javafx\|org.testfx" src/main/ src/test/` returns nothing, and
   `src/main/java` has no default-package source.
6. **No frontend run.** No frontend file was changed by this invocation and `git status` shows no dirty
   file under `frontend/`.
7. **Nothing of QA's was touched** — `src/test/java/web/LegacyJavaFxRemovalTest.java` still hashes to
   `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3` (the plan's protected hash), the
   index is empty, and the only modified file under `src/test/` is the maintainer's
   `sync/CharacterRosterPublicationTest.java`.

## Documentation reconciled

- `docs/CURRENT_ARCHITECTURE.md` §9 — the paragraph that justified retaining the three classes is
  replaced by the removal and by the explicit retained-consumer list above. §9 paragraph 2 also lost the
  sentence "`./mvnw test-compile` succeeds again": that claim is dated by construction, and it is now
  false. The transient build state lives in this result instead.
- `docs/CURRENT_STATE_SPEC.md` — the UI list entry now names the application and utility classes as part
  of what was removed, not only the entry point, views and tests.
- `docs/KNOWN_PROBLEMS.md` KP-25 — the three orphans this story's view deletion created are recorded as
  removed rather than as open orphaned production code. The rest of KP-25 (`api/Gw2PriceFetch.java`,
  `model/Price.java`, `craft.ResolvedNeedMapper#toNode`, the unreferenced frontend `ectoApi.ts` /
  `useEctoSalvage.ts`) is untouched: none of it is desktop-exclusive.

No other document was changed. `docs/ROADMAP.md` line 204 names `InitialSetupService` inside a
completed Phase 3 item; that is history of what was extracted then, so it is left as written.

## Blocker — six QA-owned test files reference the removed classes

`./mvnw -o test-compile` now fails in `default-testCompile` on exactly these files. Deleting or editing
them is a QA write (`workflow_service/cycle.py` reverts any test file this role writes and logs it as an
integrity finding), so the production removal the evaluator required and the test change it implies
cannot land in the same role. The equivalent retained coverage and the change each file needs:

| File | Symbol | What it needs |
| --- | --- | --- |
| `src/test/java/application/EctoSalvageServiceTest.java` | `application.EctoSalvageService` | **Retire.** Its production subject is gone. The calculation itself is `DOMAIN_SPEC.md` §45–47 and is now browser-owned; `frontend/src/ecto/__tests__/EctoSalvageScreen.spec.ts` covers it, and `Gw2ApiApplicationBootTest.theRemovedEctoSalvageRouteIsNotMapped()` covers the absence of the backend route. |
| `src/test/java/ecto/EctoSalvageCalculatorTest.java` | `ecto.EctoSalvageCalculator` | **Retire** (same subject; the `ecto` package no longer exists). The shared fee rule it exercised through `tradingpost.TradingPostFeePolicy` keeps its own retained coverage. |
| `src/test/java/application/InitialSetupServiceTest.java` | `application.InitialSetupService`, `sync.IconSyncGateway` | **Retire.** Both subjects are gone. The individual steps it sequenced keep coverage through `application.AccountRefreshServiceTest`, `application.GlobalDataRefreshServiceTest` and `application.TradingPostPriceRefreshServiceTest`. |
| `src/test/java/application/CraftingProfitInputCaptureEquivalenceIT.java` | `util.CoinUtils` (lines 12, 54, 62) | **Keep the test, retarget two call sites.** It verifies retained Crafting Profit behavior and must not be retired. `CoinUtils.parseToCopper("1g")` → `10000`, `CoinUtils.parseToCopper("10g")` → `100000`; drop the `import util.CoinUtils;`. |
| `src/test/java/application/CraftingProfitServiceRealDbPerfIT.java` | `util.CoinUtils` (lines 19, 50) | **Keep, retarget one call site** — `CoinUtils.parseToCopper("1g")` → `10000`; drop the import. |
| `src/test/java/web/CraftingProfitApiRealDbEquivalenceIT.java` | `util.CoinUtils` (lines 13, 61, 72) | **Keep, retarget two call sites** — `"1g"` → `10000`, `"10g"` → `100000`; drop the import. |

Nothing else in `src/test/` references any removed class (`grep -rn` over the five names returns only
these six files). Once they are aligned, `./mvnw -o test-compile` and the full Surefire run should
succeed: every other suite tried above compiled and passed against the tree as it stands.

## Interface Deviations

None. Both names in the plan's `interface_contract` exist and were executed verbatim:
`web.LegacyJavaFxRemovalTest.productionClasspathDoesNotContainTheLegacyJavaFxApplicationEntryPoint()`
and
`web.Gw2ApiApplicationBootTest.applicationStartsAndExposesTheCraftingControllersOverAnEmbeddedServer()`.

## QA plan: evidence for its two stale fields

(Reported, not written — `cycle.py` restores the plan byte-for-byte if this role edits it.)

- `pre_implementation_verification.status: NOT_RUN`, blaming a sandbox `AccessDeniedException` on
  `…\conf\security\java.security`, an unavailable PowerShell and a Node `EPERM`. None of the three
  reproduces here. Its exact command cannot run today for a different and real reason — the six files
  above break `default-testCompile` — but both tests it names were executed and passed, as quoted above.
- `coverage_review`: still no JaCoCo report, and none can be produced until `test-compile` succeeds. No
  coverage figure is claimed.

## Follow-up Findings

F001: `frontend/src/api/ectoApi.ts` and `frontend/src/ecto/useEctoSalvage.ts` still target
`/api/ecto/salvage`, a route that no controller maps and whose backing service this story deleted. No
frontend module imports either file — `EctoSalvageScreen.vue` computes the scenarios itself — so they are
unreachable frontend code rather than a broken feature. They are browser-side, never part of a desktop
flow, so they were left to `KNOWN_PROBLEMS.md` KP-25, which already lists them.

F002: `sync.IconSync.syncItemIconsToDisk(Path)` lost its last production caller with
`InitialSetupService`/`IconSyncGateway`: nothing in `src/main` now invokes it, so the icon files on disk
are only ever written by whatever wrote them before. It was deliberately **not** deleted — it is one
method of the retained icon subsystem that `ADR-004-web-item-icon-delivery.md` describes and that
`KNOWN_PROBLEMS.md` KP-22 tracks an open stale-path defect against, so removing it is a design decision
about icon delivery, not desktop cleanup. A decision is needed on whether the browser icon path should
call it or whether it goes with the ADR.

F003: `src/main/resources/images/` ships 13 files inside the backend jar (favicon set,
`site.webmanifest`, `logo.png`, `logo-no-bg.png`, `app-icon.png`, web-app-manifest icons, two
`ChatGPT Image …png` sources). Only `logo.png` was ever loaded from Java, by the removed home screen; the
other twelve were never referenced at all. They are brand assets rather than desktop UI, which is why
this story kept them, but the browser serves its own `frontend/public/favicon.ico` and nothing reads
them from the backend.

F004: Seven retained non-desktop tests carry javadoc naming deleted desktop classes or retired desktop
test files as the origin of the behavior they verify (`application/AccountRefreshServiceTest`,
`application/GlobalDataRefreshServiceTest`, `application/MaterialStorageServiceTest`,
`application/CraftingProfitServiceRealDbPerfIT`, `repo/BankRepositoryTest`,
`repo/CharacterSelectionCraftingPlanIntegrationTest` — which cites "CraftingProfitController.java lines
~104-115" — and `web/CraftingSelectorOptionsApiControllerTest`, which points at the retired
`CraftingProfitViewSelectorIT`/`CraftingDiscoveryViewSelectorIT`). Harmless to the build and QA-owned, so
left alone; a QA pass could reword them while it is in those files.

F005: A production removal and the test change it forces cannot land in one cycle. This story has now
spent four cycles alternating between the two roles — production deletion, QA retirement of 31 files,
documentation reconciliation, and now a second production deletion that again needs QA. The coding role
can only hand over a file list; `cycle.py`'s repair channels are shaped for name alignment
(`interface_deviation`) or evaluation-driven revision. A deletion-shaped handover — "these production
symbols are gone, here are the test files that reference them" — emitted at the point the production
change is made would collapse the alternation.

## User Resolution

Maintainer deleted src/main/resources/images/logo.png, reconciled docs/CURRENT_ARCHITECTURE.md and verified the full test-compile.

## Resolution Notes

The last evaluation's only open items. Evidence is in the story Result under 'Maintainer follow-up'. The story goes straight back to evaluation.
