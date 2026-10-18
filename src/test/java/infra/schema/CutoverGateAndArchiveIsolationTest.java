package infra.schema;

import org.junit.jupiter.api.Test;
import repo.AppConfig;
import repo.Db;
import repo.ManagedSchema;
import repo.RuntimeAccessRole;
import sync.IconSync;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criteria 1, 4 and 6: the three guarantees that only hold where the
 * <em>serving</em> path and the <em>runtime database access boundary</em> actually enforce them,
 * rather than where the adoption path checks them for itself.
 *
 * <ul>
 *   <li>A cutover that got as far as committing the versioned chain but never finished its
 *       provenance association and integrity reconciliation must not be served - neither after an
 *       interruption in that window nor after a reconciliation failure. Ordinary startup has to
 *       refuse it, because Flyway commits in the middle of the cutover and a managed database would
 *       otherwise look current.</li>
 *   <li>The archive must be denied to the credentials the runtime actually connects with, for a
 *       schema-qualified read, while authorized migration/archive access still reconciles the
 *       preserved rows. Grants to {@code PUBLIC} and the search path cannot deliver this on their
 *       own: the runtime credential owns the archived tables.</li>
 *   <li>The retained standalone {@link IconSync} entry point must pass the same gate before it can
 *       read anything.</li>
 * </ul>
 *
 * <p>Every check runs the production chain and the production adoption path in its own disposable
 * schema (docs/TEST_STRATEGY.md §31.2); no developer database content is read or written.
 */
class CutoverGateAndArchiveIsolationTest {

    /** An unrelated registry GUID, used only to plant a live row no source can prove. */
    private static final String UNPROVEN_GUID = "99999999-8888-7777-6666-555555555555";

    private static final String DRY_RUN_OUTPUT = "Items the icon-metadata refresh would cover";

    private LegacySchemaAdoption.Request request(DisposableSchema db) {
        return new LegacySchemaAdoption.Request(db.url(), db.user(), db.password(), db.name(),
                db.archiveName(), "pg_dump gw2 2026-10-18T09:00Z -> /backups/gw2-pre-cutover.dump",
                "maintainer confirmed backend, JavaFX and schedulers stopped", true);
    }

    private SchemaGate.Result gate(DisposableSchema db) {
        return SchemaGate.run(db.url(), db.user(), db.password(), db.name());
    }

