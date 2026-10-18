package infra.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-INFRA-004 acceptance criterion 2: the ownership rules the migrated schema must enforce in
 * the database itself, proved against real PostgreSQL in a disposable schema.
 *
 * <p>Expected behavior is taken from TARGET_ARCHITECTURE.md §7.2, not from the SQL under test: two
 * accounts may hold the same natural identifiers, a child may never reach another account's
 * character, an account-owned row may never name an unregistered GUID, and the inventory slot key
 * treats NULL parts as equal so duplicate slots are rejected rather than silently stored.
 */
class AccountSchemaOwnershipConstraintsTest {

    private static final String ACCOUNT_A = "aaaaaaaa-1111-2222-3333-444444444444";
    private static final String ACCOUNT_B = "bbbbbbbb-1111-2222-3333-444444444444";

    private DisposableSchema db;

    @BeforeEach
    void migrate() throws Exception {
        db = new DisposableSchema("acct_scope");
        SchemaGate.run(db.url(), db.user(), db.password(), db.name());
        db.execute("INSERT INTO gw2_account (account_id, display_name) VALUES "
                + "('" + ACCOUNT_A + "', 'A'), ('" + ACCOUNT_B + "', 'B')");
    }

    @AfterEach
    void drop() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void twoAccountsHoldTheSameNaturalIdentifiersWithoutColliding() throws Exception {
        db.execute("""
                INSERT INTO account_bank_slot (account_id, slot, item_id, count, fetched_at) VALUES
                  ('%1$s', 0, 100, 5, '2026-03-01T00:00:00Z'),
                  ('%2$s', 0, 200, 7, '2026-03-01T00:00:00Z')
                """.formatted(ACCOUNT_A, ACCOUNT_B));
        db.execute("""
                INSERT INTO account_material_stack (account_id, item_id, count, fetched_at) VALUES
                  ('%1$s', 100, 10, '2026-03-01T00:00:00Z'),
                  ('%2$s', 100, 20, '2026-03-01T00:00:00Z')
                """.formatted(ACCOUNT_A, ACCOUNT_B));
        db.execute("""
                INSERT INTO account_recipe_unlock (account_id, recipe_id, fetched_at) VALUES
                  ('%1$s', 9001, '2026-03-01T00:00:00Z'),
                  ('%2$s', 9001, '2026-03-01T00:00:00Z')
                """.formatted(ACCOUNT_A, ACCOUNT_B));
        db.execute("""
                INSERT INTO account_character (account_id, name, fetched_at) VALUES
                  ('%1$s', 'Shared Name', '2026-03-01T00:00:00Z'),
                  ('%2$s', 'Shared Name', '2026-03-01T00:00:00Z')
                """.formatted(ACCOUNT_A, ACCOUNT_B));

        assertEquals(List.of("100|10"), db.rows(
                "SELECT item_id || '|' || count FROM account_material_stack WHERE account_id = '" + ACCOUNT_A + "'"),
                "a scoped read must see only its own account's quantity");
        assertEquals(List.of("200"), db.rows(
                "SELECT item_id::text FROM account_bank_slot WHERE account_id = '" + ACCOUNT_B + "'"));

        assertThrows(SQLException.class, () -> db.execute(
                "INSERT INTO account_bank_slot (account_id, slot, item_id, count, fetched_at) VALUES "
                        + "('" + ACCOUNT_A + "', 0, 300, 1, '2026-03-02T00:00:00Z')"),
                "bank uniqueness is (account_id, slot), so the same account may not hold one slot twice");
        assertThrows(SQLException.class, () -> db.execute(
                "INSERT INTO account_character (account_id, name, fetched_at) VALUES "
                        + "('" + ACCOUNT_A + "', 'Shared Name', '2026-03-02T00:00:00Z')"),
                "a character name is unique within one account");
    }

    @Test
    void anAccountOwnedRowCannotNameAnUnregisteredAccount() {
        assertThrows(SQLException.class, () -> db.execute(
                "INSERT INTO account_bank_slot (account_id, slot, item_id, count, fetched_at) VALUES "
                        + "('cccccccc-1111-2222-3333-444444444444', 0, 100, 1, '2026-03-01T00:00:00Z')"),
                "every live account-owned row must reference the account registry");
    }

