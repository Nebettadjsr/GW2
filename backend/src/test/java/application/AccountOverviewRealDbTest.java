package application;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountOverviewRepository;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real PostgreSQL, disposable schema (never public): own-account overview counts and publication semantics. */
class AccountOverviewRealDbTest {
    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("20000000-0000-0000-0000-00000000000b");
    private static final Set<String> ALL = Set.of("CHARACTERS", "ACCOUNT_RECIPES", "MATERIALS");
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private final String schema = "test_overview_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private String previous;
    private final AccountOverviewService service = new AccountOverviewService(Db::open,
            new AccountOverviewRepository(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        exec("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD")).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        previous = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
        exec("INSERT INTO account_registry(account_id) VALUES ('" + A + "'), ('" + B + "')");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (previous == null) System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        else System.setProperty(Db.TEST_SCHEMA_PROPERTY, previous);
        try { exec("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } finally { admin.close(); }
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = admin.createStatement()) { s.execute(sql); }
    }

    private void marker(UUID account, String source, String at) throws SQLException {
        exec("INSERT INTO account_source_state(account_id, source, generation, completed_at) VALUES ('"
                + account + "','" + source + "',1,'" + at + "')");
    }

    @Test
    void missingMarkerIsNotLoadedEvenWithStrayRows() throws Exception {
        exec("INSERT INTO characters(account_id, name) VALUES ('" + A + "','Stray')");
        exec("INSERT INTO account_materials(account_id,item_id,count) VALUES ('" + A + "',1,5)");
        var out = service.read(AccountScope.of(A), ALL);
        for (String s : ALL) {
            assertEquals(AccountOverviewService.NOT_LOADED, out.get(s).state());
            assertNull(out.get(s).count());
        }
    }

    @Test
    void publishedEmptyIsKnownZeroAndStaleIsPreserved() throws Exception {
        marker(A, "CHARACTERS", "2026-11-11T11:59:00Z");
        marker(A, "ACCOUNT_RECIPES", "2026-11-11T10:00:00Z");
        marker(A, "MATERIALS", "2026-11-11T11:59:00Z");
        var out = service.read(AccountScope.of(A), ALL);
        assertEquals(new AccountOverviewService.SourceOverview("CURRENT", 0L, null, Instant.parse("2026-11-11T11:59:00Z")),
                out.get("CHARACTERS"));
        assertEquals("STALE", out.get("ACCOUNT_RECIPES").state());
        assertEquals(0L, out.get("ACCOUNT_RECIPES").count());
        assertEquals(0L, out.get("MATERIALS").count());
        assertEquals(0L, out.get("MATERIALS").total());
    }

    @Test
    void accountsAreIsolatedAndMaterialSumsUseLongsAndSkipNonPositive() throws Exception {
        for (UUID a : new UUID[] {A, B}) {
            marker(a, "CHARACTERS", "2026-11-11T11:59:00Z");
            marker(a, "ACCOUNT_RECIPES", "2026-11-11T11:59:00Z");
            marker(a, "MATERIALS", "2026-11-11T11:59:00Z");
        }
        exec("INSERT INTO characters(account_id, name) VALUES ('" + A + "','One'),('" + A + "','Two'),('" + B + "','One')");
        // Shared recipe/item IDs across both accounts.
        exec("INSERT INTO account_recipes(account_id, recipe_id) VALUES ('" + A + "',1),('" + A + "',2),('" + B + "',1),('" + B + "',3),('" + B + "',4)");
        exec("INSERT INTO account_materials(account_id,item_id,count) VALUES "
                + "('" + A + "',1,2147483647),('" + A + "',2,2147483647),('" + A + "',3,0),('" + A + "',4,-5),('" + A + "',5,NULL),"
                + "('" + B + "',1,7)");
        var a = service.read(AccountScope.of(A), ALL);
        var b = service.read(AccountScope.of(B), ALL);
        assertEquals(2L, a.get("CHARACTERS").count());
        assertEquals(1L, b.get("CHARACTERS").count());
        assertEquals(2L, a.get("ACCOUNT_RECIPES").count());
        assertEquals(3L, b.get("ACCOUNT_RECIPES").count());
        assertEquals(2L, a.get("MATERIALS").count());
        assertEquals(2L * Integer.MAX_VALUE, a.get("MATERIALS").total());
        assertEquals(1L, b.get("MATERIALS").count());
        assertEquals(7L, b.get("MATERIALS").total());
    }

    @Test
    void onlyRequestedSourcesAreReadAndQueryFailureIsUnavailableNotZero() throws Exception {
        marker(A, "CHARACTERS", "2026-11-11T11:59:00Z");
        marker(A, "MATERIALS", "2026-11-11T11:59:00Z");
        var narrowed = service.read(AccountScope.of(A), Set.of("CHARACTERS"));
        assertEquals(Set.of("CHARACTERS"), narrowed.keySet());
        var failing = new AccountOverviewService(() -> { throw new SQLException("down"); },
                new AccountOverviewRepository(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15))
                .read(AccountScope.of(A), ALL);
        for (String s : ALL) {
            assertEquals(AccountOverviewService.UNAVAILABLE, failing.get(s).state());
            assertNull(failing.get(s).count());
        }
    }

    /**
     * Publication race: a replacement marker and rows commit (on another connection) after the reader has
     * read its marker but before it counts. REPEATABLE READ must keep marker and count from one snapshot.
     * The proxy hook is deterministic: it fires on the first count query, with no sleeps or live calls.
     */
    @Test
    void replacementPublishedBetweenMarkerAndCountReadsIsNotMixedIntoTheSnapshot() throws Exception {
        marker(A, "CHARACTERS", "2026-11-11T11:00:00Z");
        exec("INSERT INTO characters(account_id, name) VALUES ('" + A + "','Old1'),('" + A + "','Old2')");
        boolean[] fired = {false};
        AccountOverviewService raced = new AccountOverviewService(() -> {
            Connection real = Db.open();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && !fired[0]
                                && ((String) args[0]).toLowerCase().contains("count(")) {
                            fired[0] = true;
                            // Replace marker and rows in one publication on the independent connection.
                            admin.setAutoCommit(false);
                            try {
                                exec("UPDATE account_source_state SET generation = 2, completed_at = '2026-11-11T11:58:00Z'"
                                        + " WHERE account_id = '" + A + "' AND source = 'CHARACTERS'");
                                exec("DELETE FROM characters WHERE account_id = '" + A + "'");
                                exec("INSERT INTO characters(account_id, name) VALUES ('" + A + "','N1'),('" + A + "','N2'),"
                                        + "('" + A + "','N3'),('" + A + "','N4'),('" + A + "','N5')");
                                admin.commit();
                            } catch (SQLException failure) {
                                admin.rollback();
                                throw failure;
                            } finally {
                                admin.setAutoCommit(true);
                            }
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }, new AccountOverviewRepository(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(60));

        var during = raced.read(AccountScope.of(A), Set.of("CHARACTERS"));
        assertEquals(true, fired[0], "the replacement must have been committed between the reads");
        // Old marker with the old rows, never the old marker with the new count (or vice versa).
        assertEquals(Instant.parse("2026-11-11T11:00:00Z"), during.get("CHARACTERS").completedAt());
        assertEquals(2L, during.get("CHARACTERS").count());

        // A later read sees the replacement publication as a whole.
        var after = service.read(AccountScope.of(A), Set.of("CHARACTERS"));
        assertEquals(Instant.parse("2026-11-11T11:58:00Z"), after.get("CHARACTERS").completedAt());
        assertEquals(5L, after.get("CHARACTERS").count());
    }
}
