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

    /** One crafting discipline of one character, exactly as persisted. */
    public record Discipline(String discipline, int rating, boolean active) {}

    /**
     * One character's identity facts and crafting disciplines (STORY-CHAR-001). Nullable identity
     * columns stay null: nothing is defaulted or fabricated.
     */
    public record CharacterProfile(String name, String profession, String race, String gender,
                                   Integer level, java.time.Instant createdAt, List<Discipline> disciplines) {
        public CharacterProfile {
            disciplines = List.copyOf(disciplines);
        }
    }

    /**
     * The whole account roster with each character's disciplines, in two statements (one for the
     * characters, one for all their disciplines) regardless of roster size. Characters are ordered
     * by name then persisted id (no in-game slot order is retained); disciplines by discipline name.
     */
    public List<CharacterProfile> loadRoster(Connection con, model.AccountScope scope) throws SQLException {
        return load(con, scope, null);
    }

    /** One named character of the account, or empty; the lookup is exact and account-scoped. */
    public Optional<CharacterProfile> loadCharacter(Connection con, model.AccountScope scope, String name)
            throws SQLException {
        return load(con, scope, name).stream().findFirst();
    }

    private List<CharacterProfile> load(Connection con, model.AccountScope scope, String onlyName)
            throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "characters");
        String filter = onlyName == null ? "" : " AND c.name = ?";
        Map<Long, List<Discipline>> disciplines = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT cc.character_id, cc.discipline, cc.rating, cc.is_active
                FROM character_crafting cc
                JOIN characters c ON c.account_id = cc.account_id AND c.character_id = cc.character_id
                WHERE cc.account_id = ?""" + filter + """

                ORDER BY cc.character_id, cc.discipline""")) {
            ps.setObject(1, scope.accountId());
            if (onlyName != null) ps.setString(2, onlyName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    disciplines.computeIfAbsent(rs.getLong("character_id"), k -> new ArrayList<>())
                            .add(new Discipline(rs.getString("discipline"), rs.getInt("rating"),
                                    rs.getBoolean("is_active")));
                }
            }
        }
        List<CharacterProfile> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT c.character_id, c.name, c.profession, c.race, c.gender, c.level, c.created_at_gw
                FROM characters c
                WHERE c.account_id = ?""" + filter + """

                ORDER BY c.name, c.character_id""")) {
            ps.setObject(1, scope.accountId());
            if (onlyName != null) ps.setString(2, onlyName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int level = rs.getInt("level");
                    Integer boxedLevel = rs.wasNull() ? null : level;
                    Timestamp created = rs.getTimestamp("created_at_gw");
                    out.add(new CharacterProfile(rs.getString("name"), rs.getString("profession"),
                            rs.getString("race"), rs.getString("gender"), boxedLevel,
                            created == null ? null : created.toInstant(),
                            disciplines.getOrDefault(rs.getLong("character_id"), List.of())));
                }
            }
        }
        return out;
    }
}