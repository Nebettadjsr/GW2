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

Outcome: **blocked on one QA test retirement.** The production side is complete: with the desktop icon
download removed, `src/main/java` contains no JavaFX-exclusive production path at all. Documentation is
reconciled, and the QA-required checks plus the full retained browser icon-delivery coverage pass against
the tree as it now stands. What is not done, and cannot be done by this role, is retiring
`src/test/java/sync/DesktopIconAdoptionTest.java`, the sole test of the removed
`sync.DesktopIconAdoption`. While it is present, `./mvnw -o test-compile` fails on that file alone, so
Surefire — and the CI gate — cannot run. No test file was created, modified or deleted by this role.

## The handed-back deficiency

*Remove the desktop-only icon synchronization path and the production code used solely by it, including
`DesktopIconAdoption`, while preserving the shared browser icon delivery components; retire
`DesktopIconAdoptionTest`; reconcile current architecture documentation and update the QA plan.*

The evaluator's primary deficiency was that "production source still contains the desktop-only icon
download path after its JavaFX caller was removed". This reverses the earlier decision, recorded as
finding F002, to keep `IconSync.syncItemIconsToDisk` on the grounds that it belonged to an
ADR-documented subsystem. It is now removed.

Two of the four listed items are not writable by this role and are reported instead of done: the test
retirement and the QA plan update. CLAUDE.md (*Testing*) forbids this role from creating, editing or
deleting a test or fixture, and `agent/runtime/support/config.py` assigns `agent/qa-plans/` to QA and the
harness. Both are handed over with full evidence below.

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

## Verification

`JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root.

- `./mvnw -o -q clean compile` → exit 0.
- `./mvnw -o test-compile` → **exit 1**, `BUILD FAILURE` in `default-testCompile`. Every error is in
  `src/test/java/sync/DesktopIconAdoptionTest.java`: 20 errors, `Symbol nicht gefunden: Klasse
  DesktopIconAdoption` / `Package DesktopIconAdoption ist nicht vorhanden`, at lines 58, 59, 70, 72, 87,
  89, 99, 105, 107, 122, 124, 137, 139, 152, 154, 166, 169, 179, 181, 186. It is the only failing file;
  the other 120 test sources compile. This is the blocker, not a production defect.
- `grep -rn "DesktopIconAdoption\|syncItemIconsToDisk\|assetsRoot()" src/main/java frontend/src` → no
  match. That test file is the only remaining reference anywhere in the repository.
- QA-required checks and retained browser icon coverage, compiled and run directly because the broken
  test file blocks Surefire — the path `tasks/lessons.md` documents:

  `javac -cp "target\classes;<test classpath>" -sourcepath src/test/java -d <tmp outside the repo>`
  over `web/LegacyJavaFxRemovalTest.java`, `web/Gw2ApiApplicationBootTest.java`,
  `application/icons/IconDeliveryTest.java`, `application/icons/ItemIconUrlsTest.java`,
  `infra/icons/FilesystemIconStoreTest.java`, `infra/icons/IconAcquisitionTest.java`,
  `infra/icons/HttpIconImageFetcherTest.java`, `infra/icons/IconSourcePolicyTest.java`,
  `infra/icons/IconImageBytesTest.java`, `web/ItemIconApiControllerTest.java` and
  `sync/IconSyncMetadataTest.java` → exit 0, producing 18 class files and reading no other test source
  (`DesktopIconAdoptionTest` is never pulled in; implicit compilation reads only what the named sources
  reference).

  `java -cp "<tmp>;src\test\resources;target\classes;<classpath>;junit-platform-launcher;
  junit-platform-console;junit-platform-reporting" org.junit.platform.console.ConsoleLauncher execute
  --disable-ansi-colors --details=tree --select-class=…` over the same eleven classes → exit 0:

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
  `javafx-*`/`testfx-*` jars filtered out (95 entries → 82), so `LegacyJavaFxRemovalTest` ran against a
  classpath that genuinely lacks JavaFX — stronger evidence than Surefire's would have been.
  `ItemIconApiControllerTest`, `IconDeliveryTest`, `FilesystemIconStoreTest`, `IconAcquisitionTest` and
  `IconSyncMetadataTest` are the retained browser icon-delivery and icon-metadata coverage; the removal
  left all of it passing.
- `src/test/java/web/ItemIconApiRealDbIT.java` was **not** run: it needs a real PostgreSQL database
  (`TEST_STRATEGY.md` §34), which this environment does not provide. No claim is made from it.
- No frontend command was run: no frontend file was changed.
- `src/test/java/web/LegacyJavaFxRemovalTest.java` still hashes to
  `d9bc33e33e126c431a223f177b32452d5cb5c8d5a515322a5b038a7b88cc9ae3`, the plan's protected hash.

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

## Blocker — one QA-owned test file

`src/test/java/sync/DesktopIconAdoptionTest.java` (20 errors, quoted above) is the only test of
`sync.DesktopIconAdoption`. Its production subject no longer exists, so it cannot be repaired, only
retired — which is what the handed-back deficiency itself asks for. The retained behavior it used to
cover sits one layer down and is already covered by the tests that pass above:
`FilesystemIconStoreTest`/`IconAcquisitionTest` for the keyed store and publication protocol,
`IconSourcePolicyTest` for source acceptance, `IconDeliveryTest` for on-demand miss repair. The only
behavior that disappears with it is legacy `items/{itemId}.png` byte-equality adoption, whose production
subject is gone. Retiring it needs no replacement test from this role's point of view, but that
judgement is QA's to make.

## QA plan — the update the deficiency asks for

(Reported, not written: `agent/qa-plans/` is QA/harness-owned and `cycle.py` restores it byte-for-byte if
this role edits it.)

- `existing_tests_reviewed` / `removed_test_paths` should gain
  `src/test/java/sync/DesktopIconAdoptionTest.java` as retired because its production subject was
  removed, bringing the retired desktop total to 32 files + the 6 of the previous cycle.
- Retained browser icon coverage to record for AC2/AC3: `web.ItemIconApiControllerTest`,
  `application.icons.IconDeliveryTest`, `application.icons.ItemIconUrlsTest`,
  `infra.icons.FilesystemIconStoreTest`, `IconAcquisitionTest`, `HttpIconImageFetcherTest`,
  `IconSourcePolicyTest`, `IconImageBytesTest`, and `sync.IconSyncMetadataTest` for the retained metadata
  half. `web.ItemIconApiRealDbIT` remains a real-database layer the gate cannot run here.
- `pre_implementation_verification.status: NOT_RUN` — its `java.security` `AccessDeniedException` and
  missing-PowerShell evidence does not reproduce here. The command fails today for a different and real
  reason: the one test file above breaks `default-testCompile`.
- `coverage_review` — still no JaCoCo report, and none can be produced until `test-compile` succeeds. No
  coverage figure is claimed.
- Available verification results: the 108-test run and the two compile results quoted above.

## Interface Deviations

None. Both names in the plan's `interface_contract` exist and were executed verbatim:
`web.LegacyJavaFxRemovalTest.productionClasspathDoesNotContainTheLegacyJavaFxApplicationEntryPoint()`
and
`web.Gw2ApiApplicationBootTest.applicationStartsAndExposesTheCraftingControllersOverAnEmbeddedServer()`.

## Working tree

The tree carries the maintainer's concurrent work for other stories; it was left exactly as found, and
every command above ran against it. This invocation changed only: `src/main/java/sync/IconSync.java`,
`src/main/java/infra/icons/FilesystemIconStore.java`, `src/main/java/infra/icons/StoredIcon.java`, the
deletion of `src/main/java/sync/DesktopIconAdoption.java`, `docs/CURRENT_ARCHITECTURE.md`,
`docs/KNOWN_PROBLEMS.md`, this file, the story file and `tasks/lessons.md`. No process was left running:
the only long-lived one was the Spring context inside `Gw2ApiApplicationBootTest`, which logged its own
graceful shutdown at the end of the run.

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

F005: A production removal and the test change it forces cannot land in one cycle. This story has now
spent five cycles alternating between the two roles — production deletion, QA retirement of 31 files,
documentation reconciliation, a second production deletion with QA retirement of 6 files, and now a
third production deletion that again needs QA for 1 file. The coding role can only hand over a file
list; `cycle.py`'s repair channels are shaped for name alignment (`interface_deviation`) or
evaluation-driven revision. A deletion-shaped handover — "these production symbols are gone, here are
the test files that reference them" — emitted at the moment the production change is made would collapse
the alternation.

F006: `src/test/java/sync/DesktopIconAdoptionTest.java` is the one file of this kind left, and it is
mechanically identifiable: it is the sole referent of a deleted production class and `./mvnw -o
test-compile` names it exhaustively. Nothing outside the QA test process may act on it, which is why it
appears here as well as under Blocker.

## User Resolution

Maintainer deleted src/test/java/sync/DesktopIconAdoptionTest.java (subject deleted) and verified test-compile plus the retained icon tests.

## Resolution Notes

The last evaluation's only open item was test-only work routed to the coder as RETRY. Evidence is in the story Result under 'Maintainer follow-up 2'. The story goes straight back to evaluation.