    @Test
    void startupRefusesToServeACutoverInterruptedBetweenMigrationAndReconciliation() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_intr")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");

            SchemaGateException interrupted = assertThrows(SchemaGateException.class,
                    () -> LegacySchemaAdoption.adopt(request(db), new LegacySchemaAdoption.Hooks() {
                        @Override
                        public void afterPreservationWrites() {}

                        @Override
                        public void afterSchemaHistoryRecorded() {
                            throw new IllegalStateException("injected crash after the chain was recorded");
                        }
                    }));
            assertTrue(interrupted.getMessage().contains("interrupted after the versioned chain"),
                    interrupted.getMessage());

            // The window this is about: the chain is committed, so the database is MANAGED, but the
            // preserved dataset is still unaccounted for.
            assertTrue(db.scalarBoolean("SELECT EXISTS (SELECT 1 FROM information_schema.tables"
                            + " WHERE table_schema = current_schema() AND table_name = '"
                            + SchemaGate.HISTORY_TABLE + "')"),
                    "the interruption must happen after the versioned chain is committed");
            assertEquals(1, db.scalarLong("SELECT count(*) FROM " + db.archiveName()
                            + ".import_run WHERE reconciled_at IS NULL"),
                    "the preserved import must still be marked unreconciled");
            assertEquals(0, db.count("gw2_account"),
                    "the rolled-back provenance association must have left no registry row");

            SchemaGateException refused = assertThrows(SchemaGateException.class, () -> gate(db),
                    "ordinary startup must refuse a managed database whose adoption never finished");
            assertTrue(refused.getMessage().contains("refusing to serve"), refused.getMessage());
            assertTrue(refused.getMessage().contains("never reconciled"), refused.getMessage());
            assertTrue(refused.getMessage().contains("LegacySchemaAdoptionCli"),
                    "the refusal must name how to resume: " + refused.getMessage());

            // Nothing was deleted, so resuming is possible rather than only reporting a dead end.
            assertEquals(4, db.count(db.archiveName() + ".account_luck"));
            assertEquals(1, db.count(db.archiveName() + ".import_run"));

            LegacySchemaAdoption.Outcome resumed = LegacySchemaAdoption.adopt(request(db));
            assertTrue(resumed.resumed(), "the committed preservation must be resumed, not imported again");
            assertEquals(1, db.count(db.archiveName() + ".import_run"), "no second import may be created");
            assertNotNull(db.scalarText("SELECT reconciled_at::text FROM " + db.archiveName() + ".import_run"),
                    "a completed adoption must record that reconciliation passed");

            SchemaGate.Result served = gate(db);
            assertEquals(SchemaGate.State.MANAGED, served.state(),
                    "once the adoption is reconciled, ordinary startup must serve the database again");
            assertEquals(1, db.count("gw2_account"));
        }
    }

    @Test
    void startupRefusesToServeAfterIntegrityReconciliationFailed() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_recon")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");

            // Plant, in the one window where the live account tables already exist but the cutover
            // has not been reconciled, a live account row no legacy source can prove ownership of.
            // The final reconciliation must reject it - and the point of this check is what startup
            // does afterwards, with the chain committed and the archive preserved.
            LegacyAdoptionBlockedException blocked = assertThrows(LegacyAdoptionBlockedException.class,
                    () -> LegacySchemaAdoption.adopt(request(db), new LegacySchemaAdoption.Hooks() {
                        @Override
                        public void afterPreservationWrites() {}

                        @Override
                        public void afterSchemaHistoryRecorded() throws SQLException {
                            try (Connection other = db.openAnotherConnection();
                                 Statement st = other.createStatement()) {
                                st.execute("INSERT INTO gw2_account (account_id) VALUES ('"
                                        + UNPROVEN_GUID + "')");
                                st.execute("INSERT INTO account_bank_slot"
                                        + " (account_id, slot, item_id, count, fetched_at) VALUES ('"
                                        + UNPROVEN_GUID + "', 0, 100, 1, '2026-03-01T00:00:00Z')");
                            }
                        }
                    }));
            assertTrue(blocked.reasons().stream().anyMatch(reason ->
                            reason.contains("account_bank_slot") && reason.contains("speculatively")),
                    blocked.reasons().toString());

            assertEquals(1, db.scalarLong("SELECT count(*) FROM " + db.archiveName()
                            + ".import_run WHERE reconciled_at IS NULL"),
                    "a failed reconciliation must leave the import unreconciled");
            assertEquals(0, db.count(db.archiveName() + ".provenance_ledger"),
                    "the blocked cutover's provenance association must have rolled back");

            SchemaGateException refused = assertThrows(SchemaGateException.class, () -> gate(db),
                    "startup must refuse a database whose integrity reconciliation failed");
            assertTrue(refused.getMessage().contains("never reconciled"), refused.getMessage());
            assertEquals(4, db.count(db.archiveName() + ".account_luck"),
                    "the refusal must not cost the preserved dataset");

            // Remove the planted row and resume: the gate must stop refusing for the right reason.
            db.execute("DELETE FROM account_bank_slot");
            db.execute("DELETE FROM gw2_account WHERE account_id = '" + UNPROVEN_GUID + "'");
            assertTrue(LegacySchemaAdoption.adopt(request(db)).resumed());
            assertEquals(SchemaGate.State.MANAGED, gate(db).state());
        }
    }

    @Test
    void runtimeDatabaseAccessIsDeniedTheArchiveWhileArchiveAccessStillReconcilesIt() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_arch")) {
            assertEquals(db.url(), AppConfig.DB_URL,
                    "this check drives repo.Db.open(), so it must address the same database");
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");
            String archivedBankChecksum = db.scalarText(
                    "SELECT md5(string_agg(t::text, ',' ORDER BY t::text)) FROM account_bank t");

            LegacySchemaAdoption.adopt(request(db));

            String restrictedRole = RuntimeAccessRole.roleNameFor(db.name());
            System.setProperty(Db.TEST_SCHEMA_PROPERTY, db.name());
            try (Connection runtime = Db.open(); Statement st = runtime.createStatement()) {
                assertEquals(restrictedRole, scalar(st, "SELECT current_user"),
                        "the one runtime database access boundary must switch to the restricted role");

                // The read this story is about: schema-qualified, so neither the search path nor the
                // absence of a PUBLIC grant can be what denies it. The runtime credential owns these
                // tables, so only a role boundary denies this.
                for (String archived : new String[] {"account_bank", "account_luck", "character_items",
                        "import_run", "import_manifest", "provenance_ledger"}) {
                    String qualified = "\"" + db.archiveName() + "\".\"" + archived + "\"";
                    SQLException denied = assertThrows(SQLException.class,
                            () -> st.executeQuery("SELECT count(*) FROM " + qualified),
                            "runtime access must be denied " + qualified);
                    assertEquals("42501", denied.getSQLState(),
                            "the denial must be PostgreSQL's own insufficient_privilege, not a name"
                                    + " resolution accident: " + denied.getMessage());
                }

                // Not a blanket breakage: the same restricted connection serves the live schema.
                assertEquals("0", scalar(st, "SELECT count(*)::text FROM account_bank_slot"));
                assertEquals("1", scalar(st, "SELECT count(*)::text FROM gw2_account"));

                // And the old unscoped name is unreachable on the live schema as well.
                SQLException unscoped = assertThrows(SQLException.class,
                        () -> st.executeQuery("SELECT count(*) FROM account_bank"));
                assertTrue(unscoped.getMessage().contains("account_bank"), unscoped.getMessage());

                // The restriction must not cost the actionable diagnostic: the restricted role still
                // recognizes the managed state, so an unscoped runtime path is refused with the
                // reason rather than with a bare missing-relation error.
                assertTrue(ManagedSchema.isManaged(runtime),
                        "the restricted runtime role must still see that the chain owns this schema");
            } finally {
                System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            }

            // Same thing through a retained entry point, end to end on the adopted database.
            System.setProperty(Db.TEST_SCHEMA_PROPERTY, db.name());
            try {
                Capture refused = capture(() -> assertThrows(SQLException.class,
                        () -> IconSync.main(new String[] {"--dry-run"})));
                assertTrue(((SQLException) refused.value()).getMessage().contains("disabled"),
                        refused.value().toString());
                assertFalse(refused.output().contains(DRY_RUN_OUTPUT), refused.output());
            } finally {
                System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            }

            // Authorized migration/archive access still reconciles the preserved data: same rows,
            // same content, against the manifest the import recorded.
            assertEquals(3, db.count(db.archiveName() + ".account_bank"));
            assertEquals(archivedBankChecksum, db.scalarText(
                            "SELECT md5(string_agg(t::text, ',' ORDER BY t::text)) FROM "
                                    + db.archiveName() + ".account_bank t"),
                    "the archived rows must still be byte-identical to the legacy source rows");
            assertEquals(archivedBankChecksum, db.scalarText("SELECT content_checksum FROM "
                            + db.archiveName() + ".import_manifest WHERE object_name = 'account_bank'"),
                    "the manifest must remain reconcilable against the archive it describes");
        }
    }

    @Test
    void theRetainedIconSyncEntryPointCannotReachDataBeforeTheSchemaIsValidated() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_icon")) {
            gate(db);
            long originalChecksum = db.scalarLong(
                    "SELECT checksum FROM " + SchemaGate.HISTORY_TABLE + " WHERE version = '2'");
            db.execute("UPDATE " + SchemaGate.HISTORY_TABLE + " SET checksum = 123456789 WHERE version = '2'");

            System.setProperty(Db.TEST_SCHEMA_PROPERTY, db.name());
            try {
                // --dry-run is the one IconSync mode that reads the database and calls nothing
                // upstream, so whether it printed its coverage report is exactly whether this
                // retained entry point reached data.
                Capture blocked = capture(() -> assertThrows(SchemaGateException.class,
                        () -> IconSync.main(new String[] {"--dry-run"}),
                        "the retained entry point must pass the same gate as the backend"));
                assertTrue(((SchemaGateException) blocked.value()).getMessage().toLowerCase()
                        .contains("checksum"), blocked.value().toString());
                assertFalse(blocked.output().contains(DRY_RUN_OUTPUT),
                        "nothing may be read before the schema is validated, but the entry point"
                                + " reported: " + blocked.output());

                // With the edited applied migration restored the gate passes - and the command is
                // still refused, now explicitly, because its item-id selection reads the legacy
                // unscoped account tables and no unscoped path may run against the upgraded schema.
                db.execute("UPDATE " + SchemaGate.HISTORY_TABLE + " SET checksum = " + originalChecksum
                        + " WHERE version = '2'");
                Capture disabled = capture(() -> assertThrows(SQLException.class,
                        () -> IconSync.main(new String[] {"--dry-run"})));
                SQLException refusal = (SQLException) disabled.value();
                assertTrue(refusal.getMessage().contains("disabled")
                                && refusal.getMessage().contains("account_bank"),
                        "an upgraded database must disable the unscoped path with an actionable"
                                + " diagnostic: " + refusal.getMessage());
                assertFalse(disabled.output().contains(DRY_RUN_OUTPUT),
                        "a disabled unscoped path must report no coverage: " + disabled.output());
            } finally {
                System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            }
        }
    }

    /**
     * The counterpart of the gate and the explicit disable: against the pre-migration schema, where
     * the gate reports the legacy state without migrating anything, the retained entry point still
     * runs exactly as it did. Without this, "it is refused" would be indistinguishable from "it is
     * broken".
     */
    @Test
    void theRetainedIconSyncEntryPointStillRunsAgainstAnUnmigratedLegacyDatabase() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_icon3")) {
            db.applyLegacy("optional_material_catalog.sql");
            db.apply("seed_material_catalog.sql");

            System.setProperty(Db.TEST_SCHEMA_PROPERTY, db.name());
            try {
                Capture ran = capture(() -> {
                    IconSync.main(new String[] {"--dry-run"});
                    return null;
                });
                assertTrue(ran.output().contains("pre-migration manual schema"),
                        "the gate must report the legacy state rather than migrate it: " + ran.output());
                assertTrue(ran.output().contains(DRY_RUN_OUTPUT),
                        "an unmigrated database must keep running this entry point: " + ran.output());
            } finally {
                System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            }

            assertFalse(db.scalarBoolean("SELECT EXISTS (SELECT 1 FROM information_schema.tables"
                            + " WHERE table_schema = current_schema() AND table_name = '"
                            + SchemaGate.HISTORY_TABLE + "')"),
                    "a retained entry point must not migrate a legacy database on the way past");
        }
    }

    /**
     * An unreconciled adoption must also stop the retained standalone entry point, not only the
     * backend's own startup.
     */
    @Test
    void theRetainedIconSyncEntryPointRefusesAnUnreconciledAdoption() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_icon2")) {
            db.applyLegacy("optional_account_luck.sql");
            db.apply("seed_account_luck.sql");
            assertThrows(SchemaGateException.class,
                    () -> LegacySchemaAdoption.adopt(request(db), new LegacySchemaAdoption.Hooks() {
                        @Override
                        public void afterPreservationWrites() {}

                        @Override
                        public void afterSchemaHistoryRecorded() {
                            throw new IllegalStateException("injected crash after the chain was recorded");
                        }
                    }));
            assertNull(db.scalarText("SELECT reconciled_at::text FROM " + db.archiveName() + ".import_run"));

            System.setProperty(Db.TEST_SCHEMA_PROPERTY, db.name());
            try {
                Capture refused = capture(() -> assertThrows(SchemaGateException.class,
                        () -> IconSync.main(new String[] {"--dry-run"})));
                assertTrue(((SchemaGateException) refused.value()).getMessage()
                        .contains("never reconciled"), refused.value().toString());
                assertFalse(refused.output().contains(DRY_RUN_OUTPUT),
                        "the standalone entry point must not read an unreconciled database");
            } finally {
                System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            }
        }
    }

    /**
     * The other half of the restriction's contract: an installation that never held legacy data has
     * no archive, therefore no restricted role, and therefore completely unchanged runtime behavior.
     */
    @Test
    void anInstallationWithoutAnArchiveIsNotRestrictedAtAll() throws Exception {
        try (DisposableSchema db = new DisposableSchema("cut_plain")) {
            gate(db);

            String role = RuntimeAccessRole.roleNameFor(db.name());
            assertFalse(RuntimeAccessRole.exists(db.connection(), role),
                    "a fresh installation must not create a restricted role");
            assertFalse(RuntimeAccessRole.restrict(db.connection()),
                    "with no archive there is nothing to restrict");
            // Both grant sides are no-ops without a role, so the startup gate can call them blindly.
            RuntimeAccessRole.grantLiveSchemaAccess(db.connection(), db.name());
            RuntimeAccessRole.denyAccessTo(db.connection(), db.name(), db.archiveName());
            assertFalse(RuntimeAccessRole.exists(db.connection(), role));

            System.setProperty(Db.TEST_SCHEMA_PROPERTY, db.name());
            try (Connection runtime = Db.open(); Statement st = runtime.createStatement()) {
                assertEquals(db.user(), scalar(st, "SELECT current_user"),
                        "runtime connections must keep their own credential where no archive exists");
            } finally {
                System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
            }
        }
    }

    @Test
    void theRestrictedRoleNameStaysWithinPostgresIdentifierLimits() {
        String longSchema = "s".repeat(80);
        String role = RuntimeAccessRole.roleNameFor(longSchema);

        assertEquals(63, role.length(),
                "a role name past NAMEDATALEN - 1 would be truncated by PostgreSQL itself, so the"
                        + " name the runtime switches to and the name adoption created must match");
        assertTrue(role.startsWith(RuntimeAccessRole.ROLE_PREFIX), role);
        assertEquals(role, RuntimeAccessRole.roleNameFor(longSchema), "the name must be stable");
    }

    private static String scalar(Statement st, String sql) throws SQLException {
        try (var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** What an entry-point invocation returned or threw, plus everything it printed. */
    private record Capture(Object value, String output) {}

    private interface Invocation {
        Object run() throws Exception;
    }

    /**
     * Runs one entry point with {@code System.out} captured, because "it did not reach data" is
     * asserted on whether the coverage report was printed. The stream is always restored.
     */
    private static Capture capture(Invocation invocation) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Object value;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            value = invocation.run();
        } finally {
            System.setOut(original);
        }
        return new Capture(value, captured.toString(StandardCharsets.UTF_8));
    }
}
