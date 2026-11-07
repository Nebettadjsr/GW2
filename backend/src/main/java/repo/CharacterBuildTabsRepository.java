package repo;

import model.AccountScope;
import parser.CharacterBuildTabsParser.Specialization;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Account-scoped, read-only access to one selected character's saved build tabs (STORY-CHAR-007). Raw
 * stored identifiers only: no metadata is joined, so nothing is named or iconised here.
 */
public class CharacterBuildTabsRepository {

    public record SkillsView(Integer heal, List<Integer> utilities, Integer elite) {}

    public record TabView(int number, String name, String profession, boolean active,
                          List<Specialization> specializations, SkillsView skills, SkillsView aquaticSkills,
                          List<String> legends, List<String> aquaticLegends,
                          List<Integer> pets, List<Integer> aquaticPets) {}

    public record Snapshot(boolean characterFound, Optional<Long> coveredParentGeneration, List<TabView> tabs,
                           Optional<AccountSourceStateRepository.SourceState> parent,
                           Optional<AccountSourceStateRepository.SourceState> builds) {}

    public Snapshot load(Connection con, AccountScope scope, String name) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "character build tabs");
        var states = new AccountSourceStateRepository();
        var parent = states.read(con, scope, AccountSyncStateRepository.CHARACTERS);
        var builds = states.read(con, scope, AccountSyncStateRepository.CHARACTER_BUILD_TABS);
        Long characterId = null;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT character_id FROM characters WHERE account_id = ? AND name = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) characterId = rs.getLong(1);
            }
        }
        if (characterId == null) return new Snapshot(false, Optional.empty(), List.of(), parent, builds);
        Optional<Long> covered = Optional.empty();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT parent_generation FROM character_build_coverage WHERE account_id = ?")) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) covered = Optional.of(rs.getLong(1));
            }
        }
        if (builds.isEmpty() || covered.isEmpty()) {
            return new Snapshot(true, covered, List.of(), parent, builds);
        }
        return new Snapshot(true, covered, tabs(con, scope, characterId), parent, builds);
    }

    private List<TabView> tabs(Connection con, AccountScope scope, long characterId) throws SQLException {
        Map<Integer, List<Specialization>> specs = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT tab_number, specialization_id, traits FROM character_build_tab_specializations
                WHERE account_id = ? AND character_id = ? ORDER BY tab_number, position""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(2);
                    Integer specId = rs.wasNull() ? null : id;
                    specs.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>())
                            .add(new Specialization(specId, ints(rs.getArray(3))));
                }
            }
        }
        List<TabView> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT tab_number, name, profession, is_active, land_heal, land_elite, land_utilities,
                       aquatic_heal, aquatic_elite, aquatic_utilities, land_legends, aquatic_legends,
                       land_pets, aquatic_pets
                FROM character_build_tabs
                WHERE account_id = ? AND character_id = ? ORDER BY tab_number""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int number = rs.getInt(1);
                    out.add(new TabView(number, rs.getString(2), rs.getString(3), rs.getBoolean(4),
                            specs.getOrDefault(number, List.of()),
                            new SkillsView(nullableInt(rs, 5), ints(rs.getArray(7)), nullableInt(rs, 6)),
                            new SkillsView(nullableInt(rs, 8), ints(rs.getArray(10)), nullableInt(rs, 9)),
                            strings(rs.getArray(11)), strings(rs.getArray(12)),
                            ints(rs.getArray(13)), ints(rs.getArray(14))));
                }
            }
        }
        return out;
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    /** Null array stays null; null elements stay null. */
    private static List<Integer> ints(Array array) throws SQLException {
        if (array == null) return null;
        Object[] raw = (Object[]) array.getArray();
        Integer[] out = new Integer[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i] == null ? null : ((Number) raw[i]).intValue();
        return Arrays.asList(out);
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return null;
        Object[] raw = (Object[]) array.getArray();
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i] == null ? null : raw[i].toString();
        return Arrays.asList(out);
    }
}
