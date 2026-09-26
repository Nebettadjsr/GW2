package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for the material-storage read
 * relocated out of {@code MaterialsView} by STORY-APP-009: the {@code count IS NOT NULL AND
 * count > 0} filter, the {@code category, item_id} ordering, the {@code items} LEFT JOIN's
 * icon/rarity mapping and the empty-table case must behave exactly as the view's own inline query
 * did. Also verifies that the no-argument entry point goes through the shared {@link Db#open()}
 * configuration path rather than any view-local credentials.
 *
 * <p>Runs against a real local PostgreSQL server using a disposable, uniquely-named schema created
 * in {@link #createIsolatedSchema()} and dropped in {@link #dropIsolatedSchema()}, so the
 * developer's normal database is never read from or written to (same pattern as
 * {@link InventoryRepositoryOwnedInventoryTest}).
 */
class MaterialStorageRepositoryTest {

    private final String schema = "test_mats_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));

        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);

        try (Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE account_materials (
                    item_id     INTEGER PRIMARY KEY,
                    category    INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    fetched_at  TIMESTAMPTZ
                )
                """);
            st.execute("""
                CREATE TABLE items (
                    item_id      INTEGER PRIMARY KEY,
                    name         TEXT,
                    type         TEXT,
                    rarity       TEXT,
                    vendor_value INTEGER,
                    icon_path    TEXT,
                    icon_url     TEXT,
                    fetched_at   TIMESTAMPTZ
                )
                """);
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    @Test
    void ordersByCategoryThenItemIdAndJoinsIconAndRarity() throws Exception {
        try (Statement st = con.createStatement()) {
            // 19697 carries retained icon metadata as well, so the batch read is proven to supply both
            // the desktop path and the upstream source without a second query (STORY-API-009).
            st.execute("INSERT INTO items (item_id, name, rarity, icon_path, icon_url) VALUES (19697, 'Copper Ore', 'Basic', 'C:\\icons\\19697.png', 'https://render.guildwars2.com/file/ABCD/1.png')");
            st.execute("INSERT INTO items (item_id, name, rarity, icon_path) VALUES (19719, 'Pile of Glittering Dust', 'Basic', 'C:\\icons\\19719.png')");
            st.execute("INSERT INTO items (item_id, name, rarity, icon_path) VALUES (12142, 'Head of Garlic', 'Basic', 'C:\\icons\\12142.png')");
            // Inserted out of order on purpose: ORDER BY category, item_id must decide the order.
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (12142, 5, 3)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (19719, 1, 40)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (19697, 1, 100)");
        }

        List<MaterialStorageRepository.MaterialStorageRow> rows =
                new MaterialStorageRepository().loadMaterialStorage(con);

        assertEquals(List.of(19697, 19719, 12142), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::itemId).toList());
        assertEquals(new MaterialStorageRepository.MaterialStorageRow(
                1, 19697, 100, "C:\\icons\\19697.png", "https://render.guildwars2.com/file/ABCD/1.png", "Basic"),
                rows.get(0));
        assertEquals(5, rows.get(2).category());
    }

    @Test
    void excludesZeroAndNullCountStacks() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (19697, 1, 0)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (19719, 1, NULL)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (19721, 1, 2)");
        }

        List<MaterialStorageRepository.MaterialStorageRow> rows =
                new MaterialStorageRepository().loadMaterialStorage(con);

        assertEquals(1, rows.size());
        assertEquals(19721, rows.get(0).itemId());
    }

    @Test
    void unknownItemKeepsNullIconAndRarity() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (99999, 3, 11)");
        }

        List<MaterialStorageRepository.MaterialStorageRow> rows =
                new MaterialStorageRepository().loadMaterialStorage(con);

        assertEquals(1, rows.size());
        assertEquals(11, rows.get(0).count());
        assertNull(rows.get(0).iconPath());
        assertNull(rows.get(0).rarity());
    }

    @Test
    void emptyMaterialsTableReturnsAnEmptyList() throws Exception {
        assertTrue(new MaterialStorageRepository().loadMaterialStorage(con).isEmpty());
    }

    @Test
    void noArgLoadUsesTheSharedDbConnectionConfiguration() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (19697, 1, 64)");
        }

        // Db.open() reads repo.AppConfig/EnvConfig, then honors this property to reach the
        // disposable schema - the same shared path every other repository uses.
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);

        List<MaterialStorageRepository.MaterialStorageRow> rows =
                new MaterialStorageRepository().loadMaterialStorage();

        assertEquals(1, rows.size());
        assertEquals(64, rows.get(0).count());
    }
}
