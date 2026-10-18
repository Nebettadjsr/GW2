package infra.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criteria 1, 2 and 6: the production Flyway chain against isolated real
 * PostgreSQL.
 *
 * <p>Covers the three states the gate distinguishes, that an applied migration is immutable and a
 * checksum mismatch is never repaired automatically, and that the migrated account schema holds no
 * credential of any kind. Every check runs in its own disposable schema, so the developer's normal
 * database content is neither read nor written (docs/TEST_STRATEGY.md §31.2).
 */
class SchemaMigrationChainTest {

    @Test
    void anEmptyDatabaseExecutesTheCompleteChainAndASecondStartupOnlyValidates() throws Exception {
        try (DisposableSchema db = new DisposableSchema("mig_empty")) {
            SchemaGate.Result first = SchemaGate.run(db.url(), db.user(), db.password(), db.name());

            assertEquals(SchemaGate.State.EMPTY, first.state());
            assertEquals(List.of("1 global reference and economy",
                            "2 account registry and scoped account data"),
                    first.appliedVersions(),
                    "an empty database must execute the complete versioned chain, in order");

            Map<String, String> shape = db.liveSchemaShape();
            assertTrue(shape.keySet().containsAll(List.of(
                            "items", "recipes", "recipe_ingredients", "tp_prices", "tp_tradeable_items",
                            "material_categories", "material_category_items",
                            "gw2_account", "account_source_state", "account_bank_slot",
                            "account_material_stack", "account_recipe_unlock", "account_luck_total",
                            "account_character", "account_character_discipline",
                            "account_character_recipe", "account_character_item",
                            SchemaGate.HISTORY_TABLE)),
                    "migrated relations: " + shape.keySet());

            long historyRows = db.count(SchemaGate.HISTORY_TABLE);
            String historyDigest = db.scalarText(
                    "SELECT md5(string_agg(version || ':' || checksum, ',' ORDER BY installed_rank))"
                            + " FROM " + SchemaGate.HISTORY_TABLE + " WHERE version IS NOT NULL");

            SchemaGate.Result second = SchemaGate.run(db.url(), db.user(), db.password(), db.name());

            assertEquals(SchemaGate.State.MANAGED, second.state());
            assertEquals(List.of(), second.appliedVersions(), "a current database must apply nothing");
            assertEquals(historyRows, db.count(SchemaGate.HISTORY_TABLE),
                    "validating a current database must not write schema history");
            assertEquals(historyDigest, db.scalarText(
                            "SELECT md5(string_agg(version || ':' || checksum, ',' ORDER BY installed_rank))"
                                    + " FROM " + SchemaGate.HISTORY_TABLE + " WHERE version IS NOT NULL"),
                    "the recorded versions and their checksums must be immutable");
            assertEquals(shape, db.liveSchemaShape(), "a second startup must not change the schema");
        }
    }

    @Test
    void aLegacyDatabaseIsReportedForExplicitAdoptionAndLeftCompletelyUntouched() throws Exception {
        try (DisposableSchema db = new DisposableSchema("mig_legacy")) {
            db.applyLegacy("optional_material_catalog.sql", "optional_account_materials_sync.sql",
                    "optional_account_luck.sql", "optional_account_sync_state.sql");
            Map<String, String> before = db.liveSchemaShape();
            long bankRows = db.count("account_bank");

            SchemaGate.Result result = SchemaGate.run(db.url(), db.user(), db.password(), db.name());

            assertEquals(SchemaGate.State.LEGACY_UNMANAGED, result.state());
            assertFalse(result.migrated(), "an unmanaged legacy schema must not be migrated by startup");
            assertEquals(List.of(), result.appliedVersions());
            assertTrue(result.diagnostics().contains("LegacySchemaAdoptionCli"),
                    "the diagnostic must name the explicit adoption operation: " + result.diagnostics());
            assertTrue(result.diagnostics().contains("--backup=")
                            && result.diagnostics().contains("--writers-stopped="),
                    "the diagnostic must name the adoption prerequisites: " + result.diagnostics());

            assertFalse(before.containsKey(SchemaGate.HISTORY_TABLE));
            assertEquals(before, db.liveSchemaShape(),
                    "nothing may be created, baselined or dropped on a legacy database");
            assertEquals(bankRows, db.count("account_bank"), "no legacy row may be touched");
        }
    }

    @Test
    void anEditedAppliedMigrationFailsValidationAndIsNeverRepairedAutomatically() throws Exception {
        try (DisposableSchema db = new DisposableSchema("mig_checksum")) {
            SchemaGate.run(db.url(), db.user(), db.password(), db.name());
            db.execute("UPDATE " + SchemaGate.HISTORY_TABLE + " SET checksum = 123456789 WHERE version = '1'");

            SchemaGateException refused = assertThrows(SchemaGateException.class,
                    () -> SchemaGate.run(db.url(), db.user(), db.password(), db.name()));

            assertTrue(refused.getMessage().toLowerCase().contains("checksum"),
                    "the refusal must name the mismatch: " + refused.getMessage());
            assertEquals(123456789L, db.scalarLong(
                            "SELECT checksum FROM " + SchemaGate.HISTORY_TABLE + " WHERE version = '1'"),
                    "a mismatching checksum must be left alone, not silently repaired");
        }
    }

    @Test
    void theSharedFlywayConfigurationKeepsBaselineAndCleanDisabled() {
        var configuration = SchemaGate.flyway("jdbc:postgresql://127.0.0.1:1/unused", "u", "p", null)
                .getConfiguration();

        assertFalse(configuration.isBaselineOnMigrate(),
                "automatic baselining would remove the protection against migrating the wrong database");
        assertTrue(configuration.isCleanDisabled(), "destructive clean must be unreachable");
        assertTrue(configuration.isValidateOnMigrate(), "history and checksums must be validated");
        assertFalse(configuration.isOutOfOrder(), "the chain is linear");
        assertEquals("1", configuration.getBaselineVersion().getVersion(),
                "a supported legacy database is baselined at the legacy global schema version");
    }

    @Test
    void theMigratedAccountSchemaStoresNoCredentialSessionOrApplicationUserField() throws Exception {
        try (DisposableSchema db = new DisposableSchema("mig_nocreds")) {
            SchemaGate.run(db.url(), db.user(), db.password(), db.name());

            List<String> forbidden = db.rows("""
                    SELECT table_name || '.' || column_name
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND (column_name ~* '(api_?key|token|password|passwd|secret|session|credential|verifier)'
                           OR table_name ~* '(session|credential|api_?key|user_account|app_user)')
                    ORDER BY 1
                    """);

            assertEquals(List.of(), forbidden,
                    "validated key possession is the only application identity; nothing here is a store for it");
        }
    }
}
