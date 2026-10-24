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

Outcome: **complete.** The production side carries no JavaFX-exclusive path, documentation is
reconciled, and the last blocker is gone: `src/test/java/sync/DesktopIconAdoptionTest.java` — the sole
test of the removed `sync.DesktopIconAdoption` — has been retired outside this role, so
`./mvnw -o clean test-compile` now succeeds and Surefire runs the whole suite again. The QA-required
checks and the retained API/service/icon suites pass on the current tree (210 tests, 0 failures; see
*Verification*). No test file was created, modified or deleted by this role in any invocation.

> **Superseded statements.** Every sentence in this file of the form "`test-compile` fails on
> `DesktopIconAdoptionTest`" described the tree before that retirement and is false as of 2026-10-24.
> The *Verification* section below has been rewritten against the current tree; the earlier
> blocked-state narrative is kept only where it explains why a removal was made.

## The handed-back deficiency

*Remove the desktop-only icon synchronization path and the production code used solely by it, including
`DesktopIconAdoption`, while preserving the shared browser icon delivery components; retire
`DesktopIconAdoptionTest`; reconcile current architecture documentation and update the QA plan.*

The evaluator's primary deficiency was that "production source still contains the desktop-only icon
download path after its JavaFX caller was removed". This reverses the earlier decision, recorded as
finding F002, to keep `IconSync.syncItemIconsToDisk` on the grounds that it belonged to an
ADR-documented subsystem. It is now removed.

Two of the four listed items were not writable by this role and were reported instead of done: the test
retirement and the QA plan update. CLAUDE.md (*Testing*) forbids this role from creating, editing or
deleting a test or fixture, and `agent/runtime/support/config.py` assigns `agent/qa-plans/` to QA and the
harness. The test retirement has since been carried out outside this role and is verified below; the QA
plan correction remains reported rather than written, for the same ownership reason.

## Removed

| Removed | Why it is desktop-exclusive |
| --- | --- |
| `sync.IconSync.syncItemIconsToDisk(Path)` | The bulk desktop download: selected every item with `icon_url` and no `icon_path`, downloaded the binary and recorded the local file in `items.icon_path`. Its last caller went with `InitialSetupService`/`IconSyncGateway`; nothing in `src/main` invoked it. |
| `IconSync.IconUpdate`, `IconSync.flushIconPathUpdates`, `IconSync.ICON_FLUSH_BATCH` | Private supporting code of that method only. `flushIconPathUpdates` held the tree's only `UPDATE items SET icon_path` statement. |
| `src/main/java/sync/DesktopIconAdoption.java` | The per-item legacy-file adoption step, byte-comparing `items/{itemId}.png` against a freshly fetched canonical image. `syncItemIconsToDisk` was its only production consumer; its `LEGACY_ITEMS_DIRECTORY` constant and `Outcome`/`Adoption` types had no other reader. |
| `infra.icons.FilesystemIconStore.assetsRoot()` | A public accessor whose only remaining caller was the removed method's completion message. The field it exposed stays; the class is retained shared browser infrastructure. |

## Preserved — the shared browser icon delivery components

Each of the fourteen `infra.icons`/`application.icons` types was checked individually for a consumer
other than the deleted desktop path, and every one has one:

- `application.icons.IconDelivery`, `application.icons.ItemIconUrls` → `web.ItemIconApiController`, the
  browser's item-image route.
- `infra.icons.FilesystemIconStore`, `IconStore`, `IconAcquisition`, `IconAcquisitionResult`,
  `HttpIconImageFetcher`, `IconImageFetcher`, `IconFetchResult`, `IconSourcePolicy`, `IconImageBytes`,
  `IconCacheBounds`, `IconStorageException`, `IconSource`, `StoredIcon` → `IconDelivery` and each other,
  wired in `web.Gw2ApiApplication` to `repo.AppConfig.ICON_CACHE_DIR`.
