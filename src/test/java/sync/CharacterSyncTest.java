package sync;

import model.CharacterCraftingRow;
import model.CharacterInfo;
import model.CharacterItemRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for
 * {@code CharacterSync}'s database-write logic: {@code upsertCharacter},
 * {@code replaceCharacterCrafting}, and {@code replaceCharacterItems}. These methods have only
 * ever run against the developer's real database via the live application; this is their first
 * automated coverage.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following the
 * same pattern as {@code InventoryRepositoryOwnedInventoryTest} (STORY-TEST-002). Seeded rows are
 * already-parsed model records ({@code CharacterInfo}/{@code CharacterCraftingRow}/
 * {@code CharacterItemRow}) constructed directly in the test - no live GW2 API call is made, and
 * {@code fetchCharacterNames}/{@code fetchCharacterDetails} are never exercised.
 */
class CharacterSyncTest {

    private final String schema = "test_charsync_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(
                requireEnv("DATABASE_URL"),
                requireEnv("DATABASE_USER"),
                requireEnv("DATABASE_PASSWORD"));

        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);

        try (Statement st = con.createStatement()) {
            st.execute("""
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
            st.execute("""
                CREATE TABLE character_crafting (
                    character_id  BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    discipline    TEXT   NOT NULL,
                    rating        INTEGER NOT NULL DEFAULT 0,
                    is_active     BOOLEAN NOT NULL DEFAULT false,
                    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (character_id, discipline)
                )
                """);
            st.execute("""
                CREATE TABLE character_items (
                    character_id    BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    location        TEXT   NOT NULL,
                    bag_index       INTEGER,
                    slot_index      INTEGER,
                    equipment_slot  TEXT,
                    item_id         INTEGER NOT NULL,
                    count           INTEGER NOT NULL DEFAULT 1,
                    binding         TEXT,
                    bound_to        TEXT,
                    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_character_items_slot UNIQUE
                        (character_id, location, bag_index, slot_index, equipment_slot)
                )
                """);
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    /**
     * Reads DB connection config the same way {@code repo.EnvConfig} does (real env var, falling
     * back to a ".env" file in the project root) - duplicated here in test-only code rather than
     * making the package-private {@code repo.EnvConfig} accessible from this test's {@code sync}
     * package, so this story's only production change stays the {@code CharacterSync} visibility
     * bump.
     */
    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value;

        Path path = Path.of(".env");
        if (Files.exists(path)) {
            try {
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) continue;
                    if (!trimmed.substring(0, eq).trim().equals(key)) continue;
                    String v = trimmed.substring(eq + 1).trim();
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    if (!v.isBlank()) return v;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read .env file: " + path.toAbsolutePath(), e);
            }
        }

        throw new IllegalStateException("Missing required configuration value: " + key +
                ". Set it as an environment variable, or add it to a .env file in the project root " +
                "(see .env.example).");
    }

    @Test
    void upsertCharacter_insertsThenUpdatesTheSameRowOnConflictingName() throws Exception {
        CharacterInfo initial = new CharacterInfo("Test Hero", "Engineer", "Norn", "Male", 10, null);
        long characterId = CharacterSync.upsertCharacter(con, initial);

        CharacterInfo updated = new CharacterInfo("Test Hero", "Engineer", "Norn", "Male", 42, null);
        long sameCharacterId = CharacterSync.upsertCharacter(con, updated);

        assertEquals(characterId, sameCharacterId);

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), MAX(level) FROM characters WHERE character_id = " + characterId)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(42, rs.getInt(2));
        }
    }

    @Test
    void replaceCharacterItems_insertsUpdatesOnConflictAndDeletesStaleRows() throws Exception {
        long characterId = CharacterSync.upsertCharacter(con,
                new CharacterInfo("Item Hero", "Warrior", "Human", "Female", 80, null));

        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        // Initial sync run: two bag slots.
        CharacterItemRow refreshedSlot = new CharacterItemRow("BAG", 0, 0, null, 111, 5, null, null);
        CharacterItemRow droppedSlot = new CharacterItemRow("BAG", 0, 1, null, 222, 1, null, null);
        CharacterSync.replaceCharacterItems(con, characterId, List.of(refreshedSlot, droppedSlot), ts(t1));

        // (a) Initial insert landed correctly.
        assertEquals(5, itemCount(characterId, 0, 0));
        assertEquals(1, itemCount(characterId, 0, 1));
        assertEquals(2, itemRowCount(characterId));

        // Second sync run: the item in slot (0,0) is still present but its stack count changed
        // (an overlapping key), while slot (0,1)'s item is no longer in the character's bags -
        // i.e. not returned by this run at all - so it must be cleaned up as stale.
        CharacterItemRow refreshedSlotUpdated = new CharacterItemRow("BAG", 0, 0, null, 111, 9, null, null);
        CharacterSync.replaceCharacterItems(con, characterId, List.of(refreshedSlotUpdated), ts(t2));

        // (b) Overlapping key updated the existing row rather than duplicating it.
        assertEquals(1, slotRowCount(characterId, 0, 0));
        assertEquals(9, itemCount(characterId, 0, 0));

        // (c) The row from the previous run that was not refreshed this run (fetched_at = t1,
        // which predates this run's t2) was deleted by the stale-row cleanup, while the row
        // refreshed this run (fetched_at = t2) survives.
        assertEquals(0, slotRowCount(characterId, 0, 1));
        assertEquals(1, itemRowCount(characterId));
    }

    @Test
    void replaceCharacterCrafting_insertsUpdatesOnConflictAndDeletesStaleRows() throws Exception {
        long characterId = CharacterSync.upsertCharacter(con,
                new CharacterInfo("Craft Hero", "Elementalist", "Asura", "Male", 80, null));

        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        // Initial sync run: two active disciplines.
        CharacterCraftingRow chef = new CharacterCraftingRow("Chef", 400, true);
        CharacterCraftingRow tailor = new CharacterCraftingRow("Tailor", 500, false);
        CharacterSync.replaceCharacterCrafting(con, characterId, List.of(chef, tailor), ts(t1));

        // (a) Initial insert landed correctly.
        assertEquals(400, craftingRating(characterId, "Chef"));
        assertEquals(500, craftingRating(characterId, "Tailor"));

        // Second sync run: Chef leveled up (overlapping key), Tailor is no longer reported
        // (e.g. the character unlearned/swapped it) so it's absent from this run's rows.
        CharacterCraftingRow chefLeveledUp = new CharacterCraftingRow("Chef", 450, true);
        CharacterSync.replaceCharacterCrafting(con, characterId, List.of(chefLeveledUp), ts(t2));

        // (b) Overlapping key updated the existing row rather than duplicating it.
        assertEquals(1, disciplineRowCount(characterId, "Chef"));
        assertEquals(450, craftingRating(characterId, "Chef"));

        // (c) Tailor's row (fetched_at = t1, predating this run's t2) was deleted by the
        // stale-row cleanup; Chef's row (fetched_at = t2) survives.
        assertEquals(0, disciplineRowCount(characterId, "Tailor"));
    }

    private int itemCount(long characterId, int bagIndex, int slotIndex) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count FROM character_items WHERE character_id = " + characterId +
                             " AND bag_index = " + bagIndex + " AND slot_index = " + slotIndex)) {
            assertTrue(rs.next());
            return rs.getInt("count");
        }
    }

    private int slotRowCount(long characterId, int bagIndex, int slotIndex) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM character_items WHERE character_id = " + characterId +
                             " AND bag_index = " + bagIndex + " AND slot_index = " + slotIndex)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int itemRowCount(long characterId) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM character_items WHERE character_id = " + characterId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int craftingRating(long characterId, String discipline) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rating FROM character_crafting WHERE character_id = " + characterId +
                             " AND discipline = '" + discipline + "'")) {
            assertTrue(rs.next());
            return rs.getInt("rating");
        }
    }

    private int disciplineRowCount(long characterId, String discipline) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM character_crafting WHERE character_id = " + characterId +
                             " AND discipline = '" + discipline + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
