package repo;

import model.AccountLuck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL check of account isolation and upsert semantics in a disposable schema. */
class AccountLuckRepositoryTest {
    private final String schema = "test_luck_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createSchema() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);
        AccountLuckSchema.ensure(con);
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
        repo.save(con, new AccountLuck("account-a", 100, oldTime));
        repo.save(con, new AccountLuck("account-b", 200, oldTime));
        repo.save(con, new AccountLuck("account-a", 300, newTime));

        assertEquals(new AccountLuck("account-a", 300, newTime), repo.find(con, "account-a").orElseThrow());
        assertEquals(new AccountLuck("account-b", 200, oldTime), repo.find(con, "account-b").orElseThrow());
        assertTrue(repo.find(con, "account-c").isEmpty());
    }

    @Test
    void createsMissingTableAndRejectsIncompatibleExistingTable() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("DROP TABLE account_luck");
        }
        AccountLuckSchema.ensure(con);
        assertTrue(new AccountLuckRepository().find(con, "account-a").isEmpty());

        try (Statement st = con.createStatement()) {
            st.execute("DROP TABLE account_luck");
            st.execute("CREATE TABLE account_luck (account_id TEXT PRIMARY KEY, consumed_luck INTEGER NOT NULL, fetched_at TIMESTAMPTZ NOT NULL)");
        }
        assertThrows(java.sql.SQLException.class, () -> AccountLuckSchema.ensure(con));
    }
}
