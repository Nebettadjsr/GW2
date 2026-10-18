package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criteria 1 and 6: on a database the versioned chain owns, the retained
 * unscoped legacy account paths are disabled and the remaining {@code CREATE TABLE IF NOT EXISTS}
 * bootstraps stop writing schema. On a pre-migration database they still behave exactly as before,
 * which is what keeps the current installation working until it is explicitly adopted.
 *
 * <p>Real PostgreSQL in a disposable schema; the Flyway history table is created directly here
 * because what is under test is how these paths react to a managed database, not the chain itself
 * (that is {@code infra.schema.SchemaMigrationChainTest}).
 */
class ManagedSchemaLegacyPathsTest {

    private final String schema = "managed_paths_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createSchema() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    private void markManaged() throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("CREATE TABLE " + ManagedSchema.HISTORY_TABLE + " (installed_rank INTEGER PRIMARY KEY)");
        }
    }

    @Test
    void anUnmigratedDatabaseKeepsTheLegacyBootstrapsExactlyAsTheyWere() throws Exception {
        assertFalse(ManagedSchema.isManaged(con));

        AccountSyncStateRepository.ensure(con);
        AccountLuckSchema.ensure(con);
        // The material bootstrap has always read account_materials to decide whether a pre-migration
        // snapshot is worth preserving, so the legacy table has to be there as it is in production.
        try (Statement st = con.createStatement()) {
            st.execute("CREATE TABLE account_materials (item_id INTEGER PRIMARY KEY, count INTEGER,"
                    + " fetched_at TIMESTAMPTZ)");
        }
        MaterialStorageSchema.ensure(con);

        assertTrue(relationExists("account_sync_state"));
        assertTrue(relationExists("account_luck"));
        assertTrue(relationExists("material_categories"));
        assertTrue(relationExists("account_materials_sync"));
        assertEquals(java.util.Optional.empty(), new AccountSyncStateRepository().find(con, "BANK"));
    }

    @Test
    void aManagedDatabaseRefusesTheUnscopedAccountMarkerPathWithAnActionableDiagnostic() throws Exception {
        markManaged();
        assertTrue(ManagedSchema.isManaged(con));

        SQLException refused = assertThrows(SQLException.class,
                () -> AccountSyncStateRepository.ensure(con));
        assertTrue(refused.getMessage().contains("verified account GUID"),
                "the diagnostic must say why an unscoped account path is refused: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("account_sync_state"), refused.getMessage());

        // Every account read and every account writer goes through a marker, so the same refusal
        // covers the whole unscoped account surface.
        assertThrows(SQLException.class, () -> AccountSyncStateRepository.mark(con, "BANK", java.time.Instant.EPOCH));
        assertThrows(SQLException.class, () -> new AccountSyncStateRepository().find(con, "BANK"));
        assertFalse(relationExists("account_sync_state"),
                "a refused path must not have created the legacy table");
    }

    @Test
    void aManagedDatabaseLeavesTheFlywayOwnedObjectsToFlyway() throws Exception {
        markManaged();

        AccountLuckSchema.ensure(con);
        MaterialStorageSchema.ensure(con);

        assertFalse(relationExists("account_luck"),
                "the legacy Luck table is replaced by the Flyway-owned, GUID-keyed one");
        assertFalse(relationExists("material_categories"),
                "the material catalog is owned by migration V1, so this bootstrap must not create it");
        assertFalse(relationExists("account_materials_sync"),
                "the singleton material marker is retired in favour of per-account source state");
    }

    private boolean relationExists(String name) throws SQLException {
        try (var ps = con.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                               WHERE table_schema = current_schema() AND table_name = ?)
                """)) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
