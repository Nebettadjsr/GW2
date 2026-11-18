package sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import repo.Db;
import repo.EnvConfig;
import repo.MaterialStorageRepository;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-COV-007: the active fetch-to-publication transaction against production migrations and a
 * controlled public-array upstream. Literal expected rows are independent of the parser/publisher.
 * PostgreSQL faults occur after earlier writes, so readback from another connection proves rollback.
 */
class MaterialCategoryPublicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "https://api.guildwars2.com/v2/materials?ids=all";
    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-00000000000b");
    private static final Instant A_TIME = Instant.parse("2026-11-15T00:00:00Z");
    private static final Instant B_TIME = Instant.parse("2026-11-16T00:00:00Z");
    private static final String INITIAL = """
            [{"id":7,"name":"Intermediate","order":20,"items":[110,120,130,140]},
             {"id":2,"name":"Starter","order":10,"items":[210,220]},
             {"id":9,"name":"Old category","order":30,"items":[910,920]},
             {"id":5,"name":"Empty later","order":40,"items":[510]}]
            """;
    private static final String REPLACEMENT = """
            [{"id":2,"name":"Starter reordered","order":30,"items":[220,230,210]},
             {"id":5,"name":"Empty later","order":20,"items":[]},
             {"id":4,"name":"New category","order":10,"items":[410,420]},
             {"id":7,"name":"Renamed supplies","order":5,"items":[130,110]}]
            """;

    private final String schema = "test_material_publication_" + UUID.randomUUID().toString().replace("-", "");
    private final List<String> urls = new ArrayList<>();
    private Connection observer;
    private String previousSchema;

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(schema.matches("test_material_publication_[0-9a-f]{32}"));
        observer = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        exec("CREATE SCHEMA " + schema);
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        observer.setSchema(schema);
        previousSchema = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
        seedAccounts();
        assertAccountsUnchanged();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (previousSchema == null) System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        else System.setProperty(Db.TEST_SCHEMA_PROPERTY, previousSchema);
        if (observer == null) return;
        try {
            assertTrue(schema.matches("test_material_publication_[0-9a-f]{32}"), "drop only this test's generated schema");
            exec("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            observer.close();
        }
    }

    @Test
    void publishesOrderedReplacementAndRepeatWithoutChangingEitherAccount() throws Exception {
        publish(INITIAL);
        assertInitialCatalog();
        assertAccountsUnchanged();

        publish(REPLACEMENT);
        assertReplacementCatalog();
        assertScopedVisibleRows();
        assertAccountsUnchanged();

        publish(REPLACEMENT);
        assertReplacementCatalog();
        assertScopedVisibleRows();
        assertAccountsUnchanged();
        assertRequests(3);
    }

    @Test
    void upstreamFailurePreservesPriorPublicationAndExplicitRetrySucceeds() throws Exception {
        publish(INITIAL);
        var upstreamFailure = new IOException("controlled public catalog outage");

        var thrown = assertThrows(IOException.class, () -> MaterialCategorySync.syncMaterialCategories(url -> {
            urls.add(url);
            assertEquals(ENDPOINT, url);
            throw upstreamFailure;
        }));

        assertSame(upstreamFailure, thrown);
        assertRequests(2);
        assertInitialCatalog();
        assertAccountsUnchanged();
        publish(REPLACEMENT);
        assertReplacementCatalog();
        assertScopedVisibleRows();
        assertAccountsUnchanged();
        assertRequests(3);
    }

    @Test
    void emptyCatalogRefusesPublicationAndExplicitRetrySucceeds() throws Exception {
        publish(INITIAL);

        var failure = assertThrows(SQLException.class, () -> publish("[]"));

        assertEquals("Material catalog contained no categories", failure.getMessage());
        assertRequests(2);
        assertInitialCatalog();
        assertAccountsUnchanged();
        publish(REPLACEMENT);
        assertReplacementCatalog();
        assertAccountsUnchanged();
        assertRequests(3);
    }

    enum Fault { ITEM_INSERT, STALE_CATEGORY_DELETE }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void postgresFailureRollsBackEarlierWritesAndExplicitRetrySucceeds(Fault fault) throws Exception {
        publish(INITIAL);
        String table = fault == Fault.ITEM_INSERT ? "material_category_items" : "material_categories";
        String event = fault == Fault.ITEM_INSERT ? "INSERT" : "DELETE";
        String condition = fault == Fault.ITEM_INSERT ? "NEW.item_id = 420" : "OLD.category_id = 9";
        String returned = fault == Fault.ITEM_INSERT ? "NEW" : "OLD";
        exec("CREATE FUNCTION reject_catalog_change() RETURNS trigger AS $$ BEGIN IF " + condition
                + " THEN RAISE EXCEPTION 'controlled material publication failure' USING ERRCODE = 'P0001';"
                + " END IF; RETURN " + returned + "; END; $$ LANGUAGE plpgsql");
        exec("CREATE TRIGGER reject_catalog_change BEFORE " + event + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION reject_catalog_change()");

        var failure = assertThrows(SQLException.class, () -> publish(REPLACEMENT));

        assertEquals("P0001", failure.getSQLState(), "the controlled real PostgreSQL statement failed");
        assertRequests(2);
        // ITEM_INSERT follows the category batch; STALE_CATEGORY_DELETE follows item writes and tail trimming.
        // A partial commit would change names, add category 4, trim category 7/5 or remove category 9 here.
        assertInitialCatalog();
        assertAccountsUnchanged();
        exec("DROP TRIGGER reject_catalog_change ON " + table);
        publish(REPLACEMENT);
        assertReplacementCatalog();
        assertScopedVisibleRows();
        assertAccountsUnchanged();
        assertRequests(3);
    }

    private void publish(String payload) throws Exception {
        MaterialCategorySync.syncMaterialCategories(url -> {
            urls.add(url);
            assertEquals(ENDPOINT, url, "only the public material catalog is requested");
            return JSON.readTree(payload);
        });
    }

    private void assertRequests(int count) {
        assertEquals(Collections.nCopies(count, ENDPOINT), urls, "one fetch per explicit invocation; no automatic retry");
    }

    private record CategoryRow(int id, String name, int order) {}
    private record PositionRow(int category, int position, int item) {}

    private void assertInitialCatalog() throws SQLException {
        assertEquals(List.of(new CategoryRow(2, "Starter", 10), new CategoryRow(5, "Empty later", 40),
                new CategoryRow(7, "Intermediate", 20), new CategoryRow(9, "Old category", 30)), categories());
        assertEquals(List.of(new PositionRow(2, 0, 210), new PositionRow(2, 1, 220),
                new PositionRow(5, 0, 510), new PositionRow(7, 0, 110), new PositionRow(7, 1, 120),
                new PositionRow(7, 2, 130), new PositionRow(7, 3, 140),
                new PositionRow(9, 0, 910), new PositionRow(9, 1, 920)), positions());
    }

    private void assertReplacementCatalog() throws SQLException {
        assertEquals(List.of(new CategoryRow(2, "Starter reordered", 30), new CategoryRow(4, "New category", 10),
                new CategoryRow(5, "Empty later", 20), new CategoryRow(7, "Renamed supplies", 5)), categories());
        assertEquals(List.of(new PositionRow(2, 0, 220), new PositionRow(2, 1, 230), new PositionRow(2, 2, 210),
                new PositionRow(4, 0, 410), new PositionRow(4, 1, 420),
                new PositionRow(7, 0, 130), new PositionRow(7, 1, 110)), positions());
    }

    private List<CategoryRow> categories() throws SQLException {
        var rows = new ArrayList<CategoryRow>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT category_id, name, display_order FROM material_categories ORDER BY category_id")) {
            while (rs.next()) rows.add(new CategoryRow(rs.getInt(1), rs.getString(2), rs.getInt(3)));
        }
        return rows;
    }

    private List<PositionRow> positions() throws SQLException {
        var rows = new ArrayList<PositionRow>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT category_id, position, item_id FROM material_category_items ORDER BY category_id, position")) {
            while (rs.next()) rows.add(new PositionRow(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
        }
        return rows;
    }

    private record VisibleRow(int category, String name, int order, Integer position, Integer item, int count) {
        static VisibleRow of(MaterialStorageRepository.MaterialStorageRow row) {
            return new VisibleRow(row.category(), row.categoryName(), row.categoryOrder(), row.position(), row.itemId(), row.count());
        }
    }

    private void assertScopedVisibleRows() throws SQLException {
        var repository = new MaterialStorageRepository();
        assertEquals(List.of(new VisibleRow(7, "Renamed supplies", 5, 0, 130, 8),
                new VisibleRow(7, "Renamed supplies", 5, 1, 110, 6),
                new VisibleRow(4, "New category", 10, 0, 410, 0), new VisibleRow(4, "New category", 10, 1, 420, 0),
                new VisibleRow(5, "Empty later", 20, null, null, 0),
                new VisibleRow(2, "Starter reordered", 30, 0, 220, 12),
                new VisibleRow(2, "Starter reordered", 30, 1, 230, 0),
                new VisibleRow(2, "Starter reordered", 30, 2, 210, 4)),
                repository.loadMaterialStorage(observer, A).stream().map(VisibleRow::of).toList());
        assertEquals(List.of(new VisibleRow(7, "Renamed supplies", 5, 0, 130, 80),
                new VisibleRow(7, "Renamed supplies", 5, 1, 110, 60),
                new VisibleRow(4, "New category", 10, 0, 410, 0), new VisibleRow(4, "New category", 10, 1, 420, 9),
                new VisibleRow(5, "Empty later", 20, null, null, 0),
                new VisibleRow(2, "Starter reordered", 30, 0, 220, 120),
                new VisibleRow(2, "Starter reordered", 30, 1, 230, 0),
                new VisibleRow(2, "Starter reordered", 30, 2, 210, 0)),
                repository.loadMaterialStorage(observer, B).stream().map(VisibleRow::of).toList());
    }

    private void seedAccounts() throws SQLException {
        exec("INSERT INTO account_registry (account_id, display_name, created_at, last_seen_at, last_successful_sync_at) VALUES"
                + " ('" + A.accountId() + "', 'Synthetic A', '2026-11-15T00:00:00Z', '2026-11-15T01:00:00Z', '2026-11-15T02:00:00Z'),"
                + " ('" + B.accountId() + "', 'Synthetic B', '2026-11-16T00:00:00Z', '2026-11-16T01:00:00Z', '2026-11-16T02:00:00Z')");
        exec("INSERT INTO account_materials (account_id, item_id, count, fetched_at) VALUES"
                + " ('" + A.accountId() + "',110,6,'2026-11-15T00:00:00Z'), ('" + A.accountId() + "',130,8,'2026-11-15T00:00:00Z'),"
                + " ('" + A.accountId() + "',210,4,'2026-11-15T00:00:00Z'), ('" + A.accountId() + "',220,12,'2026-11-15T00:00:00Z'),"
                + " ('" + A.accountId() + "',910,2,'2026-11-15T00:00:00Z'),"
                + " ('" + B.accountId() + "',110,60,'2026-11-16T00:00:00Z'), ('" + B.accountId() + "',130,80,'2026-11-16T00:00:00Z'),"
                + " ('" + B.accountId() + "',220,120,'2026-11-16T00:00:00Z'), ('" + B.accountId() + "',420,9,'2026-11-16T00:00:00Z')");
        exec("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES"
                + " ('" + A.accountId() + "','MATERIALS',3,'2026-11-15T02:00:00Z'),"
                + " ('" + B.accountId() + "','MATERIALS',8,'2026-11-16T02:00:00Z')");
    }

    private record RegistryRow(UUID account, String name, Instant created, Instant seen, Instant synced) {}
    private record StockRow(UUID account, int item, int count, Instant fetched) {}
    private record SourceRow(UUID account, String source, long generation, Instant completed) {}

    private void assertAccountsUnchanged() throws SQLException {
        var registry = new ArrayList<RegistryRow>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT account_id, display_name, created_at, last_seen_at, last_successful_sync_at FROM account_registry ORDER BY account_id")) {
            while (rs.next()) registry.add(new RegistryRow(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()));
        }
        assertEquals(List.of(new RegistryRow(A.accountId(), "Synthetic A", A_TIME, A_TIME.plusSeconds(3600), A_TIME.plusSeconds(7200)),
                new RegistryRow(B.accountId(), "Synthetic B", B_TIME, B_TIME.plusSeconds(3600), B_TIME.plusSeconds(7200))), registry,
                "global refresh neither registers an account nor rewrites its registry markers");
        var stock = new ArrayList<StockRow>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT account_id, item_id, count, fetched_at FROM account_materials ORDER BY account_id, item_id")) {
            while (rs.next()) stock.add(new StockRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toInstant()));
        }
        assertEquals(List.of(new StockRow(A.accountId(), 110, 6, A_TIME), new StockRow(A.accountId(), 130, 8, A_TIME),
                new StockRow(A.accountId(), 210, 4, A_TIME), new StockRow(A.accountId(), 220, 12, A_TIME),
                new StockRow(A.accountId(), 910, 2, A_TIME), new StockRow(B.accountId(), 110, 60, B_TIME),
                new StockRow(B.accountId(), 130, 80, B_TIME), new StockRow(B.accountId(), 220, 120, B_TIME),
                new StockRow(B.accountId(), 420, 9, B_TIME)), stock, "removed catalog positions do not remove account stock");
        var states = new ArrayList<SourceRow>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT account_id, source, generation, completed_at FROM account_source_state ORDER BY account_id, source")) {
            while (rs.next()) states.add(new SourceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant()));
        }
        assertEquals(List.of(new SourceRow(A.accountId(), "MATERIALS", 3, A_TIME.plusSeconds(7200)),
                new SourceRow(B.accountId(), "MATERIALS", 8, B_TIME.plusSeconds(7200))), states,
                "global publication does not claim a new account source generation/completion");
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = observer.createStatement()) { s.execute(sql); }
    }
}
