package infra.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Story STORY-INFRA-004: exercises the production schema gate and Flyway chain against real
 * PostgreSQL. Each test owns a disposable schema on the isolated DATABASE_URL configured by CI.
 * The test contract deliberately starts at the same boundary used by application startup.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SchemaInitializerPostgresTest {
    private final String schema = "qa_schema_" + UUID.randomUUID().toString().replace("-", "");
    private PGSimpleDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("DATABASE_URL");
        String user = System.getenv("DATABASE_USER");
        String password = System.getenv("DATABASE_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "Requires the isolated PostgreSQL service configured by CI");
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        dataSource.setCurrentSchema(schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void emptySchemaRunsCompleteChainAndLeavesAccountSourcesUnobserved() throws Exception {
        SchemaInitializer.initialize(dataSource);

        assertTrue(tableExists("account_registry"));
        assertTrue(tableExists("account_source_state"));
        assertTrue(tableExists("items"), "shared reference data remains part of the global schema");
        assertFalse(columnExists("items", "account_id"), "global catalog is not partitioned by account");
        assertFalse(tableExists("account_sync_state"), "legacy singleton marker is not runtime authority");
        assertEquals(0, rowCount("account_source_state"), "schema creation must not invent successful sources");
        assertTrue(tableExists("flyway_schema_history"));
        assertEquals(0, rowCount("account_registry"), "migration must not fabricate an account");
    }

    @Test
    void unmanagedLegacyTablesAreExplicitlyResetBeforeTheFullChain() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account_bank (slot integer PRIMARY KEY, item_id integer)");
            statement.execute("INSERT INTO account_bank VALUES (1, 123)");
            statement.execute("CREATE TABLE items (id integer PRIMARY KEY)");
        }

        SchemaInitializer.initialize(dataSource);

        assertTrue(tableExists("account_registry"));
        assertTrue(columnExists("account_bank", "account_id"),
                "the legacy table must be replaced by the account-scoped schema");
        assertTrue(primaryKeyIncludesAccountAndSlot("account_bank"),
                "bank slot uniqueness must be scoped to the account");
        assertEquals(0, rowCount("account_bank"), "legacy unscoped rows must be dropped");
        assertEquals(0, rowCount("account_source_state"));
        assertEquals(0, rowCount("account_registry"));
    }

    @Test
    void accountOwnershipAndCharacterCompositeOwnershipAreDatabaseEnforced() throws Exception {
        SchemaInitializer.initialize(dataSource);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        execute("INSERT INTO account_registry (account_id) VALUES ('" + first + "'), ('" + second + "')");
        execute("INSERT INTO account_bank (account_id, slot, item_id) VALUES ('" + first + "', 0, 10), ('" + second + "', 0, 11)");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO account_bank (account_id, slot, item_id) VALUES ('" + first + "', 0, 12)"));

        execute("INSERT INTO characters (account_id, character_id, name) VALUES ('" + first + "', 7, 'A'), ('" + second + "', 8, 'B')");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO character_crafting (account_id, character_id, discipline, rating) VALUES ('" + first + "', 8, 'Artificer', 500)"));
    }

    @Test
    void inventoryNullableLocationsHaveExplicitDuplicateAndNullSemantics() throws Exception {
        SchemaInitializer.initialize(dataSource);
        UUID account = UUID.randomUUID();
        execute("INSERT INTO account_registry (account_id) VALUES ('" + account + "')");
        execute("INSERT INTO characters (account_id, character_id, name) VALUES ('" + account + "', 9, 'C')");
        execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, equipment_slot, item_id, count) VALUES ('" + account + "', 9, 'BAG', 0, 0, NULL, 20, 1)");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, equipment_slot, item_id, count) VALUES ('" + account + "', 9, 'BAG', 0, 0, NULL, 21, 1)"));
        execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, equipment_slot, item_id, count) VALUES ('" + account + "', 9, 'EQUIPMENT', NULL, NULL, 'Helm', 22, 1)");
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, equipment_slot, item_id, count) VALUES ('" + account + "', 9, 'EQUIPMENT', NULL, NULL, 'Helm', 23, 1)"));
    }

    @Test
    void sourceStateRequiresAccountAndSuccessfulCompletionAndGeneration() throws Exception {
        SchemaInitializer.initialize(dataSource);
        UUID account = UUID.randomUUID();
        execute("INSERT INTO account_registry (account_id) VALUES ('" + account + "')");
        assertEquals(0, rowCount("account_source_state"));
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES ('" + account + "', 'BANK', 0, now())"));
    }

    @Test
    void failedMigrationDoesNotResetManagedSchemaAndRetryResumesHistory() throws Exception {
        SchemaInitializer.initialize(dataSource);
        int before = rowCount("flyway_schema_history");
        assertThrows(RuntimeException.class, () -> SchemaInitializer.initialize(dataSource,
                "CREATE TABLE qa_injected_failure (id integer PRIMARY KEY); SELECT 1 / 0"));
        assertEquals(before, rowCount("flyway_schema_history"));
        assertFalse(tableExists("qa_injected_failure"),
                "the failed migration version must roll back its earlier DDL statement");
        assertTrue(tableExists("account_registry"), "ordinary migration failure cannot reset a managed schema");
        SchemaInitializer.initialize(dataSource);
        assertEquals(before, rowCount("flyway_schema_history"));
    }

    @Test
    void checksumMismatchFailsClosedAndDoesNotResetManagedSchema() throws Exception {
        SchemaInitializer.initialize(dataSource);
        execute("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE success");
        assertThrows(RuntimeException.class, () -> SchemaInitializer.initialize(dataSource));
        assertTrue(tableExists("account_registry"));
    }

    @Test
    void unknownSchemaIsResetButAutomaticBaselineAndCleanAreDisabled() throws Exception {
        execute("CREATE TABLE unknown_marker (value text)");
        execute("INSERT INTO unknown_marker VALUES ('cached')");
        SchemaInitializer.initialize(dataSource);
        assertFalse(tableExists("unknown_marker"));
        assertTrue(tableExists("account_registry"));
        assertFalse(SchemaInitializer.configuration().baselineOnMigrate());
        assertTrue(SchemaInitializer.configuration().cleanDisabled(),
                "automatic destructive clean must remain disabled");
        assertFalse(SchemaInitializer.configuration().repairOnMigrate());
    }

    private boolean tableExists(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)")) {
            statement.setString(1, schema);
            statement.setString(2, name);
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private int rowCount(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next(); return result.getInt(1);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?)")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private boolean primaryKeyIncludesAccountAndSlot(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT count(DISTINCT kcu.column_name) = 2 " +
                        "FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.key_column_usage kcu " +
                        "ON tc.constraint_catalog = kcu.constraint_catalog " +
                        "AND tc.constraint_schema = kcu.constraint_schema " +
                        "AND tc.constraint_name = kcu.constraint_name " +
                        "AND tc.table_name = kcu.table_name " +
                        "WHERE tc.table_schema = ? AND tc.table_name = ? " +
                        "AND tc.constraint_type = 'PRIMARY KEY' " +
                        "AND kcu.column_name IN ('account_id', 'slot')")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
