package application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;
import sync.IconSync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A selected Forge project with fresh quotes but missing/incomplete ingredient metadata must be detected
 * independently of quote coverage, so the shared item-demand admission can complete names/icons.
 * Runs in a disposable, uniquely named schema; no GW2 API call.
 */
class ProjectMetadataGapTest {
    private static final String ICON = "https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/63133.png";
    private final String schema = "test_meta_gap_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(schema.matches("test_meta_gap_[0-9a-f]{32}"));
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        exec("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            exec("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    @Test
    void detectsMissingOrIncompleteIngredientRowsAndIgnoresCompleteOnes() throws Exception {
        var reader = new CalculationInputVersions.DatabasePriceCoverageReader();
        exec("INSERT INTO items (item_id,name,type,rarity,vendor_value,icon_url,no_mystic_forge) VALUES"
                + " (19745,'Gossamer Scrap','CraftingMaterial','Basic',1,'" + ICON + "',false),"
                + " (19748,NULL,'CraftingMaterial','Basic',1,'" + ICON + "',false)");

        assertEquals(Set.of(19748, 20796), IconSync.incompleteIds(con, Set.of(19745, 19748, 20796, -3, 0)));
        assertTrue(reader.metadataIncomplete(con, Set.of(19745, 19748)), "incomplete row");
        assertTrue(reader.metadataIncomplete(con, Set.of(19745, 20796)), "missing row");
        assertFalse(reader.metadataIncomplete(con, Set.of(19745)), "fully complete metadata needs no admission");
        assertFalse(reader.metadataIncomplete(con, Set.of()), "empty path");
        assertFalse(reader.metadataIncomplete(null, Set.of(19748)), "no connection");

        exec("UPDATE items SET name='Wool Scrap' WHERE item_id=19748");
        assertFalse(reader.metadataIncomplete(con, Set.of(19745, 19748)), "completed metadata clears the gap");
    }

    private void exec(String sql) throws Exception {
        try (Statement st = con.createStatement()) { st.execute(sql); }
    }
}
