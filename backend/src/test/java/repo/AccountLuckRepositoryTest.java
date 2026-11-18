package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL check of account isolation and upsert semantics on the Flyway-managed schema in a disposable schema. */
class AccountLuckRepositoryTest {
    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-00000000000b");
    private static final AccountScope C = AccountScope.of("30000000-0000-0000-0000-00000000000c");

    private final String schema = "test_luck_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createSchema() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
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
                    + B.accountId() + "'), ('" + C.accountId() + "')");
        }
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

    @Test
    void storesEachAccountSeparatelyAndUpdatesOnlyTheMatchingAccount() throws Exception {
        var repo = new AccountLuckRepository();
        var oldTime = Instant.parse("2026-01-01T00:00:00Z");
        var newTime = Instant.parse("2026-09-29T00:00:00Z");
        repo.save(con, A, 100, oldTime);
        repo.save(con, B, 200, oldTime);
        repo.save(con, A, 300, newTime);

        var a = repo.find(con, A).orElseThrow();
        assertEquals(300, a.consumedLuck());
        assertEquals(newTime, a.fetchedAt());
        var b = repo.find(con, B).orElseThrow();
        assertEquals(200, b.consumedLuck());
        assertEquals(oldTime, b.fetchedAt());
        assertTrue(repo.find(con, C).isEmpty(), "a never-published account is missing, not zero");
    }
}
