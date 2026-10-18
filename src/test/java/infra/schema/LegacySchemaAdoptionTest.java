package infra.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criteria 3, 4, 5, 6 and 7: the explicit legacy adoption against real
 * isolated PostgreSQL.
 *
 * <p>Every check builds a legacy schema from the checked-in fixtures in its own disposable schema,
 * runs the production adoption path and reconciles source, archive, ledger and live contents.
 * Expected values come from the fixtures and TARGET_ARCHITECTURE.md §7.4, never from the
 * implementation's own output.
 */
class LegacySchemaAdoptionTest {

    private static final String PROVEN_GUID = "11111111-2222-3333-4444-555555555555";

    /** The GUID two legacy Luck rows claim, so the archive quarantines it instead of owning it. */
    private static final String QUARANTINED_GUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private LegacySchemaAdoption.Request request(DisposableSchema db) {
        return new LegacySchemaAdoption.Request(db.url(), db.user(), db.password(), db.name(),
                db.archiveName(), "pg_dump gw2 2026-10-17T18:00Z -> /backups/gw2-pre-cutover.dump",
                "maintainer confirmed backend, JavaFX and schedulers stopped", true);
    }

    @Test
    void adoptionPreservesEveryLegacyAccountRecordAndAssociatesOnlyProvenLuckOwnership() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_full")) {
            db.applyLegacy("optional_material_catalog.sql", "optional_account_materials_sync.sql",
                    "optional_account_luck.sql", "optional_account_sync_state.sql");
            db.apply("seed_material_catalog.sql");
            db.apply("seed_account_materials_sync.sql");
            db.apply("seed_account_luck.sql");
            db.apply("seed_account_sync_state.sql");

            LegacySchemaAdoption.Outcome outcome = LegacySchemaAdoption.adopt(request(db));

            // ---- the import manifest describes the real source inventory
            assertEquals(LegacySchemaFingerprint.FINGERPRINT_ID, outcome.fingerprintId());
            assertEquals(List.of("material_categories", "material_category_items",
                            "account_materials_sync", "account_luck", "account_sync_state"),
                    outcome.variants(), "every optional object present must be recorded as a variant");
            assertEquals(Map.of("account_bank", 3L, "account_materials", 2L, "account_recipes", 1L,
                            "characters", 2L, "character_crafting", 1L, "character_recipes", 1L,
                            "character_items", 3L, "account_materials_sync", 1L, "account_luck", 4L,
                            "account_sync_state", 5L),
                    outcome.manifest().stream().collect(java.util.stream.Collectors.toMap(
                            LegacySchemaAdoption.ManifestEntry::object,
                            LegacySchemaAdoption.ManifestEntry::rowCount)),
                    "the manifest must account for every legacy account row");
            var inventory = outcome.manifest().stream().filter(e -> e.object().equals("character_items"))
                    .findFirst().orElseThrow();
            assertEquals(2L, inventory.ambiguousRows(),
                    "the two Equipped rows whose slot parts are all NULL cannot be distinguished");
            assertEquals("2026-02-04T13:00:00Z", inventory.oldestFetchedAt().toString(),
                    "original timestamps must be retained in the manifest");
            assertEquals(1L, outcome.manifest().stream().filter(e -> e.object().equals("account_luck"))
                            .findFirst().orElseThrow().invalidRows(),
                    "the malformed identity must be captured, not dropped or corrected");

