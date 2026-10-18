package infra.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import repo.RuntimeAccessRole;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The explicit, one-time legacy adoption (TARGET_ARCHITECTURE.md 7.4, ADR-010).
 *
 * <p>This is deliberately not part of startup. It needs the old writers stopped and a restorable
 * backup, it refuses anything it does not recognize, and it deletes nothing - ever. What it does, in
 * order:
 *
 * <ol>
 *   <li>Refuse unless the operator supplied a backup reference, a stopped-writers attestation and an
 *       explicit confirmation, and unless an exclusive advisory lock can be taken.</li>
 *   <li>Preflight the schema against {@link LegacySchemaFingerprint}. Unknown drift, a managed
 *       database or an empty one is refused with a report and no writes.</li>
 *   <li>Inventory every present legacy account object: row counts, a UTC-rendered content checksum,
 *       the original {@code fetched_at} range, rows that are invalid or orphaned, and the inventory
 *       rows whose legacy representation cannot distinguish duplicate inventory slots.</li>
 *   <li>Move those objects, with their original rows, relations and timestamps, into a restricted
 *       archive schema, and record the import manifest and provenance ledger beside them. Global
 *       tables and their data stay exactly where they are.</li>
 *   <li>Normalize the remaining live schema to migration version V1, reconcile archive content
 *       against the manifest, and commit. A failure anywhere up to here rolls the whole step back.</li>
 *   <li>Record the Flyway baseline at V1 and run the chain, so the adopted database converges on the
 *       same live schema an empty one reaches.</li>
 *   <li>Associate only what has source-specific historical identity provenance: a valid
 *       identity-aware Luck row keeps its GUID, value and original time. Everything else is recorded
 *       as explicitly unresolved and stays archived. Live data is never overwritten: a destination
 *       that already holds something else is left alone and recorded as conflicted. No upstream call
 *       is made and no activity or source-completion history is invented.</li>
 *   <li>Reconcile live, archive and ledger before the new runtime serves - including that every
 *       associated account's live value and original fetch time are still the archived ones.</li>
 * </ol>
 *
 * <p>A crash between steps is resumable: the import ID is derived from the source inventory rather
 * than generated, the manifest and ledger writes are idempotent, and a rerun that finds preservation
 * already committed continues from there instead of re-archiving or double-importing.
 */
public final class LegacySchemaAdoption {

    /** Where the preserved legacy account dataset lives, outside every runtime search path. */
    public static final String DEFAULT_ARCHIVE_SCHEMA = "gw2_legacy_archive";

    /** Serializes adoption against itself and against account publication. */
    private static final long ADOPTION_LOCK_KEY = 7726_2026_0004L;

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Ledger outcomes. Nothing is ever recorded as owned without source-specific provenance. */
    public static final String ASSOCIATED = "ASSOCIATED";
    public static final String QUARANTINED = "QUARANTINED";
    public static final String UNRESOLVED = "UNRESOLVED";
    /**
     * The archived source is provably owned, but its live destination already holds different data,
     * so the import can neither overwrite it nor claim the association succeeded. Cutover stays
     * blocked until an operator resolves which value is correct.
     */
    public static final String CONFLICTED = "CONFLICTED";

    /**
     * Test seam for deterministic failure injection. Production uses the default no-ops, so the
     * shipped path has no extra branch; a test implements {@link #afterPreservationWrites()} to fail
     * once preservation has begun and assert the whole step rolled back, or
     * {@link #afterSchemaHistoryRecorded()} to interrupt a cutover in the one window where the
     * versioned chain is already committed but the preserved dataset is not yet reconciled.
     */
    public interface Hooks {
        void afterPreservationWrites() throws Exception;

        default void afterSchemaHistoryRecorded() throws Exception {}
    }

    private static final Hooks NO_HOOKS = () -> {};

    /**
     * @param liveSchema               schema to adopt, or {@code null} for the connection default
     * @param archiveSchema            where the preserved dataset goes
     * @param backupReference          how the restorable backup can be found; required
     * @param writersStoppedAttestedBy who confirmed the old writers and scheduled work are stopped
     * @param confirmed                explicit operator confirmation; nothing runs without it
     */
    public record Request(String url,
                          String user,
                          String password,
                          String liveSchema,
                          String archiveSchema,
                          String backupReference,
                          String writersStoppedAttestedBy,
                          boolean confirmed) {

        public Request {
            Objects.requireNonNull(url, "url");
            archiveSchema = archiveSchema == null ? DEFAULT_ARCHIVE_SCHEMA : archiveSchema;
        }
    }

    /** One legacy source's provenance outcome. {@code accountId} is set only when ASSOCIATED. */
    public record SourceOutcome(String source, String outcome, String accountId, long rowCount, String evidence) {}

    /** What one archived object contributed to the import manifest. */
    public record ManifestEntry(String object,
                                long rowCount,
                                String contentChecksum,
                                Instant oldestFetchedAt,
                                Instant newestFetchedAt,
                                long ambiguousRows,
                                long invalidRows) {}

