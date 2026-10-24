## Story ID

STORY-UI-004

## Title

Remove the legacy JavaFX application and exclusive presentation code

## Status

DONE

## Milestone

milestone-06

## Goal

Retire the unsupported JavaFX UI and its exclusive runtime paths while retaining behavior used by the browser and backend.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6 exit criteria and high-level stories (JavaFX removal).
- docs/TARGET_ARCHITECTURE.md sections 2, 13 and 14 (sole supported UI and code reuse).
- docs/CURRENT_ARCHITECTURE.md sections 2, 3, 8 and 9 (current JavaFX entry point and UI paths).
- docs/TEST_STRATEGY.md sections 26, 32 and 36 (affected behavior and verification boundaries).
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.

## Context

The browser is already the canonical UI; the current architecture still records a runnable JavaFX entry point and desktop views. The Product Owner requires complete removal during milestone-06 before its health review. Shared backend consumers must be identified before any deletion.

## Acceptance Criteria

1. Inventory JavaFX views, controllers, application entry points, desktop-only resources and their direct dependencies. For each shared-looking component, identify its remaining browser/backend consumers before changing it.
2. Remove the JavaFX application entry point, views/controllers, desktop-only UI logic, exclusive FXML/styles/images and desktop-only startup/initialization paths. Remove adapters and utilities used solely by those paths; retain and, where necessary, decouple domain, crafting, repository, synchronization and application services still used by the web backend.
3. Remove desktop-only UI test code and fixtures whose production subject no longer exists. Preserve or replace meaningful non-UI verification of retained shared behavior through the normal QA-owned test process.
4. The remaining application has no production path that starts JavaFX or requires a desktop display. Do not add replacement desktop infrastructure or web features solely to mimic retired desktop controls.
5. Update current architecture and runtime documentation to describe the delivered web/backend runtime and removal accurately. Identify any remaining JavaFX build/configuration remnants for STORY-INFRA-005.

## Required Tests

- QA verifies retained shared backend behavior and browser/API paths affected by deleting desktop entry points or adapters, using meaningful existing or new checks.
- QA reviews removed desktop test coverage and affected backend coverage reports; removed UI-only tests must not silently remove coverage of retained business behavior.
- A targeted startup/build check confirms the backend can run without the JavaFX entry point; STORY-INFRA-005 completes dependency and build cleanup.

## Constraints

- No unrelated refactoring or replacement desktop framework.
- Preserve specified browser functionality and backend-owned calculations. The desktop UI is not a parity requirement.
- QA owns all tests and fixtures. The coding agent satisfies QA-defined checks without authoring tests.
- Do not change the agent workflow.

## Dependencies

None.

## Definition of Done

- The JavaFX application, exclusive production paths and resources are removed with shared consumers preserved; relevant documentation and QA evidence reflect the change.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

**Complete.** No JavaFX-exclusive production path is left in `src/main/java`, the last one being the
desktop icon download removed on 2026-10-24. Documentation matches the resulting inventory. The last
blocker is gone: `src/test/java/sync/DesktopIconAdoptionTest.java`, whose production subject no longer
exists, has been retired outside this role, so `./mvnw -o clean test-compile` succeeds and Surefire runs
again. The QA-required checks and the relevant retained suites pass on the current tree — 210 tests, 0
failures, recorded under *Verification of the current tree* below. No test file was created, modified or
deleted by this role in any invocation.

> **Superseded statements.** Everything below of the form "`test-compile` fails on
> `DesktopIconAdoptionTest`" or "the story is blocked on QA" described the tree before that retirement
> and is false as of 2026-10-24. Those passages are kept only where they explain why a production
> removal was made; the live build and test state is the *Verification of the current tree* section.

### Desktop icon synchronization removed (2026-10-24 invocation) — the handed-back deficiency

This reverses the earlier decision, recorded as finding F002, to keep `IconSync.syncItemIconsToDisk`
because it belonged to an ADR-documented subsystem. The evaluator ruled the retained desktop-only
download the primary deficiency, so it is now gone.

Removed:

- `sync.IconSync.syncItemIconsToDisk(Path)` — the bulk desktop download. It selected every item with
  `icon_url` and no `icon_path`, downloaded the binary and recorded the local file in `items.icon_path`.
  Its last caller went with `InitialSetupService`/`IconSyncGateway`, so nothing in `src/main` invoked it.
  Its private supporting code went with it: the `IconUpdate` record, `flushIconPathUpdates` (the only
  `UPDATE items SET icon_path` statement in the tree) and the `ICON_FLUSH_BATCH` constant.
