package application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criterion 4, database half: the publication protocol the captured read
 * view relies on, verified against a real PostgreSQL in a disposable schema.
 *
 * <p>Two things are proved here, because the mechanism is only as good as the database's part of it:
 *
 * <ul>
 *   <li>A reader that captures its inputs the way {@link ProfitInputSession} does — one
 *       {@code REPEATABLE READ} transaction for the markers <em>and</em> the data — cannot observe a
 *       refresh's part-written rows next to the previous run's completion marker, and cannot mix
 *       generations between its own reads, no matter what commits while it reads.</li>
 *   <li>A refresh that fails part-way leaves the whole previous generation and its marker readable,
 *       because a source's data and its marker are written in one transaction. This is the
 *       behaviour {@code sync.CharacterSync} was changed to have: it used to commit per character
 *       and mark afterwards, so a concurrent reader could see some characters replaced while the
 *       marker still named the older run.</li>
 * </ul>
 *
 * <p>The {@code probe_rows} table stands in for any one source's rows; the invariant is about the
 * transaction boundary, not about which columns a source has. Nothing here touches the developer's
 * own schema: every statement runs inside a schema created and dropped by this test.
 */
class ProfitInputPublicationTest {

    private static final String SOURCE = AccountSyncStateRepository.CHARACTERS;
    private static final Instant GENERATION_ONE = Instant.parse("2026-10-12T09:00:00Z");
    private static final Instant GENERATION_TWO = Instant.parse("2026-10-12T10:00:00Z");

    private final String schema = "test_profit_publication_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        admin.setSchema(schema);
        AccountSyncStateRepository.ensure(admin);
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE probe_rows (name text PRIMARY KEY)");
        }
        publishGeneration(GENERATION_ONE, List.of("first-a", "first-b"), true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin == null) return;
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void aCapturedReadViewNeverPairsPartlyWrittenRowsWithAnOlderSuccessfulMarker() throws Exception {
        try (Connection reader = captureSession()) {
            // First read establishes the snapshot, exactly as a calculation's first input read does.
            assertEquals(GENERATION_ONE, markerOn(reader).orElseThrow());
            assertEquals(List.of("first-a", "first-b"), rowsOn(reader));

            // A whole new generation is published while the reader is still reading.
            publishGeneration(GENERATION_TWO, List.of("second-a", "second-b", "second-c"), true);

            assertEquals(GENERATION_ONE, markerOn(reader).orElseThrow(),
                    "the captured view must not jump to a version published after it started");
            assertEquals(List.of("first-a", "first-b"), rowsOn(reader),
                    "and must not read the newer rows under the older marker either");
        }

        try (Connection next = captureSession()) {
            assertEquals(GENERATION_TWO, markerOn(next).orElseThrow(),
                    "a request that starts afterwards captures the new generation whole");
            assertEquals(List.of("second-a", "second-b", "second-c"), rowsOn(next));
        }
    }

    @Test
    void aReaderThatStartsMidRefreshSeesTheWholeOldGenerationOrTheWholeNewOne() throws Exception {
        // The refresh has written its rows and its marker but has not committed: nothing is
        // published yet, so a reader starting now must see the previous generation complete.
        try (Connection refresher = connect()) {
            refresher.setSchema(schema);
            refresher.setAutoCommit(false);
            replaceRows(refresher, List.of("uncommitted-a"));
            AccountSyncStateRepository.mark(refresher, SOURCE, GENERATION_TWO);

            try (Connection reader = captureSession()) {
                assertEquals(GENERATION_ONE, markerOn(reader).orElseThrow());
                assertEquals(List.of("first-a", "first-b"), rowsOn(reader),
                        "an in-flight refresh must be invisible until it publishes");
            }

            refresher.commit();
        }

        try (Connection reader = captureSession()) {
            assertEquals(GENERATION_TWO, markerOn(reader).orElseThrow());
            assertEquals(List.of("uncommitted-a"), rowsOn(reader),
                    "once published, the data and its version appear together");
        }
    }

    @Test
    void aFailedRefreshLeavesTheCompletePriorGenerationAndItsMarkerIntact() throws Exception {
        publishGeneration(GENERATION_TWO, List.of("doomed-a", "doomed-b"), false);

        try (Connection reader = captureSession()) {
            assertEquals(GENERATION_ONE, markerOn(reader).orElseThrow(),
                    "a failed refresh must not advance the completion marker");
            assertEquals(List.of("first-a", "first-b"), rowsOn(reader),
                    "and must not leave any of its rows behind");
        }
    }

    @Test
    void aSourceThatSynchronizedSuccessfullyWithNoRowsIsStillAPublishedVersion() throws Exception {
        publishGeneration(GENERATION_TWO, List.of(), true);

        try (Connection reader = captureSession()) {
            assertEquals(GENERATION_TWO, markerOn(reader).orElseThrow(),
                    "synchronized emptiness is a version, not an absent source");
            assertEquals(List.of(), rowsOn(reader));
            assertTrue(markerOn(reader).isPresent());
        }
    }

    // ---------- fixtures ----------

    /**
     * Writes one source's rows and its completion marker in a single transaction, as every account
     * source's refresh does, and either publishes it or rolls the whole generation back.
     */
    private void publishGeneration(Instant version, List<String> rows, boolean commit) throws Exception {
        try (Connection writer = connect()) {
            writer.setSchema(schema);
            writer.setAutoCommit(false);
            try {
                replaceRows(writer, rows);
                AccountSyncStateRepository.mark(writer, SOURCE, version);
                if (commit) writer.commit();
                else writer.rollback();
            } catch (Exception failure) {
                writer.rollback();
                throw failure;
            }
        }
    }

    private static void replaceRows(Connection con, List<String> rows) throws Exception {
        try (Statement statement = con.createStatement()) {
            statement.executeUpdate("DELETE FROM probe_rows");
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO probe_rows(name) VALUES (?)")) {
            for (String row : rows) {
                ps.setString(1, row);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** A connection configured the way {@link ProfitInputSession#open()} configures its own. */
    private Connection captureSession() throws Exception {
        Connection con = connect();
        con.setSchema(schema);
        con.setAutoCommit(false);
        con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        return con;
    }

    private static Optional<Instant> markerOn(Connection con) throws Exception {
        return new AccountSyncStateRepository().lookup(con, SOURCE);
    }

    private static List<String> rowsOn(Connection con) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = con.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM probe_rows ORDER BY name")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
    }
}