- The icon-*metadata* half of `IconSync` is retained in full — `syncItemIconUrls`,
  `syncItemMetadataByIds`, `repairItemIconMetadata`, `applyItemMetadata`, `referencedOrUnknownItemIds`
  and the standalone `main` with `--dry-run` — with `sync.GlobalDataRefreshGateway` and
  `application.ItemReadService` as its consumers.

`StoredIcon.file` was deliberately kept rather than narrowed: `StoredIcon` and `FilesystemIconStore` are
shared components the deficiency requires preserving, and QA's `IconDeliveryTest` and
`FilesystemIconStoreTest` construct the record, so changing its shape would break retained coverage for
no production gain. Its javadoc, which described the component as existing "for the desktop path only",
now describes what it actually is.

## End-to-end behavior after the removal

No production code writes `items.icon_path` any more. The column is still selected by
`repo.ItemRepository`, `BankRepository` and `MaterialStorageRepository`, which by design keep it out of
every browser response (`STORY-API-009`, asserted by the bank and material controller tests); it will
simply stay null. Browser icons never consulted it: a request resolves through `IconDelivery` to the
source-keyed `assets-v1` entry and repairs a miss on demand against upstream. So the removed path had no
reachable caller and no browser-visible effect, which is exactly what `ItemIconApiControllerTest` and
`IconDeliveryTest` still demonstrate. Legacy `items/{itemId}.png` files left on disk by earlier runs are
now read by nothing.

## Verification (2026-10-24, current tree — supersedes the blocked-state evidence)

`JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root. Every command below was
re-run in this invocation against the tree as it now stands, after the retirement of
`src/test/java/sync/DesktopIconAdoptionTest.java`.

**Test compilation — now succeeds.** A plain `./mvnw -o test-compile` answered
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

All 120 remaining test sources compile against the removed production classes. The 20 errors in
`DesktopIconAdoptionTest` previously recorded here no longer occur: the file is absent
(`git status` shows ` D src/test/java/sync/DesktopIconAdoptionTest.java`). Surefire and the CI gate are
unblocked.

**QA-required checks and the relevant retained suites — all pass.** Run through Surefire, which the
earlier invocations could not reach:

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
classes are listed in Surefire's own order. This covers every suite the QA plan's `test_specifications`
name — both `interface_contract` entries (`LegacyJavaFxRemovalTest`, `Gw2ApiApplicationBootTest`),
`CraftingProfitApiControllerTest`, `CraftingDiscoveryApiControllerTest`, `GlobalSyncApiControllerTest`,
`AccountAccessContractTest`, `ItemReadApiControllerTest`, `PriceRefreshApiControllerTest`,
`ItemReadServiceTest` and the three trading-post refresh tests — plus the browser icon-delivery coverage
affected by the last production removal. The `ERROR`-level lines in the log
(`ApiExceptionHandler: Crafting calculation failed`, `BackgroundTaskService: … failed`) are the
negative-path logging of passing tests, not failures.

- `grep -rln "DesktopIconAdoption\|syncItemIconsToDisk\|IconSyncGateway\|EctoSalvageService\|
  EctoSalvageCalculator\|InitialSetupService\|CoinUtils\|assetsRoot()" src/main/java src/test/java` →
  no match. Nothing in the repository references a class this story removed.
- `src/test/java/web/ItemIconApiRealDbIT.java` and the other `*IT` real-database checks were **not**
  run: they need a real PostgreSQL database (`TEST_STRATEGY.md` §34), which this environment does not
  provide. No claim is made from them. The earlier `*IT` errors recorded as F007 are unrelated to this
  story.
- No frontend command was run: no frontend file was changed.
- `src/test/java/web/LegacyJavaFxRemovalTest.java` still hashes to
  `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3`, the plan's protected hash.

### Earlier, blocked-state verification (historical)

Before the retirement, `./mvnw -o test-compile` failed with 20 errors in `DesktopIconAdoptionTest` alone,
and the same checks were therefore compiled and run directly with `javac` + `ConsoleLauncher` against a
classpath with the 13 `javafx-*`/`testfx-*` jars filtered out (108 tests, 0 failures). That workaround is
no longer needed and its result is superseded by the Surefire run above; it is retained here only to
explain why the earlier invocations reported no Maven test result.