            // ---- the archive holds the original rows, relations and timestamps
            assertEquals(3, db.count(db.archiveName() + ".account_bank"));
            assertEquals(4, db.count(db.archiveName() + ".account_luck"));
            assertEquals(5, db.count(db.archiveName() + ".account_sync_state"));
            assertEquals(List.of("Legacy Hero|2026-02-04 13:00:00+00", "Legacy Sidekick|2026-02-04 13:00:00+00"),
                    db.rows("SELECT name || '|' || fetched_at FROM " + db.archiveName()
                            + ".characters ORDER BY name"));
            assertEquals(List.of("3"), db.rows("""
                            SELECT count(*)::text FROM %s.character_items child
                            JOIN %s.characters parent ON parent.character_id = child.character_id
                            """.formatted(db.archiveName(), db.archiveName())),
                    "the original parent/child relation must still resolve inside the archive");
            assertEquals(List.of(db.archiveName() + ".characters_character_id_seq"), db.rows("""
                            SELECT n.nspname || '.' || c.relname
                            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname IN (current_schema(), '%s') AND c.relkind = 'S'
                              AND c.relname LIKE 'characters%%'
                            """.formatted(db.archiveName())),
                    "the roster's owned sequence must move with it, leaving nothing legacy behind");

            // ---- global data stays shared, in place and intact
            assertEquals(3, db.count("items"));
            assertEquals(1, db.count("recipes"));
            assertEquals(1, db.count("recipe_ingredients"));
            assertEquals(1, db.count("tp_prices"));
            assertEquals(1, db.count("material_categories"));
            assertEquals(2, db.count("material_category_items"));

            // ---- only identity-aware Luck is associated; everything else stays unresolved
            assertEquals(List.of(PROVEN_GUID), db.rows("SELECT account_id::text FROM gw2_account"));
            assertEquals(List.of("1234567|2026-02-05 14:00:00+00"), db.rows(
                            "SELECT consumed_luck || '|' || fetched_at FROM account_luck_total"),
                    "the proven Luck row keeps its original value and fetch time");
            assertEquals(0, db.count("account_source_state"),
                    "an unscoped legacy marker is not completion evidence for any account");
            for (String table : List.of("account_bank_slot", "account_material_stack", "account_recipe_unlock",
                    "account_character", "account_character_discipline", "account_character_recipe",
                    "account_character_item")) {
                assertEquals(0, db.count(table), table + " must stay empty: its ownership is unproven");
            }
            assertTrue(db.scalarBoolean("SELECT bool_and(last_seen_at IS NULL "
                            + "AND last_successful_sync_at IS NULL) FROM gw2_account"),
                    "no activity history may be fabricated for an adopted account");

            // ---- the provenance ledger says so explicitly
            assertEquals(List.of(
                            "ACCOUNT_RECIPES|UNRESOLVED|null|1",
                            "BANK|UNRESOLVED|null|3",
                            "CHARACTERS|UNRESOLVED|null|7",
                            "LEGACY_MATERIAL_SYNC_SINGLETON|UNRESOLVED|null|1",
                            "LEGACY_SOURCE_MARKERS|UNRESOLVED|null|5",
                            "LUCK|ASSOCIATED|" + PROVEN_GUID + "|1",
                            "LUCK|QUARANTINED|null|3",
                            "MATERIALS|UNRESOLVED|null|2"),
                    db.rows("SELECT source || '|' || outcome || '|' || coalesce(account_id::text, 'null')"
                            + " || '|' || row_count FROM " + db.archiveName()
                            + ".provenance_ledger ORDER BY source, outcome"),
                    "one malformed and two conflicting Luck rows are quarantined; no other source is owned");
            assertEquals(List.of("ACCOUNT_RECIPES", "BANK", "CHARACTERS", "LEGACY_MATERIAL_SYNC_SINGLETON",
                            "LEGACY_SOURCE_MARKERS", "LUCK", "MATERIALS"),
                    outcome.unresolvedSources().stream().sorted().toList());

            // ---- the live schema no longer carries the unscoped legacy account objects
            Map<String, String> live = db.liveSchemaShape();
            for (String legacy : List.of("account_bank", "account_materials", "account_recipes", "characters",
                    "character_crafting", "character_recipes", "character_items", "account_luck",
                    "account_sync_state", "account_materials_sync")) {
                assertFalse(live.containsKey(legacy),
                        legacy + " must not remain in the live schema: " + live.keySet());
            }
            assertFalse(outcome.reconciliation().isEmpty(), "reconciliation must be reported");
            assertNotNull(outcome.importId());

