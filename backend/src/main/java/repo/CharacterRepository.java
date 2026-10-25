package repo;

import java.sql.*;
import java.util.*;

public class CharacterRepository {

    public static class DiscRow {
        public final String charName;
        public final String discipline;
        public final int rating;
        public final boolean active;

        public DiscRow(String charName, String discipline, int rating, boolean active) {
            this.charName = charName;
            this.discipline = discipline;
            this.rating = rating;
            this.active = active;
        }
    }

    /**
     * All synced character names, ordered by name. Used to populate the "selected character"
     * control (UD-001) that feeds {@code InventoryRepository.loadOwnedInventoryForCharacter(...)}
     * (DOMAIN_SPEC.md section 11.1 / DQ-007).
     */
    public List<String> loadAllCharacterNames() throws SQLException {
        String sql = "SELECT name FROM characters ORDER BY name";

        List<String> out = new ArrayList<>();
        try (Connection con = Db.open()) {
            // A character name is unique within an account, not globally, so this roster read has
            // no meaning without an account scope on a migrated database (STORY-INFRA-004).
            ManagedSchema.rejectUnscopedAccountAccess(con, "characters without an account scope");
            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    out.add(rs.getString("name"));
                }
            }
        }
        return out;
    }

    /**
     * One account's character names, ordered by name (STORY-APP-015).
     *
     * <p>A character name is unique within an account, never globally
     * (TARGET_ARCHITECTURE.md §7.2), so this is the only form of the roster read that has a defined
     * answer on the versioned schema: two accounts may both own a character called "Shared Name" and
     * each sees only its own.
     */
    public List<String> loadAllCharacterNames(model.AccountScope scope) throws SQLException {
        try (Connection con = Db.open()) {
            return loadAllCharacterNames(con, scope);
        }
    }

    /** @see #loadAllCharacterNames(model.AccountScope) */
    public List<String> loadAllCharacterNames(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "characters");
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT name FROM characters WHERE account_id = ? ORDER BY name")) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString("name"));
            }
        }
        return out;
    }

    /**
     * One account's per-character discipline ratings (STORY-APP-015).
     *
     * <p>The join to the roster carries the account GUID in both halves, matching the composite
     * {@code (account_id, character_id)} parent key: joining on {@code character_id} alone would let
     * a child row find a character owned by another account, which the schema's composite foreign
     * key already forbids on write and this scoping forbids on read.
     */
    public List<DiscRow> loadAllCharacterCrafting(model.AccountScope scope) throws SQLException {
        try (Connection con = Db.open()) {
            return loadAllCharacterCrafting(con, scope);
        }
    }

    /** @see #loadAllCharacterCrafting(model.AccountScope) */
    public List<DiscRow> loadAllCharacterCrafting(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "character_crafting");
        String sql = """
            SELECT c.name AS char_name, cc.discipline, cc.rating, cc.is_active
            FROM character_crafting cc
            JOIN characters c
              ON c.account_id = cc.account_id AND c.character_id = cc.character_id
            WHERE cc.account_id = ?
            ORDER BY cc.discipline, cc.rating DESC, c.name
        """;

        List<DiscRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DiscRow(
                            rs.getString("char_name"),
                            rs.getString("discipline"),
                            rs.getInt("rating"),
                            rs.getBoolean("is_active")));
                }
            }
        }
        return out;
    }

    public List<DiscRow> loadAllCharacterCrafting() throws SQLException {
        try (Connection con = Db.open()) {
            return loadAllCharacterCrafting(con);
        }
    }

    /**
     * Same query as {@link #loadAllCharacterCrafting()}, but on a caller-supplied connection, so one
     * calculation can read every input it needs inside a single consistent database snapshot
     * (TARGET_ARCHITECTURE.md §9.1: readers must not mix input generations).
     */
    public List<DiscRow> loadAllCharacterCrafting(Connection con) throws SQLException {
        ManagedSchema.rejectUnscopedAccountAccess(con, "character_crafting without an account scope");
        String sql = """
            SELECT c.name AS char_name, cc.discipline, cc.rating, cc.is_active
            FROM character_crafting cc
            JOIN characters c ON c.character_id = cc.character_id
            ORDER BY cc.discipline, cc.rating DESC, c.name
        """;

        List<DiscRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(new DiscRow(
                        rs.getString("char_name"),
                        rs.getString("discipline"),
                        rs.getInt("rating"),
                        rs.getBoolean("is_active")
                ));
            }
        }
        return out;
    }
}