    public record Outcome(UUID importId,
                          String fingerprintId,
                          List<String> variants,
                          List<String> absentOptionalObjects,
                          List<ManifestEntry> manifest,
                          List<SourceOutcome> ledger,
                          List<String> reconciliation,
                          boolean resumed) {

        /** Sources that could not be associated and therefore remain preserved but unusable. */
        public List<String> unresolvedSources() {
            return ledger.stream().filter(entry -> !ASSOCIATED.equals(entry.outcome()))
                    .map(SourceOutcome::source).distinct().toList();
        }

        public String report() {
            StringBuilder text = new StringBuilder();
            text.append(resumed ? "Resumed" : "Completed").append(" legacy adoption ").append(importId)
                    .append(" (").append(fingerprintId).append(", variants ").append(variants).append(")\n");
            text.append("Archived objects:\n");
            for (ManifestEntry entry : manifest) {
                text.append("  ").append(entry.object()).append(": ").append(entry.rowCount()).append(" rows")
                        .append(", checksum ").append(entry.contentChecksum());
                if (entry.ambiguousRows() > 0) text.append(", ").append(entry.ambiguousRows())
                        .append(" indistinguishable-slot rows");
                if (entry.invalidRows() > 0) text.append(", ").append(entry.invalidRows())
                        .append(" invalid/orphaned rows");
                text.append('\n');
            }
            text.append("Provenance:\n");
            for (SourceOutcome entry : ledger) {
                text.append("  ").append(entry.source()).append(": ").append(entry.outcome())
                        .append(entry.accountId() == null ? "" : " -> " + entry.accountId())
                        .append(" (").append(entry.rowCount()).append(" rows) ")
                        .append(entry.evidence()).append('\n');
            }
            text.append("Reconciliation:\n");
            for (String line : reconciliation) text.append("  ").append(line).append('\n');
            return text.toString();
        }
    }

    private LegacySchemaAdoption() {}

    public static Outcome adopt(Request request) {
        return adopt(request, NO_HOOKS);
    }

    static Outcome adopt(Request request, Hooks hooks) {
        List<String> refusals = new ArrayList<>();
        if (!request.confirmed()) refusals.add("no explicit confirmation was given");
        if (isBlank(request.backupReference())) {
            refusals.add("no restorable backup reference was supplied; take a backup first, because"
                    + " backup/restore is the only recovery boundary for this cutover");
        }
        if (isBlank(request.writersStoppedAttestedBy())) {
            refusals.add("no attestation that the legacy writers, schedulers and background work are"
                    + " stopped; adoption must not run alongside an active old writer");
        }
        if (!refusals.isEmpty()) {
            throw new LegacyAdoptionBlockedException("Legacy adoption refused before any write:", refusals);
        }

        try (Connection con = DriverManager.getConnection(request.url(), request.user(), request.password())) {
            if (request.liveSchema() != null) con.setSchema(request.liveSchema());
            con.setAutoCommit(false);
            utc(con);
            if (!tryLock(con)) {
                throw new LegacyAdoptionBlockedException("Legacy adoption refused before any write:",
                        List.of("another adoption or account publication holds the import lock"));
            }
            try {
                return run(con, request, hooks);
            } finally {
                unlock(con);
            }
        } catch (SQLException e) {
            throw new SchemaGateException("Legacy adoption failed against " + request.url(), e);
        }
    }

    private static Outcome run(Connection con, Request request, Hooks hooks) throws SQLException {
        String archive = request.archiveSchema();
        boolean resumed = preservationAlreadyCommitted(con, archive);

        UUID importId;
        List<ManifestEntry> manifest;
        LegacySchemaFingerprint.Inspection inspection;

        if (resumed) {
            importId = readImportId(con, archive);
            manifest = readManifest(con, archive, importId);
            inspection = readInspectionFromLedger(con, archive, importId);
            con.rollback();
        } else {
            inspection = preflight(con);
            manifest = inventory(con, inspection);
            importId = deriveImportId(inspection, manifest);
            preserve(con, request, archive, importId, inspection, manifest);
            try {
                hooks.afterPreservationWrites();
            } catch (Exception failure) {
                con.rollback();
                throw new SchemaGateException(
                        "Legacy adoption rolled back after preservation writes; the legacy schema and"
                                + " data are unchanged and the adoption can be retried", failure);
            }
            reconcilePreservation(con, archive, importId, manifest);
            restrictArchive(con, request, archive);
            con.commit();
        }

        baselineAndMigrate(request);

        try {
            hooks.afterSchemaHistoryRecorded();
        } catch (Exception failure) {
            con.rollback();
            throw new SchemaGateException(
                    "Legacy adoption was interrupted after the versioned chain was recorded and before"
                            + " the preserved dataset was reconciled. The archive, manifest and ledger are"
                            + " intact, startup refuses to serve this database until the adoption is"
                            + " resumed, and rerunning it continues from the committed preservation",
                    failure);
        }

        // The restricted runtime role may only reach the live schema once the chain has created it.
        RuntimeAccessRole.grantLiveSchemaAccess(con, con.getSchema());
        RuntimeAccessRole.denyAccessTo(con, con.getSchema(), archive);

        List<SourceOutcome> ledger = associateProvenance(con, request, archive, importId, inspection, manifest);
        List<String> reconciliation = reconcileBeforeServing(con, archive, importId, manifest, ledger);
        markReconciled(con, archive, importId);
        con.commit();

        return new Outcome(importId, inspection.fingerprintId(), inspection.variants(),
                inspection.absentOptional(), manifest, ledger, reconciliation, resumed);
    }

    // ---------------------------------------------------------------- preflight

    private static LegacySchemaFingerprint.Inspection preflight(Connection con) throws SQLException {
        SchemaGate.State state = SchemaGate.inspect(con);
        if (state == SchemaGate.State.MANAGED) {
            throw new LegacyAdoptionBlockedException("Legacy adoption refused before any write:",
                    List.of("this schema already has a Flyway history table, so it is managed and there"
                            + " is nothing to adopt"));
        }
        if (state == SchemaGate.State.EMPTY) {
            throw new LegacyAdoptionBlockedException("Legacy adoption refused before any write:",
                    List.of("this schema is empty; ordinary startup migrates an empty database and"
                            + " adoption must not record a baseline for it"));
        }

        LegacySchemaFingerprint.Inspection inspection = LegacySchemaFingerprint.inspect(con);
        if (!inspection.supported()) {
            List<String> reasons = new ArrayList<>(inspection.drift());
            reasons.add("expected the supported inventory " + LegacySchemaFingerprint.FINGERPRINT_ID
                    + "; nothing was written, moved or deleted, so the legacy database is unchanged and"
                    + " the differences above can be repaired or added to the supported inventory under"
                    + " review");
            throw new LegacyAdoptionBlockedException(
                    "Legacy adoption refused: unsupported schema drift.", reasons);
        }
        return inspection;
    }

