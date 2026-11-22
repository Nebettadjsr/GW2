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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AccountSourceStateRepository#isPublished} opens its own connection, so a controller can ask
 * "is this source published?" without holding a {@code Connection}. Real PostgreSQL, isolated schema.
 */
class AccountSourceStateRepositoryConnectingReadTest {

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-000000000001");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-000000000002");
    private static final String CHARACTERS = "CHARACTERS";

    private final String schema = "test_state_connecting_read_" + UUID.randomUUID().toString().replace("-", "");
    private final AccountSourceStateRepository states = new AccountSourceStateRepository();
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        admin.setAutoCommit(true);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (admin == null) return;
        try (Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void aSourceIsPublishedOnlyForTheAccountThatPublishedIt() throws Exception {
        assertFalse(states.isPublished(A, CHARACTERS), "nothing is published yet");

        new AccountRegistryRepository().register(admin, A);
        states.advance(admin, A, CHARACTERS, Instant.parse("2026-11-22T10:00:00Z"));

        assertTrue(states.isPublished(A, CHARACTERS));
        assertFalse(states.isPublished(A, "BANK"), "another source of the same account is not published");
        assertFalse(states.isPublished(B, CHARACTERS), "the same source of another account is not published");
    }
}