- `src/main/java/sync/DesktopIconAdoption.java` — the per-item legacy-file adoption step, byte-comparing
  `items/{itemId}.png` against a freshly fetched canonical image. `syncItemIconsToDisk` was its only
  production consumer, and its `LEGACY_ITEMS_DIRECTORY` constant and `Outcome`/`Adoption` types had no
  other reader.
- `infra.icons.FilesystemIconStore.assetsRoot()` — a public accessor whose only remaining caller was the
  removed method's completion message. The field it exposed stays; the class itself is retained shared
  browser infrastructure.

Preserved in full, because the browser icon path is their live consumer: `application.icons.IconDelivery`
and `ItemIconUrls`, `infra.icons.FilesystemIconStore`, `IconAcquisition`, `HttpIconImageFetcher`,
`IconSourcePolicy`, `IconStore`, `IconImageFetcher`, `IconAcquisitionResult`, `IconFetchResult`,
`IconImageBytes`, `IconCacheBounds`, `IconStorageException`, `IconSource`, `StoredIcon`, the
`ICON_CACHE_DIR` configuration in `repo.AppConfig` and its wiring in `web.Gw2ApiApplication`, and the
whole icon-*metadata* half of `IconSync` — `syncItemIconUrls`, `syncItemMetadataByIds`,
`repairItemIconMetadata`, `applyItemMetadata`, `referencedOrUnknownItemIds` and the standalone `main`
with its `--dry-run` — which `sync.GlobalDataRefreshGateway` and `application.ItemReadService` call. Each
of the fourteen `infra.icons`/`application.icons` types was checked individually for a consumer other
than the deleted desktop path, and every one has one.

`StoredIcon.file` was deliberately kept: `IconDelivery` and `FilesystemIconStore` are shared components
the deficiency requires preserving, and QA's `IconDeliveryTest`/`FilesystemIconStoreTest` construct the
record, so changing its shape would break retained coverage for no production gain. Its javadoc, which
described the component as existing "for the desktop path only", now describes what it actually is.

No production code writes or reads `items.icon_path` as a download target any more. The column is still
selected by `repo.ItemRepository`, `BankRepository` and `MaterialStorageRepository`, which by design keep
it out of every browser response (`STORY-API-009`); it will simply stay null. That is a behavior change
only for a desktop renderer, and there is none. Browser icons never consulted the column: they resolve
through the source-keyed `assets-v1` cache and repair a miss on demand.

Three javadoc/comment sites in retained files that named the removed path or the JavaFX flow were
corrected in place (`IconSync.main`, `IconSync.syncItemIconUrls`, `FilesystemIconStore.ASSETS_DIRECTORY`,
`StoredIcon`). No other production behavior was touched.

### Verification of the current tree (2026-10-24, this invocation)

`JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root. Every command here was
re-run against the tree as it now stands, after `src/test/java/sync/DesktopIconAdoptionTest.java` was
retired. It supersedes the blocked-state evidence recorded further down.

**Test compilation succeeds.** A plain `./mvnw -o test-compile` answered
`Nothing to compile - all classes are up to date.` from a stale `target/`, which is not evidence, so the
recorded run is the clean one:

```text
$ ./mvnw -o clean test-compile
[INFO] Compiling 222 source files with javac [debug release 25] to target\classes
[INFO] Compiling 120 source files with javac [debug release 25] to target\test-classes
[INFO] BUILD SUCCESS
[INFO] Total time:  7.386 s
exit=0
```

All 120 remaining test sources compile against the removed production classes. The 20
`DesktopIconAdoptionTest` errors recorded below no longer occur — `git status` shows
` D src/test/java/sync/DesktopIconAdoptionTest.java`.

**The QA-required checks and the relevant retained suites pass**, run through Surefire rather than the
`javac`/`ConsoleLauncher` workaround the earlier invocations needed:

```text
$ ./mvnw -o "-Dtest=LegacyJavaFxRemovalTest,Gw2ApiApplicationBootTest,CraftingProfitApiControllerTest,\
CraftingDiscoveryApiControllerTest,GlobalSyncApiControllerTest,AccountAccessContractTest,\
ItemReadApiControllerTest,PriceRefreshApiControllerTest,ItemReadServiceTest,\
TradingPostPriceRefreshServiceTest,TradingPostCoverageCoordinationTest,TradingPostRefreshCoordinatorTest,\
IconDeliveryTest,ItemIconUrlsTest,FilesystemIconStoreTest,HttpIconImageFetcherTest,IconAcquisitionTest,\
IconImageBytesTest,IconSourcePolicyTest,IconSyncMetadataTest,ItemIconApiControllerTest" \
  -Dsurefire.failIfNoSpecifiedTests=false test