    // ---------------------------------------------------------------- inventory

    private static List<ManifestEntry> inventory(Connection con,
                                                 LegacySchemaFingerprint.Inspection inspection) throws SQLException {
        List<ManifestEntry> entries = new ArrayList<>();
        for (String object : inspection.presentAccountObjects()) {
            entries.add(new ManifestEntry(object,
                    rowCount(con, quote(object)),
                    checksum(con, quote(object)),
                    instantOf(con, "SELECT min(fetched_at) FROM " + quote(object)),
                    instantOf(con, "SELECT max(fetched_at) FROM " + quote(object)),
                    ambiguousRows(con, object),
                    invalidRows(con, object)));
        }
        return entries;
    }

    private static long ambiguousRows(Connection con, String object) throws SQLException {
        // Only character inventory can carry rows the legacy representation cannot tell apart: its
        // unique index spans nullable bag/slot/equipment columns under NULLS DISTINCT, so two rows
        // differing only by a NULL part were both storable.
        if (!"character_items".equals(object)) return 0;
        return scalarLong(con, """
                SELECT coalesce(sum(duplicates), 0) FROM (
                    SELECT count(*) AS duplicates
                    FROM character_items
                    GROUP BY character_id, location, bag_index, slot_index, equipment_slot
                    HAVING count(*) > 1) ambiguous
                """);
    }

