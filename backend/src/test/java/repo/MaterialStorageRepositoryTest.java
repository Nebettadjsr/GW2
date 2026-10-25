package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MaterialStorageRepositoryTest {
    private final String schema = "test_mats_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach void setup() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);
        try (Statement st = con.createStatement()) {
            st.execute("CREATE TABLE account_materials (item_id INTEGER PRIMARY KEY, count INTEGER, fetched_at TIMESTAMPTZ)");
            st.execute("CREATE TABLE items (item_id INTEGER PRIMARY KEY, icon_path TEXT, icon_url TEXT, rarity TEXT)");
        }
        MaterialStorageSchema.ensure(con);
    }

    @AfterEach void cleanup() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (con == null) return;
        try (Statement st = con.createStatement()) { st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { con.close(); }
    }

    @Test void preservesCatalogOrderAndZeroPositionsWhileJoiningMetadata() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials_sync VALUES (1, now())");
            st.execute("INSERT INTO material_categories VALUES (5, 'Second', 10), (1, 'First', 2)");
            st.execute("INSERT INTO material_category_items VALUES (5, 0, 99), (1, 0, 20), (1, 1, 21)");
            st.execute("INSERT INTO account_materials VALUES (20, 250)");
            st.execute("INSERT INTO items VALUES (20, 'C:/icons/20.png', 'https://render.guildwars2.com/file/ABC/20.png', 'Fine')");
        }
        List<MaterialStorageRepository.MaterialStorageRow> rows = new MaterialStorageRepository().loadMaterialStorage(con);
        assertEquals(List.of(20, 21, 99), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::itemId).toList());
        assertEquals(List.of(2, 2, 10), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::categoryOrder).toList());
        assertEquals(List.of(250, 0, 0), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::count).toList());
        assertEquals("https://render.guildwars2.com/file/ABC/20.png", rows.getFirst().iconUrl());
    }

    @Test void failsWhenAccountMaterialsHaveNeverBeenSynchronized() {
        SQLException failure = assertThrows(SQLException.class, () -> new MaterialStorageRepository().loadMaterialStorage(con));
        assertTrue(failure.getMessage().contains("not been synchronized"));
    }

    @Test void bootstrapCreatesMissingCatalogAndSyncTablesAndBackfillsKnownSnapshot() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("DROP TABLE account_materials_sync");
            st.execute("DROP TABLE material_category_items");
            st.execute("DROP TABLE material_categories");
            st.execute("INSERT INTO account_materials VALUES (123, 4, now())");
        }

        MaterialStorageSchema.ensure(con);

        try (Statement st = con.createStatement(); var rs = st.executeQuery("SELECT fetched_at FROM account_materials_sync WHERE id = 1")) {
            assertTrue(rs.next(), "a known existing snapshot should be retained across schema bootstrap");
        }
        try (Statement st = con.createStatement(); var rs = st.executeQuery("SELECT to_regclass('material_categories'), to_regclass('material_category_items')")) {
            assertTrue(rs.next());
            assertNotNull(rs.getString(1));
            assertNotNull(rs.getString(2));
        }
    }
}