[INFO] Tests run: 13, …, in application.icons.IconDeliveryTest
[INFO] Tests run:  7, …, in application.icons.ItemIconUrlsTest
[INFO] Tests run:  1, …, in application.ItemReadServiceTest
[INFO] Tests run:  4, …, in application.TradingPostCoverageCoordinationTest
[INFO] Tests run:  5, …, in application.TradingPostPriceRefreshServiceTest
[INFO] Tests run:  5, …, in application.TradingPostRefreshCoordinatorTest
[INFO] Tests run: 15, …, in infra.icons.FilesystemIconStoreTest
[INFO] Tests run: 12, …, in infra.icons.HttpIconImageFetcherTest
[INFO] Tests run:  8, …, in infra.icons.IconAcquisitionTest
[INFO] Tests run:  7, …, in infra.icons.IconImageBytesTest
[INFO] Tests run: 11, …, in infra.icons.IconSourcePolicyTest
[INFO] Tests run: 15, …, in sync.IconSyncMetadataTest
[INFO] Tests run:  8, …, in web.AccountAccessContractTest
[INFO] Tests run: 19, …, in web.CraftingDiscoveryApiControllerTest
[INFO] Tests run: 28, …, in web.CraftingProfitApiControllerTest
[INFO] Tests run: 12, …, in web.GlobalSyncApiControllerTest
[INFO] Tests run:  8, …, in web.Gw2ApiApplicationBootTest
[INFO] Tests run: 11, …, in web.ItemIconApiControllerTest
[INFO] Tests run:  4, …, in web.ItemReadApiControllerTest
[INFO] Tests run:  1, …, in web.LegacyJavaFxRemovalTest
[INFO] Tests run: 16, …, in web.PriceRefreshApiControllerTest
[INFO] Results:
[INFO] Tests run: 210, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

Each per-class line ends `Failures: 0, Errors: 0, Skipped: 0`; the elapsed-time tails are elided and the
order is Surefire's own. The set covers every suite named in the QA plan's `test_specifications` — both
`interface_contract` entries plus `CraftingProfitApiControllerTest`, `CraftingDiscoveryApiControllerTest`,
`GlobalSyncApiControllerTest`, `AccountAccessContractTest`, `ItemReadApiControllerTest`,
`PriceRefreshApiControllerTest`, `ItemReadServiceTest` and the three trading-post refresh tests — and the
browser icon-delivery coverage touched by the final production removal. The `ERROR`-level lines in the
log (`ApiExceptionHandler: Crafting calculation failed`, `BackgroundTaskService: … failed`) are the
negative-path logging of passing tests.

- `grep -rln` over `src/main/java src/test/java` for `DesktopIconAdoption`, `syncItemIconsToDisk`,
  `IconSyncGateway`, `EctoSalvageService`, `EctoSalvageCalculator`, `InitialSetupService`, `CoinUtils`
  and `assetsRoot()` → no match. Nothing in the repository references a class this story removed.
- `src/test/java/web/LegacyJavaFxRemovalTest.java` still hashes to
  `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3`, the plan's protected hash.
- The `*IT` real-database checks, including `web.ItemIconApiRealDbIT`, were **not** run: they need a
  real PostgreSQL database (`TEST_STRATEGY.md` §34), unavailable here. No claim is made from them.
- No frontend command was run and no source or documentation file was changed in this invocation; its
  scope was the two handed-back reconciliation deficiencies.

### QA plan reconciliation (reported, not written)

`agent/qa-plans/` is assigned to QA and the harness in `config.DOCUMENT_OWNERSHIP` and
`agent/runtime/qa/qa_agent.py` is its only writer, so these corrections are recorded here for QA to
apply rather than edited into `QA-STORY-UI-004.json`:

