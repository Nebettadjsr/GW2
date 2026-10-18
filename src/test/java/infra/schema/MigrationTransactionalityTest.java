package infra.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criterion 6: a failed migration rolls back and a retry resumes from the
 * history that was already committed.
 *
 * <p>The failure is injected deterministically by a test-only migration under
 * {@code db/migration-failure}, which is never part of the production chain. The Flyway instance that
 * runs it is built by copying the production configuration from
 * {@link SchemaGate#flyway(String, String, String, String)} and only widening its locations, so the
 * transaction, validation and baseline settings under test are exactly the shipped ones.
 */
class MigrationTransactionalityTest {

    private Flyway withFailingMigration(DisposableSchema db) {
        return new org.flywaydb.core.api.configuration.FluentConfiguration()
                .configuration(SchemaGate.flyway(db.url(), db.user(), db.password(), db.name()).getConfiguration())
                .locations(SchemaGate.MIGRATION_LOCATION, "classpath:db/migration-failure")
                .load();
    }

    @Test
    void aFailingMigrationRollsBackItsOwnVersionAndLeavesEarlierOnesApplied() throws Exception {
        try (DisposableSchema db = new DisposableSchema("mig_rollback")) {
            assertThrows(FlywayException.class, () -> withFailingMigration(db).migrate());

            assertEquals(List.of("1", "2"), db.rows("SELECT version FROM " + SchemaGate.HISTORY_TABLE
                            + " WHERE version IS NOT NULL AND success ORDER BY installed_rank"),
                    "the versions that committed before the failure must stay recorded");
            assertFalse(db.liveSchemaShape().containsKey("should_not_survive_a_failed_migration"),
                    "the failing migration's DDL and data must roll back with it");
            assertEquals(0, db.scalarLong("SELECT count(*) FROM " + SchemaGate.HISTORY_TABLE
                            + " WHERE version = '3' AND success"),
                    "a rolled-back version must not be recorded as applied");
            assertTrue(db.liveSchemaShape().containsKey("gw2_account"),
                    "the schema the earlier versions built must be intact");
        }
    }

    @Test
    void aRetryWithTheProductionChainResumesFromTheCommittedHistoryWithoutLosingData() throws Exception {
        try (DisposableSchema db = new DisposableSchema("mig_resume")) {
            assertThrows(FlywayException.class, () -> withFailingMigration(db).migrate());
            db.execute("INSERT INTO gw2_account (account_id, display_name) VALUES "
                    + "('11111111-2222-3333-4444-555555555555', 'survivor')");

            SchemaGate.Result retry = SchemaGate.run(db.url(), db.user(), db.password(), db.name());

            assertEquals(SchemaGate.State.MANAGED, retry.state());
            assertEquals(List.of(), retry.appliedVersions(),
                    "the production chain is already at its latest version, so the retry applies nothing");
            assertEquals(List.of("survivor"), db.rows("SELECT display_name FROM gw2_account"),
                    "a retry after a failed migration must preserve the data committed before it");
            assertEquals(List.of("1", "2"), db.rows("SELECT version FROM " + SchemaGate.HISTORY_TABLE
                    + " WHERE version IS NOT NULL AND success ORDER BY installed_rank"));
        }
    }
}