## Documentation reconciled

- `docs/CURRENT_ARCHITECTURE.md` §9 — a new paragraph records the removal of `syncItemIconsToDisk` and
  `DesktopIconAdoption`, states that no production path writes `items.icon_path` while the three
  repositories still read it and keep it out of browser responses, and names the shared browser icon
  components the removal preserved plus the retained metadata half and its consumers.
- `docs/KNOWN_PROBLEMS.md` KP-22 — the stale-path half was the entry's only remaining open part and was a
  property of `syncItemIconsToDisk`'s selection; it is recorded as resolved by removal rather than by
  repair, and the entry's status moves from "partially resolved — the metadata half only" to "resolved".
  The metadata and browser-rendering text is unchanged.
- `docs/KNOWN_PROBLEMS.md` KP-25 — the removed download is added to the sentence listing which orphans
  `STORY-UI-004` removed, so it is not counted as remaining orphaned production code.

`docs/CURRENT_STATE_SPEC.md` needed no change: it describes no icon behavior. `docs/TARGET_ARCHITECTURE.md`
§12.1 is the target-state owner and still describes the decided icon delivery, which is unchanged. No
other document was written.

## Blocker — resolved

`src/test/java/sync/DesktopIconAdoptionTest.java`, the only test of the deleted `sync.DesktopIconAdoption`
and the sole cause of the `default-testCompile` failure, has been retired outside this role (the deletion
is in the working tree, unstaged). Nothing in `src/` references it or its subject, and the clean
`test-compile` above succeeds. The behavior one layer down that it used to touch keeps its coverage:
`FilesystemIconStoreTest`/`IconAcquisitionTest` for the keyed store and publication protocol,
`IconSourcePolicyTest` for source acceptance, `IconDeliveryTest` for on-demand miss repair — all passing
in the run above. The only behavior that disappeared with it is legacy `items/{itemId}.png` byte-equality
adoption, whose production subject is gone. There is no remaining blocker.

## QA plan — reconciliation with the current tree

Reported, not written: `agent/qa-plans/` is assigned to QA and the harness in
`config.DOCUMENT_OWNERSHIP`, and `agent/runtime/qa/qa_agent.py` is its only writer, so this role records
the corrections rather than applying them. Each item below names a plan field that is stale against the
tree as of 2026-10-24 and the fact that replaces it:

- `removed_test_paths` (31 entries) and `existing_tests_reviewed` — neither mentions
  `src/test/java/sync/DesktopIconAdoptionTest.java`. It is now **retired**, because its production
  subject `sync.DesktopIconAdoption` was removed; it should join the retirement list, bringing the
  retired desktop total to 32 files plus the 6 of the previous cycle. Its retirement is why the plan's
  `test_specifications` line "Run Maven test compilation to establish that retained test sources compile
  against the removed production classes" can now be answered.
- `pre_implementation_verification.status: "NOT_RUN"` with its `java.security` `AccessDeniedException`
  and missing-PowerShell `failure_evidence` — **superseded**. That environment failure does not
  reproduce here, and the command it names now has a real result:
  `./mvnw -o clean test-compile` → `BUILD SUCCESS`, 222 production and 120 test sources compiled.
- `test_repair.review.reason` — "the retained Maven test sources do not compile against the removed
  production classes, so the test suite is not yet reviewable as a whole" — **no longer true**. Test
  compilation succeeds; the suite is reviewable as a whole.
- `test_repair.review.evidence[2]` — "Those runs do not establish that Maven test compilation or the
  complete suite passes" — **answered**. Maven test compilation passes, and the QA-named suites pass
  through Surefire (210 tests, 0 failures). The complete suite is still the CI gate's job after
  publication; no claim is made here beyond the suites listed.
- `coverage_review` — "No current JaCoCo report was available for this repair" — still accurate as a
  statement about this invocation. JaCoCo's agent is now configured on the runs above, but no report was
  generated and **no coverage figure is claimed**. Coverage assessment remains QA's.
