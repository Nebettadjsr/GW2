package repo;

import application.ops.DatabaseSize;
import application.ops.DatabaseSizeService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL in a disposable schema (never public): the narrow size query and its cache. */
class DatabaseSizeRepositoryIT {
    private final String schema = "test_dbsize_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private String previous;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        exec("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD")).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        previous = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
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

    @Test
    void measuresNonNegativeTotalDatabaseBytes() throws Exception {
        assertTrue(new DatabaseSizeRepository(Db::open).totalBytes() >= 0);
    }

    @Test
    void cachesSingleFlightsAndReportsStaleThenUnavailableAgainstRealQuery() {
        AtomicInteger opens = new AtomicInteger();
        boolean[] down = {false};
        DatabaseSizeRepository repo = new DatabaseSizeRepository(() -> {
            opens.incrementAndGet();
            if (down[0]) throw new SQLException("simulated outage");
            return Db.open();
        });
        Instant[] now = {Instant.parse("2026-11-14T10:00:00Z")};
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        DatabaseSizeService service = new DatabaseSizeService(repo, clock);
        DatabaseSize first = service.current();
        assertEquals(DatabaseSize.State.AVAILABLE, first.state());
        assertTrue(first.bytes() >= 0);
        service.current();
        assertEquals(1, opens.get());
        down[0] = true;
        now[0] = now[0].plus(Duration.ofMinutes(6));
        DatabaseSize stale = service.current();
        assertEquals(DatabaseSize.State.STALE, stale.state());
        assertEquals(first.measuredAt(), stale.measuredAt());
        assertEquals(first.bytes(), stale.bytes());
        assertEquals(DatabaseSize.State.UNAVAILABLE,
                new DatabaseSizeService(repo, clock).current().state());
    }

    @Test
    void noOperationalHistoryTableOrMigrationExists() throws Exception {
        try (Statement s = admin.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                     + schema + "' AND (table_name ILIKE '%operational%' OR table_name ILIKE '%ops_%' "
                     + "OR table_name ILIKE '%minute_bucket%' OR (table_name ILIKE '%history%' AND table_name <> 'flyway_schema_history'))")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