- `removed_test_paths` (31 entries) and `existing_tests_reviewed` do not mention
  `src/test/java/sync/DesktopIconAdoptionTest.java`. It is **retired**, because its production subject
  `sync.DesktopIconAdoption` was removed — 32 retired desktop files plus the 6 of the previous cycle.
- `pre_implementation_verification.status: "NOT_RUN"`, with its `java.security` `AccessDeniedException`
  and missing-PowerShell `failure_evidence`, is **superseded**: that environment failure does not
  reproduce, and the command it names now gives `BUILD SUCCESS`.
- `test_repair.review.reason` — "the retained Maven test sources do not compile against the removed
  production classes, so the test suite is not yet reviewable as a whole" — is **no longer true**.
- `test_repair.review.evidence[2]` — "Those runs do not establish that Maven test compilation or the
  complete suite passes" — is **answered** for compilation and for the QA-named suites. The complete
  suite remains the CI gate's job after publication; nothing beyond the suites above is claimed.
- `coverage_review` stays accurate: no JaCoCo report was produced and **no coverage figure is claimed**.
- Worth adding as retained coverage for AC2/AC3: `web.ItemIconApiControllerTest`,
  `application.icons.IconDeliveryTest`, `ItemIconUrlsTest`, `infra.icons.FilesystemIconStoreTest`,
  `IconAcquisitionTest`, `HttpIconImageFetcherTest`, `IconSourcePolicyTest`, `IconImageBytesTest` and
  `sync.IconSyncMetadataTest`.

### Verification recorded while blocked (historical, superseded)

`JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root.

- `./mvnw -o -q clean compile` → exit 0.
- `./mvnw -o test-compile` → **exit 1** at the time, `BUILD FAILURE` in `default-testCompile`, every
  error in `src/test/java/sync/DesktopIconAdoptionTest.java` (20 errors, `Symbol nicht gefunden: Klasse
  DesktopIconAdoption` / `Package DesktopIconAdoption ist nicht vorhanden`, at lines 58, 59, 70, 72, 87,
  89, 99, 105, 107, 122, 124, 137, 139, 152, 154, 166, 169, 179, 181, 186). It was the only failing
  file; the other 120 test sources compiled. That was the blocker and never a production defect. **It
  no longer reproduces** — see the clean `BUILD SUCCESS` above.
- `grep -rn "DesktopIconAdoption\|syncItemIconsToDisk\|assetsRoot()" src/main/java frontend/src` →
  no match. The only remaining reference at that point was that one test file.
- QA-required checks plus the retained browser icon coverage, compiled and run directly because the
  broken test file blocks Surefire (the path `tasks/lessons.md` documents): `javac -cp "target\classes;
  <test classpath>" -sourcepath src/test/java -d <tmp outside the repo>` over
  `web/LegacyJavaFxRemovalTest`, `web/Gw2ApiApplicationBootTest`, `application/icons/IconDeliveryTest`,
  `application/icons/ItemIconUrlsTest`, `infra/icons/FilesystemIconStoreTest`,
  `infra/icons/IconAcquisitionTest`, `infra/icons/HttpIconImageFetcherTest`,
  `infra/icons/IconSourcePolicyTest`, `infra/icons/IconImageBytesTest`, `web/ItemIconApiControllerTest`
  and `sync/IconSyncMetadataTest` → exit 0, producing 18 class files and reading no other test source
  (`DesktopIconAdoptionTest` is never pulled in). Then
  `java -cp "<tmp>;src\test\resources;target\classes;<classpath>;junit-platform-launcher;
  junit-platform-console;junit-platform-reporting" org.junit.platform.console.ConsoleLauncher execute
  --select-class=…` over the same eleven classes → exit 0:

```text
Test run finished after 5464 ms
[        13 containers found      ]
[        13 containers successful ]
[         0 containers failed     ]
[       108 tests found           ]
[       108 tests successful      ]
[         0 tests failed          ]
```

  The classpath came from `dependency:build-classpath -DincludeScope=test` with the 13
  `javafx-*`/`testfx-*` jars filtered out (95 entries → 82), so the `LegacyJavaFxRemovalTest` assertion
  ran against a classpath that genuinely lacks JavaFX. `ItemIconApiControllerTest` (the browser image
  endpoint), `IconDeliveryTest` (on-demand miss repair), `FilesystemIconStoreTest`/`IconAcquisitionTest`
  (the keyed cache and publication protocol) and `IconSyncMetadataTest` (the retained metadata half) are
  the ones that matter here: they are the retained browser icon-delivery coverage, and the removal left
  all of it passing.