- Retained browser icon coverage worth recording for AC2/AC3, not currently listed:
  `web.ItemIconApiControllerTest`, `application.icons.IconDeliveryTest`,
  `application.icons.ItemIconUrlsTest`, `infra.icons.FilesystemIconStoreTest`, `IconAcquisitionTest`,
  `HttpIconImageFetcherTest`, `IconSourcePolicyTest`, `IconImageBytesTest`, and
  `sync.IconSyncMetadataTest` for the retained metadata half. `web.ItemIconApiRealDbIT` stays a
  real-database layer this environment cannot run.
- `protected_test_hashes` — unchanged and still correct:
  `src/test/java/web/LegacyJavaFxRemovalTest.java` hashes to the recorded
  `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3`.

## Interface Deviations

None. Both names in the plan's `interface_contract` exist and were executed verbatim:
`web.LegacyJavaFxRemovalTest.productionClasspathDoesNotContainTheLegacyJavaFxApplicationEntryPoint()`
and
`web.Gw2ApiApplicationBootTest.applicationStartsAndExposesTheCraftingControllersOverAnEmbeddedServer()`.

## Working tree

The tree carries the maintainer's concurrent work for other stories; it was left exactly as found, and
every command above ran against it.

Earlier invocations changed `src/main/java/sync/IconSync.java`,
`src/main/java/infra/icons/FilesystemIconStore.java`, `src/main/java/infra/icons/StoredIcon.java`, the
deletion of `src/main/java/sync/DesktopIconAdoption.java`, `docs/CURRENT_ARCHITECTURE.md` and
`docs/KNOWN_PROBLEMS.md`, all of which remain as they were.

**This invocation changed no source or documentation file.** Its scope was the two handed-back
reconciliation deficiencies, so it wrote only this file and the story's Result. The documents this role
owns were re-checked and needed no edit: `docs/CURRENT_ARCHITECTURE.md`, `docs/CURRENT_STATE_SPEC.md` and
`docs/KNOWN_PROBLEMS.md` contain no statement about test compilation, `DesktopIconAdoptionTest` or the
blocked build state (`grep -rn "test-compile\|DesktopIconAdoption\|Surefire\|mvnw"` over the three finds
only the generic "run backend tests with `./mvnw test`" guidance and the already-correct §9 removal
paragraph), so no stale claim had leaked out of the Result into an owned document this time.

No process was left running: the only long-lived one was the Spring context inside
`Gw2ApiApplicationBootTest`, which logged its own graceful shutdown at the end of the Surefire run.

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
sources). None of them is referenced from Java since the maintainer deleted `logo.png`. They are brand
assets rather than desktop UI, which is why this story kept them, but the browser serves its own
`frontend/public/favicon.ico` and nothing reads them from the backend.

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
that again needed a retirement of 1 file, and finally this record-reconciliation pass whose only work was
re-running the commands the previous cycle could not. The coding role can only hand over a file list;
`cycle.py`'s repair channels are shaped for name alignment (`interface_deviation`) or evaluation-driven
revision. A deletion-shaped handover — "these production symbols are gone, here are the test files that
reference them" — emitted at the moment the production change is made would collapse the alternation.

F006 (closed 2026-10-24): `src/test/java/sync/DesktopIconAdoptionTest.java` has been retired outside this
role. Nothing in `src/` references it or its deleted subject, and `./mvnw -o clean test-compile` →
`BUILD SUCCESS`. No planner disposition is needed.

F007: `web.ItemIconApiRealDbIT` (local real-database check) errors in two of three tests because
`BankContentsService.getBankContents` reads `account_bank` without an account scope, which the
STORY-INFRA-004 schema gate rejects. This is unrelated to this story and belongs to the account-scoped
repository work (STORY-APP-015). It was not re-run in this invocation: no real PostgreSQL database is
available here (`TEST_STRATEGY.md` §34).

## User Resolution

Routed to QA repair: the only open item is QA's own plan reconciliation, which the coder cannot perform.

## Resolution Notes

Harness fix 459b448 now routes QA-only RETRY items to QA repair automatically.