            // ---- and the operator-visible report states all of it
            String report = outcome.report();
            assertTrue(report.contains("character_items: 3 rows"), report);
            assertTrue(report.contains("2 indistinguishable-slot rows"), report);
            assertTrue(report.contains("account_luck: 4 rows"), report);
            assertTrue(report.contains("1 invalid/orphaned rows"), report);
            assertTrue(report.contains("LUCK: ASSOCIATED -> " + PROVEN_GUID), report);
            assertTrue(report.contains("CHARACTERS: UNRESOLVED"), report);
            assertTrue(report.contains("no unassociated legacy record is reachable"), report);
        }
    }

    @Test
    void archivedAccountDataIsNotReachableThroughRuntimeDatabaseAccess() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_isolate")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");
            LegacySchemaAdoption.adopt(request(db));

            try (var runtime = db.openAnotherConnection()) {
                // The runtime search path is the live schema only, and the runtime uses unqualified
                // names, so an unscoped legacy statement cannot resolve at all.
                try (var st = runtime.createStatement()) {
                    var error = assertThrows(java.sql.SQLException.class,
                            () -> st.executeQuery("SELECT count(*) FROM account_bank"));
                    assertTrue(error.getMessage().contains("account_bank"), error.getMessage());
                }
                try (var ps = runtime.prepareStatement("SELECT current_schemas(false)::text")) {
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        assertFalse(rs.getString(1).contains(db.archiveName()),
                                "the archive must be outside every runtime search path: " + rs.getString(1));
                    }
                }
            }

            // Grant side of the same boundary: the archive schema grants PUBLIC nothing, so no role
            // other than the migration/archive owner has a way in. A PUBLIC grant renders in the
            // schema ACL with an empty grantee.
            assertEquals(0, db.scalarLong("""
                            SELECT count(*) FROM pg_namespace n,
                                 unnest(coalesce(n.nspacl, '{}'::aclitem[])) acl
                            WHERE n.nspname = '%s' AND acl::text LIKE '=%%'
                            """.formatted(db.archiveName())),
                    "the archive schema must grant PUBLIC no privilege");

            // Migration/archive access can still reconcile the preserved rows.
            assertEquals(4, db.count(db.archiveName() + ".account_luck"));
        }
    }

    @Test
    void anEmptyAndAnAdoptedDatabaseConvergeOnTheSameLiveSchema() throws Exception {
        try (DisposableSchema fresh = new DisposableSchema("conv_fresh");
             DisposableSchema adopted = new DisposableSchema("conv_adopted")) {
            SchemaGate.run(fresh.url(), fresh.user(), fresh.password(), fresh.name());

            // The variant with no optional objects at all, so adoption has to normalize the missing
            // global material catalog explicitly before it can baseline at V1.
            adopted.applyLegacy();
            LegacySchemaAdoption.Outcome outcome = LegacySchemaAdoption.adopt(request(adopted));

            assertEquals(List.of(), outcome.variants());
            assertEquals(List.of("material_categories", "material_category_items", "account_materials_sync",
                            "account_luck", "account_sync_state"),
                    outcome.absentOptionalObjects(),
                    "every known optional object absent from this legacy database must be recorded");
            assertEquals(fresh.liveSchemaShape(), adopted.liveSchemaShape(),
                    "a fresh installation and an adopted legacy database must reach one live schema");
            assertEquals(7, outcome.manifest().size(),
                    "only the account objects this legacy database actually had are archived");
        }
    }

    @Test
    void unknownDriftBlocksAdoptionWithDiagnosticsAndChangesNothing() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_drift")) {
            db.applyLegacy("optional_account_luck.sql");
            db.execute("ALTER TABLE account_bank ADD COLUMN local_note TEXT");
            db.execute("CREATE TABLE operator_scratch (note TEXT)");
            Map<String, String> before = db.liveSchemaShape();
            long bankRows = db.count("account_bank");

            LegacyAdoptionBlockedException blocked = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db)));

            assertTrue(blocked.reasons().stream().anyMatch(reason -> reason.contains("account_bank columns")),
                    "the extra column must be named: " + blocked.reasons());
            assertTrue(blocked.reasons().stream().anyMatch(reason -> reason.contains("operator_scratch")),
                    "the unknown relation must be named: " + blocked.reasons());
            assertEquals(before, db.liveSchemaShape(), "a refused preflight must not change the schema");
            assertEquals(bankRows, db.count("account_bank"), "a refused preflight must not touch data");
            assertFalse(db.scalarBoolean("SELECT EXISTS (SELECT 1 FROM information_schema.schemata"
                            + " WHERE schema_name = '" + db.archiveName() + "')"),
                    "no archive may be created for a refused adoption");
            assertFalse(before.containsKey(SchemaGate.HISTORY_TABLE));
            assertFalse(db.liveSchemaShape().containsKey(SchemaGate.HISTORY_TABLE),
                    "no baseline may be recorded automatically");
        }
    }

    @Test
    void adoptionIsRefusedWithoutStoppedWritersABackupOrAnExplicitConfirmation() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_preconditions")) {
            db.applyLegacy();
            Map<String, String> before = db.liveSchemaShape();

            var noBackup = new LegacySchemaAdoption.Request(db.url(), db.user(), db.password(), db.name(),
                    db.archiveName(), "  ", "maintainer", true);
            assertTrue(assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(noBackup)).reasons().stream()
                    .anyMatch(reason -> reason.contains("backup")));

            var writersRunning = new LegacySchemaAdoption.Request(db.url(), db.user(), db.password(), db.name(),
                    db.archiveName(), "/backups/gw2.dump", null, true);
            assertTrue(assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(writersRunning)).reasons().stream()
                    .anyMatch(reason -> reason.contains("stopped")));

            var unconfirmed = new LegacySchemaAdoption.Request(db.url(), db.user(), db.password(), db.name(),
                    db.archiveName(), "/backups/gw2.dump", "maintainer", false);
            assertTrue(assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(unconfirmed)).reasons().stream()
                    .anyMatch(reason -> reason.contains("confirmation")));

            assertEquals(before, db.liveSchemaShape(), "a refused precondition must write nothing");
        }
    }

    @Test
    void anUnexpectedArchiveSchemaNameIsRefusedBeforeItReachesAnyStatement() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_badname")) {
            db.applyLegacy();
            var hostile = new LegacySchemaAdoption.Request(db.url(), db.user(), db.password(), db.name(),
                    "archive\"; DROP SCHEMA public CASCADE; --", "/backups/gw2.dump", "maintainer", true);

            SchemaGateException refused = assertThrows(SchemaGateException.class,
                    () -> LegacySchemaAdoption.adopt(hostile));

            assertTrue(refused.getMessage().contains("unexpected identifier")
                            || (refused.getCause() != null
                                && refused.getCause().getMessage().contains("unexpected identifier")),
                    "an identifier this operation composes into DDL must be validated: "
                            + refused.getMessage());
            assertTrue(db.liveSchemaShape().containsKey("account_bank"), "nothing may have been dropped");
        }
    }

    @Test
    void anEmptyOrAlreadyManagedDatabaseIsRefusedRatherThanBaselined() throws Exception {
        try (DisposableSchema empty = new DisposableSchema("adopt_empty")) {
            assertTrue(assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(empty))).reasons().stream()
                    .anyMatch(reason -> reason.contains("empty")));

            SchemaGate.run(empty.url(), empty.user(), empty.password(), empty.name());
            assertTrue(assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(empty))).reasons().stream()
                    .anyMatch(reason -> reason.contains("managed")));
        }
    }

    @Test
    void aFailureAfterPreservationWritesRollsBackCompletelyAndTheRetrySucceedsOnce() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_retry")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");
            Map<String, String> before = db.liveSchemaShape();
            String bankDigest = db.scalarText("SELECT md5(string_agg(t::text, ',' ORDER BY t::text))"
                    + " FROM account_bank t");

            SchemaGateException failed = assertThrows(SchemaGateException.class,
                    () -> LegacySchemaAdoption.adopt(request(db), () -> {
                        throw new IllegalStateException("injected failure after preservation writes");
                    }));
            assertTrue(failed.getMessage().contains("rolled back"), failed.getMessage());

            assertEquals(before, db.liveSchemaShape(),
                    "the failing step's DDL and data effects must roll back entirely");
            assertEquals(bankDigest, db.scalarText("SELECT md5(string_agg(t::text, ',' ORDER BY t::text))"
                    + " FROM account_bank t"), "the original dataset must remain reconcilable");
            assertFalse(db.scalarBoolean("SELECT EXISTS (SELECT 1 FROM information_schema.schemata"
                    + " WHERE schema_name = '" + db.archiveName() + "')"));

            LegacySchemaAdoption.Outcome retried = LegacySchemaAdoption.adopt(request(db));
            assertFalse(retried.resumed(), "the rolled-back attempt left no preservation to resume");
            assertEquals(8, retried.manifest().size());

            // Running it again must be a no-op rather than a second import or a double association.
            LegacySchemaAdoption.Outcome again = LegacySchemaAdoption.adopt(request(db));
            assertTrue(again.resumed(), "committed preservation must be resumed, not repeated");
            assertEquals(retried.importId(), again.importId(),
                    "the import ID is derived from the source inventory, so a retry reuses it");
            assertEquals(1, db.count(db.archiveName() + ".import_run"));
            assertEquals(1, db.count("account_luck_total"));
            assertEquals(1, db.count("gw2_account"));
            assertEquals(4, db.count(db.archiveName() + ".account_luck"));
        }
    }

    /**
     * STORY-INFRA-004 acceptance criteria 5 and 6: an adoption that was interrupted after the
     * versioned chain was recorded is resumed by rerunning it, and between the two runs the live
     * Luck destination may hold something the archive does not prove. The resumed import must not
     * accept that row: it may neither overwrite it nor count the archived source as associated, and
     * the cutover stays blocked until an operator resolves which value is correct. Resuming with
     * exactly the archived value and fetch time must still succeed, so the conflict rule does not
     * break the ordinary idempotent retry.
     */
    @Test
    void aResumedAdoptionRefusesAConflictingLiveLuckRowAndKeepsTheCutoverBlocked() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_luck_conflict")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");

            // Interrupt the cutover in the window where the chain is committed but the preserved
            // dataset is not yet reconciled - the state a crash or a failed resume leaves behind.
            assertThrows(SchemaGateException.class,
                    () -> LegacySchemaAdoption.adopt(request(db), new LegacySchemaAdoption.Hooks() {
                        @Override
                        public void afterPreservationWrites() {}

                        @Override
                        public void afterSchemaHistoryRecorded() {
                            throw new IllegalStateException("injected crash after the chain was recorded");
                        }
                    }));
            assertEquals(0, db.count("account_luck_total"),
                    "the rolled-back association must have left no live Luck row");

            // Someone else's value reaches the proven GUID's live destination before the resume.
            db.execute("INSERT INTO gw2_account (account_id) VALUES ('" + PROVEN_GUID + "')");
            db.execute("INSERT INTO account_luck_total (account_id, consumed_luck, fetched_at) VALUES ('"
                    + PROVEN_GUID + "', 7654321, '2026-03-09T09:00:00Z')");

            LegacyAdoptionBlockedException valueConflict = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db)));
            assertTrue(valueConflict.reasons().stream().anyMatch(reason -> reason.contains(PROVEN_GUID)
                            && reason.contains("conflicts with the archived legacy row")
                            && reason.contains("7654321") && reason.contains("1234567")),
                    "the conflict must be reported with both values: " + valueConflict.reasons());

            assertEquals(List.of("7654321|2026-03-09 09:00:00+00"), db.rows(
                            "SELECT consumed_luck || '|' || fetched_at FROM account_luck_total"),
                    "the conflicting live row must be left exactly as it was, never overwritten");
            assertEquals(0, db.count(db.archiveName() + ".provenance_ledger"),
                    "a conflicting destination must not be recorded as a successful association");
            assertEquals(1, db.scalarLong("SELECT count(*) FROM " + db.archiveName()
                            + ".import_run WHERE reconciled_at IS NULL"),
                    "the import must not be marked reconciled while the conflict is unresolved");
            assertEquals(4, db.count(db.archiveName() + ".account_luck"), "the archive must be intact");

            SchemaGateException refused = assertThrows(SchemaGateException.class,
                    () -> SchemaGate.run(db.url(), db.user(), db.password(), db.name()),
                    "cutover must stay blocked: startup may not serve the unreconciled database");
            assertTrue(refused.getMessage().contains("never reconciled"), refused.getMessage());

            // The same holds when only the original fetch time differs.
            db.execute("UPDATE account_luck_total SET consumed_luck = 1234567,"
                    + " fetched_at = '2026-03-09T09:00:00Z' WHERE account_id = '" + PROVEN_GUID + "'");
            LegacyAdoptionBlockedException timeConflict = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db)));
            assertTrue(timeConflict.reasons().stream().anyMatch(reason -> reason.contains(PROVEN_GUID)
                            && reason.contains("conflicts with the archived legacy row")
                            && reason.contains("2026-02-05T14:00:00Z")),
                    "a differing original fetch time is a conflict too: " + timeConflict.reasons());
            assertEquals(List.of("1234567|2026-03-09 09:00:00+00"), db.rows(
                    "SELECT consumed_luck || '|' || fetched_at FROM account_luck_total"));
            assertEquals(1, db.scalarLong("SELECT count(*) FROM " + db.archiveName()
                    + ".import_run WHERE reconciled_at IS NULL"));

            // A live Luck row for a GUID the archive quarantined is unexplained in the same way: it
            // is nobody's proven data, so it may not be accepted either.
            db.execute("UPDATE account_luck_total SET fetched_at = '2026-02-05T14:00:00Z'"
                    + " WHERE account_id = '" + PROVEN_GUID + "'");
            db.execute("INSERT INTO gw2_account (account_id) VALUES ('" + QUARANTINED_GUID + "')");
            db.execute("INSERT INTO account_luck_total (account_id, consumed_luck, fetched_at) VALUES ('"
                    + QUARANTINED_GUID + "', 7, '2026-02-05T14:00:00Z')");
            LegacyAdoptionBlockedException unexplained = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db)));
            assertTrue(unexplained.reasons().stream().anyMatch(reason -> reason.contains(QUARANTINED_GUID)
                            && reason.contains("did not associate")),
                    "a live Luck row no archived row proves must block the cutover: "
                            + unexplained.reasons());
            assertEquals(1, db.scalarLong("SELECT count(*) FROM " + db.archiveName()
                    + ".import_run WHERE reconciled_at IS NULL"));
            db.execute("DELETE FROM account_luck_total WHERE account_id = '" + QUARANTINED_GUID + "'");

            // Resolved: the live destination now holds exactly what the archive proves, so the same
            // resume must complete, keeping the identical-data retry working.
            LegacySchemaAdoption.Outcome resumed = LegacySchemaAdoption.adopt(request(db));
            assertTrue(resumed.resumed(), "the committed preservation must be resumed, not imported again");
            assertEquals(List.of("LUCK|ASSOCIATED|" + PROVEN_GUID), db.rows(
                            "SELECT source || '|' || outcome || '|' || coalesce(account_id::text, 'null')"
                                    + " FROM " + db.archiveName() + ".provenance_ledger"
                                    + " WHERE outcome = 'ASSOCIATED'"),
                    "only the resolved, matching Luck row may be recorded as associated");
            assertEquals(List.of("1234567|2026-02-05 14:00:00+00"), db.rows(
                    "SELECT consumed_luck || '|' || fetched_at FROM account_luck_total"));
            assertNotNull(db.scalarText("SELECT reconciled_at::text FROM " + db.archiveName()
                            + ".import_run"),
                    "the resolved adoption must record that reconciliation passed");
            assertEquals(SchemaGate.State.MANAGED,
                    SchemaGate.run(db.url(), db.user(), db.password(), db.name()).state(),
                    "once the conflict is resolved and reconciled, startup must serve the database");
        }
    }

    @Test
    void failedIntegrityReconciliationBlocksCutoverAndPreservesTheArchive() throws Exception {
        try (DisposableSchema db = new DisposableSchema("adopt_reconcile")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");
            LegacySchemaAdoption.adopt(request(db));

            // A live account row no source can prove ownership of must stop the cutover.
            db.execute("INSERT INTO account_bank_slot (account_id, slot, item_id, count, fetched_at) VALUES "
                    + "('" + PROVEN_GUID + "', 0, 100, 1, '2026-03-01T00:00:00Z')");
            LegacyAdoptionBlockedException unowned = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db)));
            assertTrue(unowned.reasons().stream().anyMatch(reason ->
                            reason.contains("account_bank_slot") && reason.contains("speculatively")),
                    unowned.reasons().toString());
            assertTrue(unowned.getMessage().contains("nothing was deleted"), unowned.getMessage());
            db.execute("DELETE FROM account_bank_slot");

            // An archive that no longer matches its manifest must stop it too.
            db.execute("DELETE FROM " + db.archiveName() + ".account_bank WHERE slot = 2");
            LegacyAdoptionBlockedException mismatch = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db)));
            assertTrue(mismatch.reasons().stream().anyMatch(reason ->
                            reason.contains("account_bank no longer matches its manifest entry")),
                    mismatch.reasons().toString());
            assertEquals(2, db.count(db.archiveName() + ".account_bank"),
                    "a blocked reconciliation must leave the archive and ledger in place");
            assertEquals(1, db.count(db.archiveName() + ".import_run"));
        }
    }

    @Test
    void theMigrationAndAdoptionPathMakesNoUpstreamApiCall() throws Exception {
        Path compiled = Path.of("target", "classes", "infra", "schema");
        assertTrue(Files.isDirectory(compiled), "expected compiled migration classes at " + compiled);

        try (Stream<Path> classes = Files.list(compiled)) {
            for (Path file : classes.filter(path -> path.toString().endsWith(".class")).toList()) {
                String constants = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("api/Gw2", "sync/AccountSync", "sync/CharacterSync",
                        "java/net/http", "java/net/URL", "HttpClient")) {
                    assertFalse(constants.contains(forbidden),
                            file.getFileName() + " references " + forbidden
                                    + "; no schema migration may call upstream or read a deployment key");
                }
            }
        }
    }

    /** Reads the fixture-derived legacy SQL back, so a fixture change cannot silently pass unused. */
    @Test
    void theLegacyFixturesCoverExactlyTheSupportedInventory() throws IOException {
        String required = DisposableSchema.read("/db/legacy-fixtures/legacy_required.sql");
        for (var table : LegacySchemaFingerprint.REQUIRED_OBJECTS) {
            assertTrue(required.contains("CREATE TABLE " + table.name() + " "),
                    "the required fixture must create " + table.name());
        }
        assertEquals(5, LegacySchemaFingerprint.OPTIONAL_OBJECTS.size(),
                "each documented optional variant needs its own fixture");
    }
}