- `web/ItemIconApiRealDbIT.java` was not run: it needs a real PostgreSQL database (`TEST_STRATEGY.md`
  §34), which this environment does not provide. No coverage claim is made from it.
- No frontend command was run: no frontend file was changed.
- `src/test/java/web/LegacyJavaFxRemovalTest.java` still hashes to
  `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3`, the plan's protected hash.

### Documentation reconciled by the icon-removal invocation

(Still accurate; the record-reconciliation invocation that followed changed no document. It re-checked
`docs/CURRENT_ARCHITECTURE.md`, `docs/CURRENT_STATE_SPEC.md` and `docs/KNOWN_PROBLEMS.md` for the stale
build-state claims the lessons file warns about and found none: none of the three says anything about
test compilation, `DesktopIconAdoptionTest` or the blocked build.)

- `docs/CURRENT_ARCHITECTURE.md` §9 — a new paragraph records the removal of `syncItemIconsToDisk` and
  `DesktopIconAdoption`, states that no production path writes `items.icon_path` while the three
  repositories still read it and keep it out of browser responses, and lists the shared browser icon
  components the removal preserved together with the retained metadata half and its consumers.
- `docs/KNOWN_PROBLEMS.md` KP-22 — the stale-path half, which was the entry's only remaining open part
  and was a property of `syncItemIconsToDisk`'s selection, is recorded as resolved by removal rather than
  by repair, and the entry's status moves from "partially resolved — the metadata half only" to
  "resolved". The metadata and browser-rendering text is unchanged.
- `docs/KNOWN_PROBLEMS.md` KP-25 — the removed download is added to the sentence listing which orphans
  `STORY-UI-004` removed, so it is not counted as remaining orphaned production code.

`docs/CURRENT_STATE_SPEC.md` needed no change: it describes no icon behavior. The QA plan
(`agent/qa-plans/QA-STORY-UI-004.json`) was not touched — it is QA/harness-owned.

### Earlier invocations (unchanged, still accurate)

#### Removed earlier (AC2) — 5 production files

- `src/main/java/application/EctoSalvageService.java` — `EctoView` was its sole caller; no route maps to
  it (`Gw2ApiApplicationBootTest.theRemovedEctoSalvageRouteIsNotMapped()` asserts that in the real
  context) and the browser evaluates the Ectoplasm scenarios itself.
- `src/main/java/ecto/EctoSalvageCalculator.java` — its javadoc named `EctoSalvageService` as the sole
  caller. The `ecto` package is now empty and the directory is gone.
- `src/main/java/application/InitialSetupService.java` — the "First-time DB Setup" use case the removed
  home screen invoked. No other production caller; AC4 forbids adding a browser equivalent.
- `src/main/java/sync/IconSyncGateway.java` — exclusively supporting code: its javadoc states it "exists
  purely to give `application.InitialSetupService` a replaceable collaborator", and that was its only
  production consumer.
- `src/main/java/util/CoinUtils.java` — the `"12g 34s 56c"` text parser/formatter for the desktop input
  fields. The browser sends numeric copper. No production consumer.

One retained file was edited, javadoc only: `api/tp/EctoLivePriceGateway.java` no longer names the
deleted `EctoSalvageService` as the reason the seam exists, and names its actual remaining consumer.

### Preserved, each with the consumer that keeps it alive (AC1)

- `api.tp.EctoLivePriceGateway` → `application.ItemReadService` → `web.ItemReadApiController`, the
  browser's item-detail route. Not desktop-exclusive despite the name.
- `sync.AccountRefreshGateway` → `AccountRefreshService`, `CurrentAccountLuckService`.
- `sync.GlobalDataRefreshGateway` → `GlobalDataRefreshService`.
- `application.TradingPostPriceRefreshService` → `CraftingDiscoveryApiController`,
  `PriceRefreshApiController`, `PriceRefreshVariant`, `TradingPostRefreshCoordinator`.