    private static long invalidRows(Connection con, String object) throws SQLException {
        return switch (object) {
            case "account_luck" -> scalarLong(con, """
                    SELECT count(*) FROM account_luck
                    WHERE account_id !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                    """);
            case "character_crafting", "character_recipes", "character_items" -> scalarLong(con, """
                    SELECT count(*) FROM %s child
                    WHERE NOT EXISTS (SELECT 1 FROM characters parent
                                      WHERE parent.character_id = child.character_id)
                    """.formatted(quote(object)));
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- preservation

    private static void preserve(Connection con,
                                 Request request,
                                 String archive,
                                 UUID importId,
                                 LegacySchemaFingerprint.Inspection inspection,
                                 List<ManifestEntry> manifest) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + quote(archive));
            st.execute("""
                    CREATE TABLE %s.import_run (
                        import_id                   UUID PRIMARY KEY,
                        fingerprint_id              TEXT NOT NULL,
                        variants                    TEXT NOT NULL,
                        absent_optional_objects     TEXT NOT NULL,
                        source_database             TEXT NOT NULL,
                        source_schema               TEXT NOT NULL,
                        backup_reference            TEXT NOT NULL,
                        writers_stopped_attested_by TEXT NOT NULL,
                        preserved_at                TIMESTAMPTZ NOT NULL,
                        -- The cutover marker the startup gate reads. It stays NULL from the moment
                        -- the archive is committed until provenance association and the final
                        -- integrity reconciliation have both passed, so an interrupted or failed
                        -- adoption cannot be served (see SchemaGate.unreconciledAdoptions).
                        reconciled_at               TIMESTAMPTZ
                    )""".formatted(quote(archive)));
            st.execute("""
                    CREATE TABLE %s.import_manifest (
                        import_id           UUID NOT NULL REFERENCES %s.import_run (import_id),
                        object_name         TEXT NOT NULL,
                        row_count           BIGINT NOT NULL,
                        content_checksum    TEXT NOT NULL,
                        oldest_fetched_at   TIMESTAMPTZ,
                        newest_fetched_at   TIMESTAMPTZ,
                        ambiguous_row_count BIGINT NOT NULL,
                        invalid_row_count   BIGINT NOT NULL,
                        PRIMARY KEY (import_id, object_name)
                    )""".formatted(quote(archive), quote(archive)));
            st.execute("""
                    CREATE TABLE %s.provenance_ledger (
                        import_id   UUID NOT NULL REFERENCES %s.import_run (import_id),
                        source      TEXT NOT NULL,
                        outcome     TEXT NOT NULL
                                    CHECK (outcome IN ('ASSOCIATED', 'QUARANTINED', 'UNRESOLVED', 'CONFLICTED')),
                        account_id  UUID,
                        row_count   BIGINT NOT NULL,
                        evidence    TEXT NOT NULL,
                        recorded_at TIMESTAMPTZ NOT NULL,
                        CONSTRAINT uq_provenance_ledger_destination
                            UNIQUE NULLS NOT DISTINCT (import_id, source, account_id)
                    )""".formatted(quote(archive), quote(archive)));
        }

        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO %s.import_run (import_id, fingerprint_id, variants, absent_optional_objects,
                                           source_database, source_schema, backup_reference,
                                           writers_stopped_attested_by, preserved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """.formatted(quote(archive)))) {
            ps.setObject(1, importId);
            ps.setString(2, inspection.fingerprintId());
            ps.setString(3, String.join(",", inspection.variants()));
            ps.setString(4, String.join(",", inspection.absentOptional()));
            ps.setString(5, request.url());
            ps.setString(6, con.getSchema());
            ps.setString(7, request.backupReference());
            ps.setString(8, request.writersStoppedAttestedBy());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO %s.import_manifest (import_id, object_name, row_count, content_checksum,
                                                oldest_fetched_at, newest_fetched_at,
                                                ambiguous_row_count, invalid_row_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(quote(archive)))) {
            for (ManifestEntry entry : manifest) {
                ps.setObject(1, importId);
                ps.setString(2, entry.object());
                ps.setLong(3, entry.rowCount());
                ps.setString(4, entry.contentChecksum());
                ps.setTimestamp(5, entry.oldestFetchedAt() == null ? null : Timestamp.from(entry.oldestFetchedAt()));
                ps.setTimestamp(6, entry.newestFetchedAt() == null ? null : Timestamp.from(entry.newestFetchedAt()));
                ps.setLong(7, entry.ambiguousRows());
                ps.setLong(8, entry.invalidRows());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // The move keeps every original row, relation, constraint and timestamp: the same table
        // objects simply stop being reachable from the runtime schema.
        try (Statement st = con.createStatement()) {
            for (ManifestEntry entry : manifest) {
                st.execute("ALTER TABLE " + quote(entry.object()) + " SET SCHEMA " + quote(archive));
            }
            // Explicit normalization of the detected variant, so the remaining live schema is exactly
            // migration version V1 - never a broad IF NOT EXISTS that would hide drift.
            for (String absent : inspection.absentOptional()) {
                if (!isGlobal(absent)) continue;
                st.execute(normalizationDdl(absent));
            }
        }
    }

    private static boolean isGlobal(String object) {
        return LegacySchemaFingerprint.allObjects().stream()
                .anyMatch(t -> t.name().equals(object) && t.scope() == LegacySchemaFingerprint.Scope.GLOBAL);
    }

    private static String normalizationDdl(String object) {
        return switch (object) {
            case "material_categories" -> """
                    CREATE TABLE material_categories (
                        category_id   INTEGER PRIMARY KEY,
                        name          TEXT    NOT NULL,
                        display_order INTEGER NOT NULL
                    )""";
            case "material_category_items" -> """
                    CREATE TABLE material_category_items (
                        category_id INTEGER NOT NULL,
                        position    INTEGER NOT NULL,
                        item_id     INTEGER NOT NULL,
                        CONSTRAINT material_category_items_pkey PRIMARY KEY (category_id, position),
                        CONSTRAINT material_category_items_category_id_fkey
                            FOREIGN KEY (category_id) REFERENCES material_categories (category_id) ON DELETE CASCADE
                    )""";
            default -> throw new SchemaGateException(
                    "No normalization is defined for the absent global object " + object);
        };
    }

    private static void reconcilePreservation(Connection con,
                                              String archive,
                                              UUID importId,
                                              List<ManifestEntry> manifest) throws SQLException {
        List<String> problems = new ArrayList<>();
        for (ManifestEntry entry : manifest) {
            String archived = quote(archive) + "." + quote(entry.object());
            long rows = rowCount(con, archived);
            String digest = checksum(con, archived);
            if (rows != entry.rowCount() || !digest.equals(entry.contentChecksum())) {
                problems.add(entry.object() + ": manifest recorded " + entry.rowCount() + " rows / "
                        + entry.contentChecksum() + " but the archive holds " + rows + " rows / " + digest);
            }
        }
        List<String> liveRelations = relations(con, con.getSchema());
        for (String leftover : liveRelations) {
            if (manifest.stream().anyMatch(entry -> entry.object().equals(leftover))) {
                problems.add("legacy account object " + leftover + " is still present in the live schema");
            }
        }
        if (!problems.isEmpty()) {
            con.rollback();
            throw new LegacyAdoptionBlockedException(
                    "Legacy adoption refused: preservation could not be reconciled, so nothing was"
                            + " committed and the legacy database is unchanged (import " + importId + ").",
                    problems);
        }
    }

    /**
     * Separates archive access from runtime access, at the database's own access boundary.
     *
     * <p>Removing the {@code PUBLIC} grants and keeping the archive out of every runtime search path
     * is not sufficient on its own: the configured runtime credential is also the owner of the moved
     * tables, and an owner can still read what it owns under its schema-qualified name. So the
     * restricted runtime role is created here, in the same transaction that creates the archive -
     * after this commits, {@code repo.Db.open()} switches every runtime connection to a role that
     * holds nothing on this schema, and PostgreSQL denies the read itself. The adoption session
     * keeps its own credential and can therefore still reconcile the preserved rows.
     */
    private static void restrictArchive(Connection con, Request request, String archive) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("REVOKE ALL ON SCHEMA " + quote(archive) + " FROM PUBLIC");
            st.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + quote(archive) + " FROM PUBLIC");
            st.execute("COMMENT ON SCHEMA " + quote(archive) + " IS "
                    + "'Preserved pre-migration account dataset and its import ledger. Not reachable "
                    + "from runtime account repositories; retained, never purged.'");
        }
        String liveSchema = request.liveSchema() == null ? con.getSchema() : request.liveSchema();
        RuntimeAccessRole.create(con, liveSchema);
        RuntimeAccessRole.denyAccessTo(con, liveSchema, archive);
    }

    // ---------------------------------------------------------------- schema history

    private static void baselineAndMigrate(Request request) {
        Flyway flyway = SchemaGate.flyway(request.url(), request.user(), request.password(), request.liveSchema());
        try {
            boolean managed;
            try (Connection con = DriverManager.getConnection(request.url(), request.user(), request.password())) {
                if (request.liveSchema() != null) con.setSchema(request.liveSchema());
                managed = SchemaGate.relationExists(con, SchemaGate.HISTORY_TABLE);
            }
            // A baseline is schema history only: it states that the global objects of version
            // V1 already exist, and never that any account row belongs to anybody.
            if (!managed) flyway.baseline();
            flyway.migrate();
            flyway.validate();
        } catch (FlywayException | SQLException e) {
            throw new SchemaGateException(
                    "Legacy adoption preserved the dataset but could not record the baseline and migrate."
                            + " The archive, manifest and ledger are committed, applied versions remain"
                            + " recorded, and rerunning the adoption resumes from that history.", e);
        }
    }

    // ---------------------------------------------------------------- provenance

    private static List<SourceOutcome> associateProvenance(Connection con,
                                                           Request request,
                                                           String archive,
                                                           UUID importId,
                                                           LegacySchemaFingerprint.Inspection inspection,
                                                           List<ManifestEntry> manifest) throws SQLException {
        utc(con);
        List<SourceOutcome> ledger = new ArrayList<>();

        // Only the identity-aware Luck table carries source-specific historical provenance.
        if (inspection.variants().contains("account_luck")) {
            ledger.addAll(importLuck(con, archive));
        }

        for (Map.Entry<String, String> unresolved : unresolvedSources(inspection, manifest).entrySet()) {
            ledger.add(new SourceOutcome(unresolved.getKey(), UNRESOLVED, null,
                    rowsOf(manifest, legacyObjectsOf(unresolved.getKey())), unresolved.getValue()));
        }

        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO %s.provenance_ledger (import_id, source, outcome, account_id, row_count,
                                                  evidence, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (import_id, source, account_id) DO UPDATE
                    SET outcome = EXCLUDED.outcome,
                        row_count = EXCLUDED.row_count,
                        evidence = EXCLUDED.evidence
                """.formatted(quote(archive)))) {
            for (SourceOutcome entry : ledger) {
                ps.setObject(1, importId);
                ps.setString(2, entry.source());
                ps.setString(3, entry.outcome());
                ps.setObject(4, entry.accountId() == null ? null : UUID.fromString(entry.accountId()));
                ps.setLong(5, entry.rowCount());
                ps.setString(6, entry.evidence());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return ledger;
    }

    /**
     * Imports the one legacy source whose ownership is proven by the source itself. A valid stored
     * GUID keeps its account, its consumed value and its original fetch time. Malformed identities
     * and GUIDs claimed by more than one row stay archived and unassociated.
     *
     * <p>No source-completion marker is written: the legacy per-source markers are unscoped, so the
     * fact that Luck once synchronized cannot be attributed to this account. The imported rows are
     * therefore owned but not a completed calculation source until a fresh synchronization.
     *
     * <p>The live destination is never overwritten. An interrupted adoption is resumed by rerunning
     * it, and the live Luck row it may already have written is only accepted as this import's own
     * work when its value <em>and</em> its original fetch time are still exactly the archived ones.
     * Anything else is live data this import cannot explain: it is left untouched, recorded
     * {@link #CONFLICTED} rather than associated, and {@link #reconcileBeforeServing} keeps the
     * cutover blocked until an operator resolves it.
     */
    private static List<SourceOutcome> importLuck(Connection con, String archive) throws SQLException {
        record LuckRow(String accountId, long consumed, Instant fetchedAt) {}
        List<LuckRow> rows = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT account_id, consumed_luck, fetched_at FROM " + quote(archive) + ".account_luck");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new LuckRow(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant()));
            }
        }

        Map<String, Integer> claims = new LinkedHashMap<>();
        for (LuckRow row : rows) {
            if (UUID_TEXT.matcher(row.accountId()).matches()) {
                claims.merge(row.accountId().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        List<SourceOutcome> outcomes = new ArrayList<>();
        long malformed = 0;
        long conflicting = 0;
        for (LuckRow row : rows) {
            boolean valid = UUID_TEXT.matcher(row.accountId()).matches();
            if (!valid) {
                malformed++;
                continue;
            }
            if (claims.get(row.accountId().toLowerCase(Locale.ROOT)) > 1) {
                conflicting++;
                continue;
            }
            UUID account = UUID.fromString(row.accountId());
            // The live destination is read before anything is written to it. A resumed or retried
            // adoption must find either nothing or exactly this archived row; different live data is
            // a conflict the import may neither overwrite nor report as a successful association.
            LiveLuck live = readLiveLuck(con, account);
            if (live != null && !live.matches(row.consumed(), row.fetchedAt())) {
                outcomes.add(new SourceOutcome("LUCK", CONFLICTED, account.toString(), 1,
                        "account_luck_total already holds " + live.consumed() + " luck fetched at "
                                + live.fetchedAt() + " for this GUID, but the archived legacy row holds "
                                + row.consumed() + " fetched at " + row.fetchedAt() + ". The live row was"
                                + " left untouched and the source is not associated; resolve which value"
                                + " is correct, then resume the adoption."));
                continue;
            }
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO gw2_account (account_id) VALUES (?) ON CONFLICT (account_id) DO NOTHING
                    """)) {
                ps.setObject(1, account);
                ps.executeUpdate();
            }
            if (live == null) {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO account_luck_total (account_id, consumed_luck, fetched_at)
                        VALUES (?, ?, ?)
                        """)) {
                    ps.setObject(1, account);
                    ps.setLong(2, row.consumed());
                    ps.setTimestamp(3, Timestamp.from(row.fetchedAt()));
                    ps.executeUpdate();
                }
            }
            outcomes.add(new SourceOutcome("LUCK", ASSOCIATED, account.toString(), 1,
                    "legacy account_luck row stored this verified /v2/account GUID itself; its value and"
                            + " original fetch time are preserved. No source completion marker is"
                            + " imported, so Luck stays unavailable as a completed source until a fresh"
                            + " synchronization."));
        }
        if (malformed + conflicting > 0) {
            outcomes.add(new SourceOutcome("LUCK", QUARANTINED, null, malformed + conflicting,
                    malformed + " row(s) with a malformed account identity and " + conflicting
                            + " row(s) whose GUID is claimed by more than one legacy row remain archived"
                            + " and unassociated."));
        }
        return outcomes;
    }

    /** What the live Luck destination already holds for one account, or {@code null} for nothing. */
    private record LiveLuck(long consumed, Instant fetchedAt) {
        boolean matches(long archivedConsumed, Instant archivedFetchedAt) {
            return consumed == archivedConsumed && fetchedAt.equals(archivedFetchedAt);
        }
    }

    private static LiveLuck readLiveLuck(Connection con, UUID account) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT consumed_luck, fetched_at FROM account_luck_total WHERE account_id = ?")) {
            ps.setObject(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new LiveLuck(rs.getLong(1), rs.getTimestamp(2).toInstant());
            }
        }
    }

    /** Every other legacy source, and the reason its ownership cannot be established. */
    private static Map<String, String> unresolvedSources(LegacySchemaFingerprint.Inspection inspection,
                                                         List<ManifestEntry> manifest) {
        Map<String, String> sources = new LinkedHashMap<>();
        String noLink = " The legacy unscoped schema records no link between this published source"
                + " generation and an upstream-verified account GUID. A configured key, the Luck GUID,"
                + " a character name, a non-empty table or the first login would not prove ownership,"
                + " so association stays unresolved and the rows remain preserved in the archive for"
                + " later evidence-based reconciliation.";
        sources.put("BANK", "account_bank has no account column." + noLink);
        sources.put("MATERIALS", "account_materials has no account column." + noLink);
        sources.put("ACCOUNT_RECIPES", "account_recipes has no account column." + noLink);
        sources.put("CHARACTERS", "characters and its child rows have no account column." + noLink
                + " Rows whose inventory slot cannot be distinguished are counted in the manifest and"
                + " await explicit reconciliation.");
        if (inspection.variants().contains("account_sync_state")) {
            sources.put("LEGACY_SOURCE_MARKERS",
                    "account_sync_state holds unscoped per-source completion times. A marker that names"
                            + " no account cannot establish that any account completed that source, and"
                            + " an identity-aware Luck row does not establish a source marker either, so"
                            + " no completion evidence is imported.");
        }
        if (inspection.variants().contains("account_materials_sync")) {
            sources.put("LEGACY_MATERIAL_SYNC_SINGLETON",
                    "The retired singleton marker's time was inferred from row fetch times, which cannot"
                            + " establish successful whole-source completion for any account.");
        }
        sources.keySet().removeIf(source -> legacyObjectsOf(source).stream()
                .noneMatch(object -> manifest.stream().anyMatch(entry -> entry.object().equals(object))));
        return sources;
    }

    private static List<String> legacyObjectsOf(String source) {
        return switch (source) {
            case "BANK" -> List.of("account_bank");
            case "MATERIALS" -> List.of("account_materials");
            case "ACCOUNT_RECIPES" -> List.of("account_recipes");
            case "CHARACTERS" -> List.of("characters", "character_crafting", "character_recipes", "character_items");
            case "LEGACY_SOURCE_MARKERS" -> List.of("account_sync_state");
            case "LEGACY_MATERIAL_SYNC_SINGLETON" -> List.of("account_materials_sync");
            case "LUCK" -> List.of("account_luck");
            default -> List.of();
        };
    }

    private static long rowsOf(List<ManifestEntry> manifest, List<String> objects) {
        return manifest.stream().filter(entry -> objects.contains(entry.object()))
                .mapToLong(ManifestEntry::rowCount).sum();
    }

    // ---------------------------------------------------------------- final reconciliation

    private static List<String> reconcileBeforeServing(Connection con,
                                                       String archive,
                                                       UUID importId,
                                                       List<ManifestEntry> manifest,
                                                       List<SourceOutcome> ledger) throws SQLException {
        List<String> report = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (ManifestEntry entry : manifest) {
            long rows = rowCount(con, quote(archive) + "." + quote(entry.object()));
            String digest = checksum(con, quote(archive) + "." + quote(entry.object()));
            if (rows != entry.rowCount() || !digest.equals(entry.contentChecksum())) {
                problems.add(entry.object() + " no longer matches its manifest entry");
            }
        }
        report.add("archive matches the import manifest for " + manifest.size() + " object(s)");

        long luckArchived = manifest.stream().filter(e -> e.object().equals("account_luck"))
                .mapToLong(ManifestEntry::rowCount).sum();
        long luckAccounted = ledger.stream().filter(e -> e.source().equals("LUCK"))
                .mapToLong(SourceOutcome::rowCount).sum();
        if (luckArchived != luckAccounted) {
            problems.add("account_luck has " + luckArchived + " archived rows but the ledger accounts for "
                    + luckAccounted);
        }
        report.add("every archived Luck row is accounted for as associated or quarantined ("
                + luckAccounted + " of " + luckArchived + ")");

        verifyAssociatedLuckMatchesTheArchive(con, archive, manifest, ledger, problems, report);

        long unowned = scalarLong(con, """
                SELECT (SELECT count(*) FROM account_luck_total l
                        WHERE NOT EXISTS (SELECT 1 FROM gw2_account a WHERE a.account_id = l.account_id))
                     + (SELECT count(*) FROM account_source_state s
                        WHERE NOT EXISTS (SELECT 1 FROM gw2_account a WHERE a.account_id = s.account_id))
                """);
        if (unowned != 0) problems.add(unowned + " live account row(s) reference no registry entry");
        report.add("every live account-owned row references the account registry");

        for (String table : List.of("account_bank_slot", "account_material_stack", "account_recipe_unlock",
                "account_character", "account_character_discipline", "account_character_recipe",
                "account_character_item", "account_source_state")) {
            long rows = rowCount(con, quote(table));
            if (rows != 0) {
                problems.add(table + " holds " + rows + " row(s), but no source other than Luck has"
                        + " provable ownership and nothing may be assigned speculatively");
            }
        }
        report.add("no unassociated legacy record is reachable through the live account tables");

        long fabricated = scalarLong(con,
                "SELECT count(*) FROM gw2_account WHERE last_seen_at IS NOT NULL OR last_successful_sync_at IS NOT NULL");
        if (fabricated != 0) problems.add(fabricated + " registry row(s) carry invented activity history");
        report.add("historical activity and source completion remain unknown rather than invented");

        verifyRuntimeCannotReadTheArchive(con, archive, manifest, problems, report);

        List<String> globalMissing = new ArrayList<>();
        for (LegacySchemaFingerprint.ExpectedTable table : LegacySchemaFingerprint.allObjects()) {
            if (table.scope() != LegacySchemaFingerprint.Scope.GLOBAL) continue;
            if (!SchemaGate.relationExists(con, table.name())) globalMissing.add(table.name());
        }
        if (!globalMissing.isEmpty()) problems.add("global table(s) missing after adoption: " + globalMissing);
        report.add("global tables remain shared and in place");

        if (!problems.isEmpty()) {
            con.rollback();
            throw new LegacyAdoptionBlockedException(
                    "Legacy adoption blocked cutover: integrity reconciliation failed for import "
                            + importId + ". The archive and ledger are preserved and nothing was deleted.",
                    problems);
        }
        return report;
    }

    /**
     * Verifies, before the import may be marked reconciled, that the live Luck destination holds
     * exactly what the archive proves and nothing else.
     *
     * <p>The association step itself refuses to overwrite a conflicting live row, but a cutover may
     * be interrupted and resumed, and between the two runs anything may have reached
     * {@code account_luck_total}. So the association's own verdict is not trusted here: every
     * associated account's live value <em>and</em> original fetch time are compared against the
     * archived legacy row, a live row no archived row explains is rejected, and an association
     * without a live row is rejected too. Any of these blocks the cutover, which rolls the whole
     * attempt back - leaving the live data, the archive and the ledger exactly as they were.
     */
    private static void verifyAssociatedLuckMatchesTheArchive(Connection con,
                                                              String archive,
                                                              List<ManifestEntry> manifest,
                                                              List<SourceOutcome> ledger,
                                                              List<String> problems,
                                                              List<String> report) throws SQLException {
        Set<String> associated = new LinkedHashSet<>();
        Set<String> explained = new LinkedHashSet<>();
        for (SourceOutcome entry : ledger) {
            if (!entry.source().equals("LUCK") || entry.accountId() == null) continue;
            if (ASSOCIATED.equals(entry.outcome())) associated.add(entry.accountId());
            if (CONFLICTED.equals(entry.outcome())) {
                explained.add(entry.accountId());
                problems.add("the live Luck destination for " + entry.accountId() + " conflicts with the"
                        + " archived legacy row, so the source is not associated: " + entry.evidence());
            }
        }
        explained.addAll(associated);

        boolean luckWasArchived = manifest.stream().anyMatch(entry -> entry.object().equals("account_luck"));
        String query = luckWasArchived
                ? "SELECT l.account_id::text, l.consumed_luck, l.fetched_at, a.consumed_luck, a.fetched_at"
                        + " FROM account_luck_total l LEFT JOIN " + quote(archive) + ".account_luck a"
                        + " ON lower(a.account_id) = l.account_id::text"
                : "SELECT l.account_id::text, l.consumed_luck, l.fetched_at,"
                        + " NULL::BIGINT, NULL::TIMESTAMPTZ FROM account_luck_total l";

        Set<String> unverified = new LinkedHashSet<>(associated);
        try (PreparedStatement ps = con.prepareStatement(query); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String account = rs.getString(1);
                long liveConsumed = rs.getLong(2);
                Instant liveFetchedAt = rs.getTimestamp(3).toInstant();
                Object archivedConsumed = rs.getObject(4);
                Timestamp archivedFetchedAt = rs.getTimestamp(5);
                if (!explained.contains(account)) {
                    problems.add("account_luck_total holds a live row for " + account + " (" + liveConsumed
                            + " luck fetched at " + liveFetchedAt + ") that this import did not associate,"
                            + " so nothing proves that ownership and the live row was left untouched");
                    continue;
                }
                if (!unverified.remove(account)) continue;
                if (archivedConsumed == null || archivedFetchedAt == null) {
                    problems.add("the live Luck row for " + account + " has no archived legacy row to"
                            + " verify it against");
                } else if (((Number) archivedConsumed).longValue() != liveConsumed
                        || !archivedFetchedAt.toInstant().equals(liveFetchedAt)) {
                    problems.add("the live Luck row for " + account + " holds " + liveConsumed
                            + " luck fetched at " + liveFetchedAt + ", but the archived legacy row holds "
                            + archivedConsumed + " fetched at " + archivedFetchedAt.toInstant()
                            + "; the archived value and original fetch time were not preserved");
                }
            }
        }
        for (String missing : unverified) {
            problems.add("Luck for " + missing + " is recorded as associated, but account_luck_total holds"
                    + " no live row for it");
        }
        report.add("every associated Luck row holds the archived value and the original fetch time ("
                + associated.size() + " account(s)), and no other live Luck row exists");
    }

    /**
     * Proves, before this cutover is allowed to finish, that the runtime access boundary really
     * denies the archive - rather than trusting that the grants and search paths add up to a denial.
     * The check runs the read a runtime connection would run, schema-qualified and as the restricted
     * role, inside a savepoint so the expected permission error does not disturb the adoption
     * transaction and the role switch is undone with it.
     */
    private static void verifyRuntimeCannotReadTheArchive(Connection con,
                                                          String archive,
                                                          List<ManifestEntry> manifest,
                                                          List<String> problems,
                                                          List<String> report) throws SQLException {
        String role = RuntimeAccessRole.roleNameFor(con.getSchema());
        if (!RuntimeAccessRole.exists(con, role)) {
            problems.add("the restricted runtime role " + role + " does not exist, so runtime"
                    + " connections would keep the archive owner's access to the preserved data");
            return;
        }
        String probe = manifest.isEmpty() ? "import_run" : manifest.getFirst().object();
        String qualified = quote(archive) + "." + quote(probe);

        Savepoint savepoint = con.setSavepoint("runtime_archive_denial_probe");
        try (Statement st = con.createStatement()) {
            st.execute("SET ROLE " + quote(role));
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + qualified)) {
                rs.next();
                problems.add("the restricted runtime role " + role + " can still read " + archive + "."
                        + probe + " (" + rs.getLong(1) + " rows), so archived account data would be"
                        + " reachable from runtime database access");
            } catch (SQLException denied) {
                con.rollback(savepoint);
                report.add("runtime database access is denied the archive: a schema-qualified read of "
                        + archive + "." + probe + " as " + role + " fails with [" + denied.getSQLState()
                        + "] " + firstLine(denied.getMessage()));
                return;
            }
        }
        con.rollback(savepoint);
    }

    private static String firstLine(String message) {
        if (message == null) return "no message";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * Records that this import passed provenance association and integrity reconciliation. It is
     * written in the same transaction as the reconciliation itself, so the marker and the guarantee
     * it stands for commit together, and startup refuses to serve the database until it is set.
     */
    private static void markReconciled(Connection con, String archive, UUID importId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE " + quote(archive)
                + ".import_run SET reconciled_at = now() WHERE import_id = ? AND reconciled_at IS NULL")) {
            ps.setObject(1, importId);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- resume support

    private static boolean preservationAlreadyCommitted(Connection con, String archive) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                               WHERE table_schema = ? AND table_name = 'import_run')
                """)) {
            ps.setString(1, archive);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static UUID readImportId(Connection con, String archive) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT import_id FROM " + quote(archive) + ".import_run ORDER BY preserved_at LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SchemaGateException("The archive schema " + archive + " has an import_run table"
                        + " but no import; this database needs manual review before adoption continues");
            }
            return rs.getObject(1, UUID.class);
        }
    }

    private static List<ManifestEntry> readManifest(Connection con, String archive, UUID importId) throws SQLException {
        List<ManifestEntry> manifest = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT object_name, row_count, content_checksum, oldest_fetched_at, newest_fetched_at,
                       ambiguous_row_count, invalid_row_count
                FROM %s.import_manifest WHERE import_id = ? ORDER BY object_name
                """.formatted(quote(archive)))) {
            ps.setObject(1, importId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp oldest = rs.getTimestamp(4);
                    Timestamp newest = rs.getTimestamp(5);
                    manifest.add(new ManifestEntry(rs.getString(1), rs.getLong(2), rs.getString(3),
                            oldest == null ? null : oldest.toInstant(),
                            newest == null ? null : newest.toInstant(),
                            rs.getLong(6), rs.getLong(7)));
                }
            }
        }
        return manifest;
    }

    private static LegacySchemaFingerprint.Inspection readInspectionFromLedger(Connection con,
                                                                              String archive,
                                                                              UUID importId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT fingerprint_id, variants, absent_optional_objects
                FROM %s.import_run WHERE import_id = ?
                """.formatted(quote(archive)))) {
            ps.setObject(1, importId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new LegacySchemaFingerprint.Inspection(rs.getString(1),
                        splitList(rs.getString(2)), splitList(rs.getString(3)), List.of());
            }
        }
    }

    private static List<String> splitList(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    // ---------------------------------------------------------------- SQL helpers

    /**
     * Derived from the source inventory rather than generated, so a retried adoption carries the same
     * import ID and its manifest/ledger writes are a no-op instead of a second import.
     */
    private static UUID deriveImportId(LegacySchemaFingerprint.Inspection inspection,
                                       List<ManifestEntry> manifest) {
        StringBuilder material = new StringBuilder(inspection.fingerprintId());
        manifest.stream().sorted((a, b) -> a.object().compareTo(b.object())).forEach(entry ->
                material.append('|').append(entry.object()).append(':').append(entry.rowCount())
                        .append(':').append(entry.contentChecksum()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new SchemaGateException("SHA-256 is unavailable, so no stable import ID can be derived", e);
        }
    }

    /** A content digest that two sessions agree on: every instant renders in UTC (see {@link #utc}). */
    private static String checksum(Connection con, String qualifiedTable) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT md5(coalesce(string_agg(rendered, ',' ORDER BY rendered), '')) FROM "
                        + "(SELECT legacy_row::text AS rendered FROM " + qualifiedTable + " legacy_row) rendered_rows");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long rowCount(Connection con, String qualifiedTable) throws SQLException {
        return scalarLong(con, "SELECT count(*) FROM " + qualifiedTable);
    }

    private static long scalarLong(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Instant instantOf(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            Timestamp value = rs.getTimestamp(1);
            return value == null ? null : value.toInstant();
        }
    }

    private static List<String> relations(Connection con, String schema) throws SQLException {
        List<String> relations = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = coalesce(?::text, current_schema()) AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                """)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) relations.add(rs.getString(1));
            }
        }
        return relations;
    }

    private static void utc(Connection con) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("SET TIME ZONE 'UTC'");
        }
    }

    private static boolean tryLock(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, ADOPTION_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection con) {
        try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, ADOPTION_LOCK_KEY);
            ps.executeQuery().close();
        } catch (SQLException ignored) {
            // The lock is session-scoped; closing the connection releases it either way.
        }
    }

    /** Quotes an identifier this class composes into DDL, which PreparedStatement cannot parameterize. */
    private static String quote(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new SchemaGateException("Refusing to use an unexpected identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
