package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AccountSyncStateRepositoryTest {
    private final String schema = "test_account_sync_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach void setUp() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement statement = con.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
        con.setSchema(schema);
        AccountSyncStateRepository.ensure(con);
    }

    @AfterEach void tearDown() throws Exception {
        if (con == null) return;
        try (Statement statement = con.createStatement()) { statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { con.close(); }
    }

    @Test void storesCompletionTimeOnlyWhenExplicitlyMarkedAndSupportsSuccessfulEmptySources() throws Exception {
        var repository = new AccountSyncStateRepository();
        assertTrue(repository.find(con, AccountSyncStateRepository.BANK).isEmpty());
        Instant fetchedAt = Instant.parse("2026-10-03T12:00:00Z");
        AccountSyncStateRepository.mark(con, AccountSyncStateRepository.BANK, fetchedAt);
        assertEquals(fetchedAt, repository.find(con, AccountSyncStateRepository.BANK).orElseThrow());
        AccountSyncStateRepository.mark(con, AccountSyncStateRepository.MATERIALS, fetchedAt);
        assertEquals(fetchedAt, repository.find(con, AccountSyncStateRepository.MATERIALS).orElseThrow());
    }
}