- `sync.IconSync`, `repo.AppConfig.ICON_CACHE_DIR`, `api.tp.TpPriceApi`,
  `tradingpost.TradingPostFeePolicy`, `craft.PriceQuote` — each checked individually after the
  deletions; all still reached from `GlobalDataRefreshGateway`, `TpSync`, `web.Gw2ApiApplication` or the
  crafting chain. The earlier inventory of view imports (`AccountRefreshService`, `BankContentsService`,
  `CharacterSelectionService`, `CraftingDiscoveryService`, `CraftingProfitService`,
  `GlobalDataRefreshService`, `MaterialStorageService`, `repo.BankRepository`, `repo.ItemRepository`,
  `repo.DiscChoice`, the `craft.*` package, `repo.Db.TEST_SCHEMA_PROPERTY`, `sync.DesktopIconAdoption`)
  is unchanged and all of it keeps live `web.*` consumers. `sync.DesktopIconAdoption` was the one entry
  of that inventory whose only consumer was itself desktop-only, which is why the 2026-10-24 invocation
  removed it.

#### Verification recorded by that earlier invocation

`JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root.

- `./mvnw -o clean compile` → exit 0, `Compiling 223 source files with javac [debug release 25] to
  target\classes`, `BUILD SUCCESS`. 228 before; the five deletions account for the difference.
- `./mvnw -o test-compile` → **exit 1** in `default-testCompile`, on the six files listed under
  Blockers. This is the blocker, not a production defect.
- QA-required checks (AC4), compiled and run directly because Maven cannot reach Surefire — the path
  `tasks/lessons.md` documents: `javac -cp "target\classes;<test classpath>" -sourcepath src/test/java
  -d <tmp outside the repo> src/test/java/web/LegacyJavaFxRemovalTest.java
  src/test/java/web/Gw2ApiApplicationBootTest.java` → exit 0, producing exactly those two class files
  and reading none of the broken sources; then `java -cp "<tmp>;src\test\resources;target\classes;
  <classpath>;junit-platform-launcher;junit-platform-console;junit-platform-reporting"
  org.junit.platform.console.ConsoleLauncher execute --select-class=web.LegacyJavaFxRemovalTest
  --select-class=web.Gw2ApiApplicationBootTest` → exit 0. The classpath came from
  `dependency:build-classpath -DincludeScope=test` with the 13 `javafx-*`/`testfx-*` jars filtered out,
  so the run is stronger evidence than Surefire's would have been:

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

- Retained behavior around the removal, same compile/run path over
  `web.CraftingProfitApiControllerTest`, `web.CraftingDiscoveryApiControllerTest`,
  `web.GlobalSyncApiControllerTest`, `web.AccountAccessContractTest`, `web.ItemReadApiControllerTest`,
  `web.PriceRefreshApiControllerTest`, `application.ItemReadServiceTest`,
  `application.TradingPostPriceRefreshServiceTest`, `application.TradingPostCoverageCoordinationTest`,
  `application.TradingPostRefreshCoordinatorTest` → exit 0,
  `[ 102 tests found ] [ 102 tests successful ] [ 0 tests failed ]`. The two `ItemRead*` suites are the
  ones that matter most: they cover the `EctoLivePriceGateway` consumer the removal preserved. All ten
  compiled without pulling in any of the six broken sources.
- `grep -rln "javafx\|org.testfx" src/main/ src/test/` returns nothing; `src/main/java` has no
  default-package source; `src/test/java` has no default-package source and no `uiverify/` directory.
- No frontend command was run: no frontend file was changed and `git status` shows none dirty.
- `src/test/java/web/LegacyJavaFxRemovalTest.java` still hashes to
  `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3`, the plan's protected hash; the
  index is empty; the only modified file under `src/test/` is the maintainer's
  `sync/CharacterRosterPublicationTest.java`.

#### Documentation reconciled earlier (AC5)

- `docs/CURRENT_ARCHITECTURE.md` §9 — the paragraph justifying the retention of the three classes is
  replaced by the removal and the explicit retained-consumer list. §9 paragraph 2 also lost the sentence
  "`./mvnw test-compile` succeeds again": that claim is dated by construction and is now false, so the
  transient build state lives in this Result instead. The remnant list for STORY-INFRA-005 (seven
  `org.openjfx` dependencies, the `javafx.version`/`javafx.platform` properties, the
  `org.openjfx:javafx-maven-plugin` and the `main.class` property still naming `Gw2App`,
  `org.testfx:testfx-junit5`, and `-Djavafx.platform=linux` in the CI backend job) is unchanged and
  still exact; none of it is on a production code path.
- `docs/CURRENT_STATE_SPEC.md` — the UI list entry now names the application and utility classes as part
  of what was removed, not only the entry point, views and tests.
- `docs/KNOWN_PROBLEMS.md` KP-25 — the three orphans this story's view deletion created are recorded as
  removed rather than as open orphaned production code. The rest of KP-25 is untouched: none of it is
  desktop-exclusive.

No other document was changed. `docs/ROADMAP.md` line 204 names `InitialSetupService` inside a completed
Phase 3 item; that is a historical record of what was extracted then.

### Working tree

The tree carries the maintainer's concurrent work for other stories (modified `application.*`/`repo.*`/
`sync.*`/`web.*` files, the untracked `application/access`, `infra/access`, `web/access` packages, and
`src/test/java/sync/CharacterRosterPublicationTest.java`). It was left exactly as found, and every
command above ran against that tree. Apart from this Result, `CLAUDE_RESULT.md`, `tasks/lessons.md`, the
five deletions, the one javadoc edit and the three documentation files named above, nothing was
modified. The final record-reconciliation invocation (2026-10-24) changed no source or documentation
file at all: it wrote only this Result and `CLAUDE_RESULT.md`.

#### Maintainer follow-up (2026-10-23)

After that evaluation, the maintainer closed two remaining items by hand:

- Deleted `src/main/resources/images/logo.png`, the only resource the removed JavaFX home screen
  loaded (no Java, frontend or configuration file references it), and reconciled the resource inventory
  in `docs/CURRENT_ARCHITECTURE.md` §9. The other image assets were never referenced from Java and stay.
- QA removed the six tests of the classes deleted up to that point, which made the full test sources
  compile at that moment: `./mvnw -o test-compile` → `BUILD SUCCESS`, and
  `./mvnw -o "-Dtest=LegacyJavaFxRemovalTest,Gw2ApiApplicationBootTest" test` → `Tests run: 9,
  Failures: 0, Errors: 0, Skipped: 0`. The 2026-10-24 icon removal re-opened test compilation for one
  further file, which has since been retired in turn — *Verification of the current tree* above is the
  live state.
- The earlier `java.security` access failure does not reproduce outside the agent sandbox. The full
  regression suite runs in GitHub Actions after publication.

### Maintainer follow-up 2 (2026-10-24)

The last evaluation's only open item was QA-owned: `src/test/java/sync/DesktopIconAdoptionTest.java`
still tested the deleted `sync.DesktopIconAdoption`. The maintainer deleted it. No other source in
`src/` now references any removed class (`DesktopIconAdoption`, `IconSyncGateway`, `EctoSalvageService`,
`EctoSalvageCalculator`, `InitialSetupService`, `CoinUtils`). With `JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`:

- `./mvnw -o test-compile` → `[INFO] BUILD SUCCESS`
- `./mvnw -o "-Dtest=LegacyJavaFxRemovalTest,Gw2ApiApplicationBootTest,IconSync*,*Icon*" -Dsurefire.failIfNoSpecifiedTests=false test`:
  `LegacyJavaFxRemovalTest` 1/0 failures, `Gw2ApiApplicationBootTest` 8/0, and the retained browser icon coverage
  `IconDeliveryTest` 13/0, `ItemIconUrlsTest` 7/0, `FilesystemIconStoreTest` 15/0, `HttpIconImageFetcherTest` 12/0,
  `IconAcquisitionTest` 8/0, `IconImageBytesTest` 7/0, `IconSourcePolicyTest` 11/0, `IconSyncMetadataTest` 15/0,
  `ItemIconApiControllerTest` 11/0.
- The same run had 2 errors in the local real-database check `web.ItemIconApiRealDbIT`. They are unrelated to
  this story: `BankContentsService.getBankContents` reads `account_bank` without an account scope, which the
  STORY-INFRA-004 schema gate rejects (`ManagedSchema.rejectUnscopedAccountAccess`). Recorded as F007.

This was independently re-verified in the following invocation against the same tree, with the `*IT`
checks excluded by naming classes explicitly: `./mvnw -o clean test-compile` → `BUILD SUCCESS`, and a
21-class Surefire run → `Tests run: 210, Failures: 0, Errors: 0, Skipped: 0`. Both outputs are quoted in
*Verification of the current tree* above.

## Blockers

None.

## Follow-up Findings

F001: `frontend/src/api/ectoApi.ts` and `frontend/src/ecto/useEctoSalvage.ts` still target
`/api/ecto/salvage`, a route that no controller maps and whose backing service this story deleted. No
frontend module imports either file — `EctoSalvageScreen.vue` computes the scenarios itself — so they are
unreachable frontend code rather than a broken feature. They are browser-side and were never part of a
desktop flow, so they are left to `KNOWN_PROBLEMS.md` KP-25, which already lists them.

F002 (closed in scope, 2026-10-24): `sync.IconSync.syncItemIconsToDisk(Path)` and
`sync.DesktopIconAdoption` were removed, so this finding needs no planner disposition. What remains is a
documentation consequence for the architect, who owns `docs/architecture/decisions/`:
`ADR-004-web-item-icon-delivery.md` names `syncItemIconsToDisk()` in its context and both ADR-004 and
`ADR-005-persistent-web-icon-cache.md` carry consequences phrased as "preserve desktop disk caching" and
"converge future desktop/web binary acquisition on one adapter". The decisions themselves still hold for
the browser path, which is untouched, but there is no desktop side left to preserve or converge with.

F003: `src/main/resources/images/` ships 12 files inside the backend jar (favicon set,
`site.webmanifest`, `logo-no-bg.png`, `app-icon.png`, web-app-manifest icons and two `ChatGPT Image …png`
sources). None of them is referenced from Java since the maintainer deleted `logo.png`, the only one the
removed home screen loaded. They are brand assets rather than desktop UI, which is why this story kept
them, but the browser serves its own `frontend/public/favicon.ico` and nothing reads them from the
backend.

F004: Seven retained non-desktop tests carry javadoc naming deleted desktop classes or retired desktop
test files as the origin of the behavior they verify (`application/AccountRefreshServiceTest`,
`application/GlobalDataRefreshServiceTest`, `application/MaterialStorageServiceTest`,
`application/CraftingProfitServiceRealDbPerfIT`, `repo/BankRepositoryTest`,
`repo/CharacterSelectionCraftingPlanIntegrationTest` — which cites "CraftingProfitController.java lines
~104-115" — and `web/CraftingSelectorOptionsApiControllerTest`, which points at the retired
`CraftingProfitViewSelectorIT`/`CraftingDiscoveryViewSelectorIT`). Harmless to the build and QA-owned,
so left alone; a QA pass could reword them while it is in those files.

F005: A production removal and the test change it forces cannot land in one cycle. This story spent six
cycles alternating between the two roles — production deletion, QA retirement of 31 files, documentation
reconciliation, a second production deletion with QA retirement of 6 files, a third production deletion
that again needed a retirement of 1 file, and finally a record-reconciliation pass whose only work was
re-running the commands the previous cycle was blocked from running. The coding role can only hand over
a file list; `cycle.py`'s repair channels are shaped for name alignment (`interface_deviation`) or
evaluation-driven revision. A deletion-shaped handover — "these production symbols are gone, here are
the test files that reference them" — emitted at the moment the production change is made would collapse
the alternation.

F006 (closed 2026-10-24): `src/test/java/sync/DesktopIconAdoptionTest.java`, the sole referent of a
deleted production class, has been retired outside this role. Nothing in `src/` references it or its
subject and `./mvnw -o clean test-compile` succeeds, so no planner disposition is needed.

F007: `web.ItemIconApiRealDbIT` (local real-database check) errors in two of three tests because
`application.BankContentsService.getBankContents` → `repo.BankRepository.loadBankSlots` still reads
`account_bank` without an account scope, which the STORY-INFRA-004 schema gate rejects. Either the bank read
or the IT needs to move to the account-scoped path (STORY-APP-015 scope).

## Follow-up Findings Disposition

F001: DEFERRED — `docs/KNOWN_PROBLEMS.md` KP-25 records these unreachable browser modules; removing them is optional cleanup outside Phase 6 account isolation.
F003: DISMISSED — the retained files are brand assets, not JavaFX-exclusive resources; `STORY-INFRA-005` already covers obsolete desktop-only resources.
F004: DISMISSED — historical test javadoc is harmless; QA owns those retained tests and the finding does not identify a behavior defect.
F005: DISMISSED — this concerns the agent workflow, which the Phase 6 application backlog does not change.
F007: FOLLOW-UP STORY — STORY-SYNC-007
