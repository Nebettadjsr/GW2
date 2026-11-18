package application;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountSourcePublication;
import repo.AccountSourceStateRepository;
import repo.AccountSourceStateRepository.SourceState;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criterion 4, database half, through the production boundaries: a real
 * {@link ProfitInputSession#open(AccountScope)} snapshot, the real {@link AccountSourcePublication}
 * writer and the real {@link AccountSourceStateRepository} marker, on the production Flyway chain in
 * a disposable schema. The source is {@code BANK}; its rows are {@code account_bank} item ids.
 *
 * <p>Characterised policy: {@code open(scope)} records the authorized use <em>before</em> it pins the
 * snapshot, so it may wait for a registry write. Every session here is therefore opened before a
 * writer begins, and its first snapshot read happens while the writer is held in its callback.
 *
 * <p>Mapping of the earlier probe-table assertions: pinned generation across a later commit
 * ({@code aPinnedSessionKeepsOneWholeGeneration...}), in-flight refresh invisible
 * ({@code aFirstReadDuringAnInFlightPublication...}), failed refresh keeps the prior generation
 * ({@code aFailedPublication...}), successful empty is a version ({@code aSuccessfulEmpty...}).
 *
 * <p>{@link Db#TEST_SCHEMA_PROPERTY} points only at this test's own schema and is cleared afterwards.
 */
class ProfitInputPublicationTest {

    private static final AccountScope SCOPE = AccountScope.of("10000000-0000-0000-0000-000000000001");
    private static final String SOURCE = "BANK";
    private static final Instant T1 = Instant.parse("2026-10-12T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-12T10:00:00Z");

    private final String schema = "test_profit_publication_" + UUID.randomUUID().toString().replace("-", "");
    private final AccountSourceStateRepository states = new AccountSourceStateRepository();
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
        publish(T1, List.of(1, 2), null);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (admin == null) return;
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void aPinnedSessionKeepsOneWholeGenerationWhileAnotherPublicationCommits() throws Exception {
        try (ProfitInputSession pinned = ProfitInputSession.open(SCOPE)) {
            assertEquals(1L, marker(pinned).generation());
            assertEquals(List.of(1, 2), rows(pinned));

            publish(T2, List.of(7, 8, 9), null);

            SourceState after = marker(pinned);
            assertEquals(1L, after.generation(), "the pinned view must not jump to a later generation");
            assertEquals(T1, after.completedAt());
            assertEquals(List.of(1, 2), rows(pinned), "nor read newer rows under the older marker");
        }

        try (ProfitInputSession next = ProfitInputSession.open(SCOPE)) {
            assertEquals(2L, marker(next).generation(), "a later session captures the new generation whole");
            assertEquals(T2, marker(next).completedAt());
            assertEquals(List.of(7, 8, 9), rows(next));
        }
    }

    @Test
    void aFirstReadDuringAnInFlightPublicationSeesTheWholeOldGenerationThenTheWholeNewOne() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ProfitInputSession early = ProfitInputSession.open(SCOPE);
             ProfitInputSession late = ProfitInputSession.open(SCOPE)) {
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try {
                    publish(T2, List.of(7, 8, 9), () -> {
                        written.countDown();
                        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
                    });
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            try {
                assertTrue(written.await(30, TimeUnit.SECONDS), "the writer reached its held callback");

                // Neither session has read yet, so their snapshots start here, mid-publication.
                assertEquals(1L, marker(early).generation(), "the in-flight generation has no marker yet");
                assertEquals(List.of(1, 2), rows(early), "and none of its rows are visible");
            } finally {
                release.countDown();
            }
            writer.get(30, TimeUnit.SECONDS);

            assertEquals(1L, marker(early).generation(), "the snapshot taken mid-publication stays old");
            assertEquals(List.of(1, 2), rows(early));
            assertEquals(2L, marker(late).generation(), "a snapshot first read after commit sees the new one");
            assertEquals(List.of(7, 8, 9), rows(late));
        }
    }

    @Test
    void aFailedPublicationKeepsTheCompletePriorGenerationAndItsMarker() throws Exception {
        assertThrows(IllegalStateException.class, () -> publish(T2, List.of(7, 8), () -> {
            throw new IllegalStateException("upstream failed after rows were written");
        }));

        try (ProfitInputSession session = ProfitInputSession.open(SCOPE)) {
            SourceState state = marker(session);
            assertEquals(1L, state.generation(), "a failed publication must not advance the generation");
            assertEquals(T1, state.completedAt());
            assertEquals(List.of(1, 2), rows(session), "and must leave none of its rows behind");
        }
    }

    @Test
    void aSuccessfulEmptyPublicationIsAPublishedGeneration() throws Exception {
        publish(T2, List.of(), null);

        try (ProfitInputSession session = ProfitInputSession.open(SCOPE)) {
            SourceState state = marker(session);
            assertEquals(2L, state.generation(), "synchronized emptiness is a version, not an absent source");
            assertEquals(T2, state.completedAt());
            assertEquals(List.of(), rows(session));
        }
    }

    // ---------- fixtures ----------

    @FunctionalInterface
    private interface Hold {
        void run() throws Exception;
    }

    /** Publishes BANK through the production publication; {@code hold} runs after the rows are written. */
    private void publish(Instant completion, List<Integer> itemIds, Hold hold) throws Exception {
        try (Connection writer = connect()) {
            writer.setSchema(schema);
            new AccountSourcePublication(Clock.fixed(completion, ZoneOffset.UTC))
                    .publish(writer, SCOPE, SOURCE, (con, scope, runTs) -> {
                        try (PreparedStatement delete = con.prepareStatement(
                                "DELETE FROM account_bank WHERE account_id = ?::uuid")) {
                            delete.setString(1, scope.accountId().toString());
                            delete.executeUpdate();
                        }
                        try (PreparedStatement insert = con.prepareStatement(
                                "INSERT INTO account_bank (account_id, slot, item_id, count, fetched_at)"
                                        + " VALUES (?::uuid, ?, ?, 1, ?)")) {
                            int slot = 0;
                            for (int itemId : itemIds) {
                                insert.setString(1, scope.accountId().toString());
                                insert.setInt(2, slot++);
                                insert.setInt(3, itemId);
                                insert.setTimestamp(4, runTs);
                                insert.addBatch();
                            }
                            insert.executeBatch();
                        }
                        if (hold != null) hold.run();
                    });
        }
    }

    private SourceState marker(ProfitInputSession session) throws Exception {
        return states.read(session.connection(), session.scope(), SOURCE).orElseThrow();
    }

    private static List<Integer> rows(ProfitInputSession session) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = session.connection().prepareStatement(
                "SELECT item_id FROM account_bank WHERE account_id = ?::uuid ORDER BY slot")) {
            ps.setString(1, session.scope().accountId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
    }
}
