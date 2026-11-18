package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static support.fixture.CachedItemNameFixtures.itemName;

import static org.junit.jupiter.api.Assertions.*;

/** Scoped material storage over the Flyway-managed schema, in a disposable test-owned schema. */
class MaterialStorageRepositoryTest {
    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-00000000000b");

    private final String schema = "test_mats_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach void setup() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_registry (account_id) VALUES ('" + A.accountId() + "'), ('"
                    + B.accountId() + "')");
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (con == null) return;
        try (Statement st = con.createStatement()) { st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { con.close(); }
    }

    @Test void preservesCatalogOrderAndZeroPositionsWhileJoiningMetadata() throws Exception {
        new AccountSourceStateRepository().advance(con, A, AccountSyncStateRepository.MATERIALS,
                Instant.parse("2026-11-15T20:00:00Z"));
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO material_categories VALUES (5, 'Second', 10), (1, 'First', 2)");
            st.execute("INSERT INTO material_category_items VALUES (5, 0, 99), (1, 0, 20), (1, 1, 21)");
            st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('" + A.accountId()
                    + "', 20, 250), ('" + B.accountId() + "', 21, 77)");
            st.execute("INSERT INTO items (item_id, name, icon_url, rarity) VALUES"
                    + " (20, 'Green Wood Log', 'https://render.guildwars2.com/file/ABC/20.png', 'Fine'),"
                    + " (21, 'Zero-stock Mithril Ore', NULL, 'Rare')");
        }
        List<MaterialStorageRepository.MaterialStorageRow> rows = new MaterialStorageRepository().loadMaterialStorage(con, A);
        assertEquals(List.of(20, 21, 99), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::itemId).toList());
        assertEquals(List.of(2, 2, 10), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::categoryOrder).toList());
        assertEquals(List.of(250, 0, 0), rows.stream().map(MaterialStorageRepository.MaterialStorageRow::count).toList(),
                "another account's quantity must not leak into this account's zero-stock position");
        assertEquals("https://render.guildwars2.com/file/ABC/20.png", rows.getFirst().iconUrl());
        assertEquals(List.of("First", "First", "Second"),
                rows.stream().map(MaterialStorageRepository.MaterialStorageRow::categoryName).toList());
        assertEquals("Green Wood Log", itemName(rows.get(0)), "the item name must not be the category name");
        assertEquals("Zero-stock Mithril Ore", itemName(rows.get(1)), "catalog metadata survives a zero count");
        assertNull(itemName(rows.get(2)), "unknown metadata stays null instead of borrowing the category name");
    }

    @Test void failsWhenTheAccountsMaterialsHaveNeverBeenSynchronized() throws Exception {
        new AccountSourceStateRepository().advance(con, B, AccountSyncStateRepository.MATERIALS,
                Instant.parse("2026-11-15T20:00:00Z"));
        SQLException failure = assertThrows(SQLException.class,
                () -> new MaterialStorageRepository().loadMaterialStorage(con, A));
        assertTrue(failure.getMessage().contains("not been synchronized"));
    }
}