    @Test
    void aChildRowCannotReachACharacterOwnedByAnotherAccount() throws Exception {
        db.execute("INSERT INTO account_character (account_id, character_id, name, fetched_at) VALUES "
                + "('" + ACCOUNT_A + "', 5000, 'Owned By A', '2026-03-01T00:00:00Z')");

        assertThrows(SQLException.class, () -> db.execute("""
                        INSERT INTO account_character_discipline (account_id, character_id, discipline, rating)
                        VALUES ('%s', 5000, 'Weaponsmith', 400)
                        """.formatted(ACCOUNT_B)),
                "the composite parent key must reject account B claiming account A's character");
        assertThrows(SQLException.class, () -> db.execute("""
                        INSERT INTO account_character_item (account_id, character_id, location, bag_index,
                                                            slot_index, item_id, count)
                        VALUES ('%s', 5000, 'Inventory', 0, 0, 100, 1)
                        """.formatted(ACCOUNT_B)),
                "an inventory row must belong to the same account as its character");

        db.execute("""
                INSERT INTO account_character_discipline (account_id, character_id, discipline, rating)
                VALUES ('%s', 5000, 'Weaponsmith', 400)
                """.formatted(ACCOUNT_A));
        assertEquals(1, db.count("account_character_discipline"));
    }

    @Test
    void duplicateInventorySlotsAreRejectedIncludingWhenTheSlotPartsAreNull() throws Exception {
        db.execute("INSERT INTO account_character (account_id, character_id, name, fetched_at) VALUES "
                + "('" + ACCOUNT_A + "', 6000, 'Hero', '2026-03-01T00:00:00Z')");
        db.execute("""
                INSERT INTO account_character_item (account_id, character_id, location, bag_index, slot_index,
                                                    equipment_slot, item_id, count)
                VALUES ('%s', 6000, 'Equipped', NULL, NULL, NULL, 300, 1)
                """.formatted(ACCOUNT_A));

        assertThrows(SQLException.class, () -> db.execute("""
                        INSERT INTO account_character_item (account_id, character_id, location, bag_index,
                                                            slot_index, equipment_slot, item_id, count)
                        VALUES ('%s', 6000, 'Equipped', NULL, NULL, NULL, 200, 1)
                        """.formatted(ACCOUNT_A)),
                "NULLS NOT DISTINCT must make two all-NULL slot keys collide, which the legacy index did not");

        // Slot keys that genuinely differ remain storable, including one that differs only in a part
        // the other row leaves NULL.
        db.execute("""
                INSERT INTO account_character_item (account_id, character_id, location, bag_index, slot_index,
                                                    equipment_slot, item_id, count) VALUES
                  ('%1$s', 6000, 'Equipped', NULL, NULL, 'Helm', 200, 1),
                  ('%1$s', 6000, 'Inventory', 0, 3, NULL, 100, 25),
                  ('%1$s', 6000, 'Inventory', 0, 4, NULL, 100, 25)
                """.formatted(ACCOUNT_A));
        assertEquals(4, db.count("account_character_item"));
    }

    @Test
    void sourceStateRecordsAPositiveGenerationAndAnAccountScopedCompletionTime() throws Exception {
        db.execute("""
                INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES
                  ('%1$s', 'BANK', 1, '2026-03-01T00:00:00Z'),
                  ('%2$s', 'BANK', 1, '2026-03-02T00:00:00Z')
                """.formatted(ACCOUNT_A, ACCOUNT_B));

        assertThrows(SQLException.class, () -> db.execute("""
                        INSERT INTO account_source_state (account_id, source, generation, completed_at)
                        VALUES ('%s', 'MATERIALS', 0, '2026-03-01T00:00:00Z')
                        """.formatted(ACCOUNT_A)),
                "a generation advances from one, so zero is not a generation identifier");
        assertThrows(SQLException.class, () -> db.execute("""
                        INSERT INTO account_source_state (account_id, source, generation, completed_at)
                        VALUES ('%s', 'BANK', 2, '2026-03-03T00:00:00Z')
                        """.formatted(ACCOUNT_A)),
                "one durable row per (account, source)");

        assertEquals(List.of("1|2026-03-01 00:00:00+00"), db.rows(
                "SELECT generation || '|' || completed_at FROM account_source_state WHERE account_id = '"
                        + ACCOUNT_A + "'"));
    }

    @Test
    void registryActivityColumnsAreNullableUtcInstantsAndTheGuidIsTheKey() throws Exception {
        assertEquals(List.of("account_id:uuid:NO",
                        "display_name:text:YES",
                        "created_at:timestamptz:NO",
                        "last_seen_at:timestamptz:YES",
                        "last_successful_sync_at:timestamptz:YES"),
                db.rows("""
                        SELECT column_name || ':' || udt_name || ':' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'gw2_account'
                        ORDER BY ordinal_position
                        """),
                "missing legacy activity must stay representable as unknown, in UTC instants");

        // Registering the same GUID twice is idempotent rather than an error.
        db.execute("INSERT INTO gw2_account (account_id) VALUES ('" + ACCOUNT_A
                + "') ON CONFLICT (account_id) DO NOTHING");
        assertEquals(2, db.count("gw2_account"));
        assertTrue(db.scalarBoolean(
                "SELECT bool_and(last_seen_at IS NULL AND last_successful_sync_at IS NULL) FROM gw2_account"));
    }
}
