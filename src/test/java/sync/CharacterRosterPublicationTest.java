package sync;

import application.CalculationInputVersions;
import application.ProfitInputSession;
import model.CharacterCraftingRow;
import model.CharacterInfo;
import model.CharacterItemRow;
import model.CharacterRecipeRow;
import model.AccountScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import repo.CharacterRepository;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * STORY-APP-014 acceptance criterion 4 through the paths that actually run in production: the
 * roster refresh publishes with {@link CharacterSync#publishRoster}, and the calculation reads with
 * {@link ProfitInputSession}, {@code CalculationInputVersions}' marker reader and
 * {@link CharacterRepository}'s connection-taking query.
 *
 * <p>{@code application.ProfitInputPublicationTest} proves the same invariants about the database's
 * snapshot semantics using a stand-in table and hand-written statements. That cannot show that the
 * code which ships obeys them, which is what this suite is for. Three claims:
 *
 * <ul>
 *   <li>A refresh that is <em>mid-write</em> — its first characters already written, its transaction
 *       not yet committed — is invisible to a capture that starts meanwhile: that capture reads the
 *       whole previous roster under the previous successful marker, never half of each.</li>
 *   <li>A refresh that fails part-way leaves the complete previous roster and its successful marker
 *       readable, and leaves none of its own rows behind.</li>
 *   <li>One capture cannot mix generations: a whole generation publishing between two of its reads
 *       changes neither of them, and the next capture sees that new generation whole.</li>
 * </ul>
 *
 * <p>The mid-write moment is held open deterministically rather than raced for: a trigger on the
 * last character of the new generation waits on an advisory lock this test holds, and the test waits
 * until PostgreSQL reports that writer's backend as waiting on it before reading anything. No sleep
 * and no timing assumption is involved.
 *
 * <p>Runs in a disposable, uniquely named schema that {@link Db#TEST_SCHEMA_PROPERTY} points every
 * {@code Db.open()} at for the duration of the test — including the ones inside the production
 * session — so the developer's own database is never read or written. The payloads are
 * already-parsed model records, so {@code fetchCharacterNames}/{@code fetchCharacterDetails} and the
 * GW2 API are never reached.
 */
class CharacterRosterPublicationTest {

    private static final AccountScope ACCOUNT = AccountScope.of("10000000-0000-0000-0000-000000000001");

    private static final Instant GENERATION_ONE = Instant.parse("2026-10-12T09:00:00Z");
    private static final Instant GENERATION_TWO = Instant.parse("2026-10-12T10:00:00Z");

    /** The character whose insert waits on this test's advisory lock, mid-transaction. */
    private static final String HELD_CHARACTER = "Gate Keeper";

    private final String schema = "test_roster_publication_" + UUID.randomUUID().toString().replace("-", "");
    private final long lockKey = (long) (Math.random() * 1_000_000_000L) + 1_000_000_000L;

    private Connection admin;
    private Connection lockHolder;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        admin.setSchema(schema);
        AccountSyncStateRepository.ensure(admin);

        try (Statement statement = admin.createStatement()) {
            statement.execute("""
                CREATE TABLE characters (
                    character_id  BIGSERIAL PRIMARY KEY,
                    name          TEXT NOT NULL UNIQUE,
                    profession    TEXT,
                    race          TEXT,
                    gender        TEXT,
                    level         INTEGER,
                    created_at_gw TIMESTAMPTZ,
                    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
            statement.execute("""
                CREATE TABLE character_crafting (
                    character_id BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    discipline   TEXT NOT NULL,
                    rating       INTEGER NOT NULL DEFAULT 0,
                    is_active    BOOLEAN NOT NULL DEFAULT false,
                    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (character_id, discipline)
                )
                """);
            statement.execute("""
                CREATE TABLE character_recipes (
                    character_id BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    recipe_id    INTEGER NOT NULL,
                    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (character_id, recipe_id)
                )
                """);
            statement.execute("""
                CREATE TABLE character_items (
                    character_id   BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    location       TEXT NOT NULL,
                    bag_index      INTEGER,
                    slot_index     INTEGER,
                    equipment_slot TEXT,
                    item_id        INTEGER NOT NULL,
                    count          INTEGER NOT NULL DEFAULT 1,
                    binding        TEXT,
                    bound_to       TEXT,
                    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_character_items_slot UNIQUE
                        (character_id, location, bag_index, slot_index, equipment_slot)
                )
                """);
        }

        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);

        publish(GENERATION_ONE, List.of(character("Scribe Ana", 400), character("Scribe Bo", 300)));
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (lockHolder != null) lockHolder.close();
        if (admin == null) return;
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void aCaptureStartedWhileARefreshIsMidWriteReadsTheWholePreviousGeneration() throws Exception {
        installLockingTriggerOnInsertOf(HELD_CHARACTER);
        holdLock();

        AtomicReference<Exception> failure = new AtomicReference<>();
        Connection writer = connect();
        writer.setSchema(schema);
        int writerPid = backendPidOf(writer);

        // The new generation changes Ana's rating and adds a third character. Its last insert waits
        // on this test's lock, so by the time the capture below runs, the refresh has written real
        // rows and has neither committed them nor advanced the marker.
        List<CharacterSync.CharPayload> generationTwo = List.of(
                character("Scribe Ana", 450),
                character("Scribe Bo", 300),
                character(HELD_CHARACTER, 500));
        Thread refresh = new Thread(() -> {
            try {
                CharacterSync.publishRoster(writer, ACCOUNT, generationTwo, GENERATION_TWO);
            } catch (Exception thrown) {
                failure.set(thrown);
            }
        }, "roster-refresh");
        refresh.start();
        awaitWaitingOnTheLock(writerPid);

        try (ProfitInputSession session = ProfitInputSession.open(ACCOUNT)) {
            assertEquals(GENERATION_ONE, capturedMarker(session),
                    "a mid-write refresh must not advance the marker a calculation reads");
            assertEquals(Map.of("Scribe Ana", 400, "Scribe Bo", 300), capturedRatings(session),
                    "and none of its written rows may be visible under that marker");
        }

        releaseLock();
        refresh.join(15_000);
        writer.close();
        assertNull(failure.get(), "the released refresh must publish normally");

        try (ProfitInputSession session = ProfitInputSession.open(ACCOUNT)) {
            assertEquals(GENERATION_TWO, capturedMarker(session),
                    "once it commits, the data and its version appear together");
            assertEquals(Map.of("Scribe Ana", 450, "Scribe Bo", 300, HELD_CHARACTER, 500),
                    capturedRatings(session));
        }
    }

    @Test
    void aRefreshThatFailsPartWayLeavesThePreviousGenerationAndItsMarkerIntact() throws Exception {
        // The second payload's crafting row has no discipline, which the table forbids: the failure
        // lands after the first character's rows were already written.
        List<CharacterSync.CharPayload> doomed = List.of(
                character("Scribe Ana", 450),
                new CharacterSync.CharPayload(
                        new CharacterInfo("Scribe Cy", "Ranger", "Sylvari", "Female", 80, null),
                        List.of(new CharacterCraftingRow(null, 200, true)),
                        List.of(new CharacterRecipeRow(99)),
                        List.of()));

        try (Connection writer = connect()) {
            writer.setSchema(schema);
            assertThrows(Exception.class,
                    () -> CharacterSync.publishRoster(writer, doomed, GENERATION_TWO),
                    "the publish must fail rather than commit half a generation");
        }

        try (ProfitInputSession session = ProfitInputSession.open(ACCOUNT)) {
            assertEquals(GENERATION_ONE, capturedMarker(session),
                    "a failed refresh must not advance the completion marker");
            assertEquals(Map.of("Scribe Ana", 400, "Scribe Bo", 300), capturedRatings(session),
                    "and must leave the complete previous roster readable");
        }
        assertEquals(List.of("Scribe Ana", "Scribe Bo"), characterNames(),
                "nor may it leave any of its own rows behind");
    }

    @Test
    void oneCaptureCannotMixGenerationsNoMatterWhatPublishesWhileItReads() throws Exception {
        try (ProfitInputSession session = ProfitInputSession.open(ACCOUNT)) {
            assertEquals(GENERATION_ONE, capturedMarker(session));
            assertEquals(Map.of("Scribe Ana", 400, "Scribe Bo", 300), capturedRatings(session));

            publish(GENERATION_TWO, List.of(character("Scribe Ana", 450), character("Scribe Bo", 350)));

            assertEquals(GENERATION_ONE, capturedMarker(session),
                    "a capture must not jump to a version published after it started");
            assertEquals(Map.of("Scribe Ana", 400, "Scribe Bo", 300), capturedRatings(session),
                    "nor read the newer rows under the older marker");
        }

        try (ProfitInputSession session = ProfitInputSession.open(ACCOUNT)) {
            assertEquals(GENERATION_TWO, capturedMarker(session));
            assertEquals(Map.of("Scribe Ana", 450, "Scribe Bo", 350), capturedRatings(session));
        }
    }

    // ---------- the production capture path ----------

    private static Instant capturedMarker(ProfitInputSession session) throws Exception {
        Map<String, Instant> versions = new CalculationInputVersions.DatabaseAccountSourceVersionReader()
                .read(session.connection(), Set.of(AccountSyncStateRepository.CHARACTERS));
        return versions.get(AccountSyncStateRepository.CHARACTERS);
    }

    private static Map<String, Integer> capturedRatings(ProfitInputSession session) throws Exception {
        Map<String, Integer> ratings = new LinkedHashMap<>();
        for (CharacterRepository.DiscRow row
                : new CharacterRepository().loadAllCharacterCrafting(session.connection())) {
            ratings.put(row.charName, row.rating);
        }
        return ratings;
    }

    // ---------- fixtures ----------

    /** Publishes one whole generation through the production write path. */
    private void publish(Instant version, List<CharacterSync.CharPayload> payloads) throws Exception {
        try (Connection writer = connect()) {
            writer.setSchema(schema);
            CharacterSync.publishRoster(writer, ACCOUNT, payloads, version);
        }
    }

    private static CharacterSync.CharPayload character(String name, int rating) {
        return new CharacterSync.CharPayload(
                new CharacterInfo(name, "Engineer", "Norn", "Male", 80, null),
                List.of(new CharacterCraftingRow("Chef", rating, true)),
                List.of(new CharacterRecipeRow(rating)),
                List.of(new CharacterItemRow("BAG", 0, 0, null, 1000 + rating, 1, null, null)));
    }

    /**
     * Makes the named character's insert wait, inside the refresh's own transaction, until this test
     * releases the advisory lock it holds.
     */
    private void installLockingTriggerOnInsertOf(String name) throws Exception {
        try (Statement statement = admin.createStatement()) {
            statement.execute("""
                CREATE FUNCTION %s.hold_roster_write() RETURNS trigger AS $$
                BEGIN
                  PERFORM pg_advisory_xact_lock(%d);
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(schema, lockKey));
            statement.execute("""
                CREATE TRIGGER hold_roster_write BEFORE INSERT ON characters
                FOR EACH ROW WHEN (NEW.name = '%s')
                EXECUTE FUNCTION %s.hold_roster_write()
                """.formatted(name, schema));
        }
    }

    private void holdLock() throws Exception {
        lockHolder = connect();
        try (PreparedStatement ps = lockHolder.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the advisory lock was not taken");
            }
        }
    }

    private void releaseLock() throws Exception {
        try (PreparedStatement ps = lockHolder.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1), "the advisory lock was not released");
            }
        }
    }

    /** Waits until PostgreSQL reports the refresh's backend as waiting on that advisory lock. */
    private void awaitWaitingOnTheLock(int pid) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (PreparedStatement ps = admin.prepareStatement(
                    "SELECT wait_event_type, wait_event FROM pg_stat_activity WHERE pid = ?")) {
                ps.setInt(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && "Lock".equals(rs.getString(1)) && "advisory".equals(rs.getString(2))) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        fail("the refresh never reached the held write (backend " + pid + " is not waiting on the lock)");
    }

    private static int backendPidOf(Connection con) throws Exception {
        try (Statement statement = con.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private List<String> characterNames() throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = admin.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM characters ORDER BY name")) {
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
