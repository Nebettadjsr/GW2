package repo;

import model.AccountScope;
import model.BuildResourceType;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Shared (not account-scoped) display metadata for build references (STORY-CHAR-009), plus the
 * account's stored reference sets. Every lookup is one batched statement per namespace.
 */
public class BuildMetadataRepository {

    public record SpecRow(int id, String name, String profession, boolean elite, String iconUrl,
                          List<Integer> minorTraits, List<Integer> majorTraits) {}
    public record TraitRow(int id, String name, String iconUrl, Integer specializationId, Integer tier, String slot) {}
    public record NamedRow(int id, String name, String iconUrl) {}
    public record LegendRow(String id, int swapSkillId) {}

    /** A set of native references by namespace. */
    public record Refs(Set<Integer> specializations, Set<Integer> traits, Set<Integer> skills,
                       Set<Integer> pets, Set<String> legends) {
        public static Refs empty() {
            return new Refs(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }
        public boolean isEmpty() {
            return specializations.isEmpty() && traits.isEmpty() && skills.isEmpty() && pets.isEmpty()
                    && legends.isEmpty();
        }
    }

    public record Metadata(Map<Integer, SpecRow> specializations, Map<Integer, TraitRow> traits,
                           Map<Integer, NamedRow> skills, Map<Integer, NamedRow> pets,
                           Map<String, LegendRow> legends) {
        public static Metadata empty() {
            return new Metadata(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    // ---- references ----

    /** All distinct references stored for the account's saved builds. */
    public Refs storedRefs(Connection con, AccountScope scope) throws SQLException {
        Refs r = Refs.empty();
        collectInts(con, "SELECT DISTINCT specialization_id FROM character_build_tab_specializations "
                + "WHERE account_id = ? AND specialization_id IS NOT NULL", scope, r.specializations());
        collectInts(con, "SELECT DISTINCT t FROM character_build_tab_specializations, unnest(traits) AS t "
                + "WHERE account_id = ? AND t IS NOT NULL", scope, r.traits());
        for (String col : new String[]{"land_heal", "land_elite", "aquatic_heal", "aquatic_elite"}) {
            collectInts(con, "SELECT DISTINCT " + col + " FROM character_build_tabs WHERE account_id = ? AND "
                    + col + " IS NOT NULL", scope, r.skills());
        }
        for (String col : new String[]{"land_utilities", "aquatic_utilities"}) {
            collectInts(con, "SELECT DISTINCT s FROM character_build_tabs, unnest(" + col + ") AS s "
                    + "WHERE account_id = ? AND s IS NOT NULL", scope, r.skills());
        }
        for (String col : new String[]{"land_pets", "aquatic_pets"}) {
            collectInts(con, "SELECT DISTINCT p FROM character_build_tabs, unnest(" + col + ") AS p "
                    + "WHERE account_id = ? AND p IS NOT NULL", scope, r.pets());
        }
        for (String col : new String[]{"land_legends", "aquatic_legends"}) {
            try (PreparedStatement ps = con.prepareStatement("SELECT DISTINCT l FROM character_build_tabs, unnest("
                    + col + ") AS l WHERE account_id = ? AND l IS NOT NULL")) {
                ps.setObject(1, scope.accountId());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) r.legends().add(rs.getString(1)); }
            }
        }
        return r;
    }

    private static void collectInts(Connection con, String sql, AccountScope scope, Set<Integer> out)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getInt(1)); }
        }
    }

    public Set<Integer> present(Connection con, BuildResourceType type, Collection<Integer> ids) throws SQLException {
        Set<Integer> out = new TreeSet<>();
        if (ids.isEmpty()) return out;
        try (PreparedStatement ps = con.prepareStatement("SELECT " + type.idColumn() + " FROM " + type.table()
                + " WHERE " + type.idColumn() + " = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getInt(1)); }
        }
        return out;
    }

    public Set<String> presentLegends(Connection con, Collection<String> ids) throws SQLException {
        Set<String> out = new TreeSet<>();
        if (ids.isEmpty()) return out;
        try (PreparedStatement ps = con.prepareStatement("SELECT legend_id FROM build_legends WHERE legend_id = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("text", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        }
        return out;
    }

    /** Minor and major trait choice ids of the stored specializations among {@code specIds}. */
    public Set<Integer> traitClosure(Connection con, Collection<Integer> specIds) throws SQLException {
        Set<Integer> out = new TreeSet<>();
        if (specIds.isEmpty()) return out;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT minor_traits, major_traits FROM build_specializations WHERE specialization_id = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", specIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { out.addAll(ints(rs.getArray(1))); out.addAll(ints(rs.getArray(2))); }
            }
        }
        return out;
    }

    public Set<Integer> swapSkills(Connection con, Collection<String> legendIds) throws SQLException {
        Set<Integer> out = new TreeSet<>();
        if (legendIds.isEmpty()) return out;
        try (PreparedStatement ps = con.prepareStatement("SELECT swap_skill_id FROM build_legends WHERE legend_id = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("text", legendIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getInt(1)); }
        }
        return out;
    }

    // ---- writes (idempotent upserts; a failed batch leaves prior rows intact) ----

    public void upsertSpecializations(Connection con, List<SpecRow> rows, Instant at) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO build_specializations (specialization_id, name, profession, elite, icon_url,
                    minor_traits, major_traits, fetched_at) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (specialization_id) DO UPDATE SET name = EXCLUDED.name, profession = EXCLUDED.profession,
                    elite = EXCLUDED.elite, icon_url = EXCLUDED.icon_url, minor_traits = EXCLUDED.minor_traits,
                    major_traits = EXCLUDED.major_traits, fetched_at = EXCLUDED.fetched_at""")) {
            for (SpecRow r : rows) {
                ps.setInt(1, r.id()); ps.setString(2, r.name()); ps.setString(3, r.profession());
                ps.setBoolean(4, r.elite()); ps.setString(5, r.iconUrl());
                ps.setArray(6, con.createArrayOf("integer", r.minorTraits().toArray()));
                ps.setArray(7, con.createArrayOf("integer", r.majorTraits().toArray()));
                ps.setTimestamp(8, Timestamp.from(at));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void upsertTraits(Connection con, List<TraitRow> rows, Instant at) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO build_traits (trait_id, name, icon_url, specialization_id, tier, slot, fetched_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (trait_id) DO UPDATE SET name = EXCLUDED.name, icon_url = EXCLUDED.icon_url,
                    specialization_id = EXCLUDED.specialization_id, tier = EXCLUDED.tier, slot = EXCLUDED.slot,
                    fetched_at = EXCLUDED.fetched_at""")) {
            for (TraitRow r : rows) {
                ps.setInt(1, r.id()); ps.setString(2, r.name()); ps.setString(3, r.iconUrl());
                ps.setObject(4, r.specializationId()); ps.setObject(5, r.tier()); ps.setString(6, r.slot());
                ps.setTimestamp(7, Timestamp.from(at));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void upsertNamed(Connection con, BuildResourceType type, List<NamedRow> rows, Instant at) throws SQLException {
        if (type != BuildResourceType.SKILLS && type != BuildResourceType.PETS) throw new IllegalArgumentException(type.name());
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO " + type.table() + " (" + type.idColumn()
                + ", name, icon_url, fetched_at) VALUES (?,?,?,?) ON CONFLICT (" + type.idColumn()
                + ") DO UPDATE SET name = EXCLUDED.name, icon_url = EXCLUDED.icon_url, fetched_at = EXCLUDED.fetched_at")) {
            for (NamedRow r : rows) {
                ps.setInt(1, r.id()); ps.setString(2, r.name()); ps.setString(3, r.iconUrl());
                ps.setTimestamp(4, Timestamp.from(at));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void upsertLegends(Connection con, List<LegendRow> rows, Instant at) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO build_legends (legend_id, swap_skill_id, fetched_at) VALUES (?,?,?)
                ON CONFLICT (legend_id) DO UPDATE SET swap_skill_id = EXCLUDED.swap_skill_id,
                    fetched_at = EXCLUDED.fetched_at""")) {
            for (LegendRow r : rows) {
                ps.setString(1, r.id()); ps.setInt(2, r.swapSkillId()); ps.setTimestamp(3, Timestamp.from(at));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---- reads ----

    /** Metadata of the given refs plus the trait-choice closure of the given specializations: one query per namespace. */
    public Metadata load(Connection con, Refs refs) throws SQLException {
        Map<Integer, SpecRow> specs = new HashMap<>();
        if (!refs.specializations().isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement("""
                    SELECT specialization_id, name, profession, elite, icon_url, minor_traits, major_traits
                    FROM build_specializations WHERE specialization_id = ANY(?)""")) {
                ps.setArray(1, con.createArrayOf("integer", refs.specializations().toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) specs.put(rs.getInt(1), new SpecRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getBoolean(4), rs.getString(5), ints(rs.getArray(6)), ints(rs.getArray(7))));
                }
            }
        }
        Set<Integer> traitIds = new TreeSet<>(refs.traits());
        for (SpecRow s : specs.values()) { traitIds.addAll(s.minorTraits()); traitIds.addAll(s.majorTraits()); }
        Map<Integer, TraitRow> traits = new HashMap<>();
        if (!traitIds.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement("""
                    SELECT trait_id, name, icon_url, specialization_id, tier, slot FROM build_traits
                    WHERE trait_id = ANY(?)""")) {
                ps.setArray(1, con.createArrayOf("integer", traitIds.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Integer spec = (Integer) rs.getObject(4);
                        Integer tier = (Integer) rs.getObject(5);
                        traits.put(rs.getInt(1), new TraitRow(rs.getInt(1), rs.getString(2), rs.getString(3), spec,
                                tier, rs.getString(6)));
                    }
                }
            }
        }
        Map<String, LegendRow> legends = new HashMap<>();
        if (!refs.legends().isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT legend_id, swap_skill_id FROM build_legends WHERE legend_id = ANY(?)")) {
                ps.setArray(1, con.createArrayOf("text", refs.legends().toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) legends.put(rs.getString(1), new LegendRow(rs.getString(1), rs.getInt(2)));
                }
            }
        }
        Set<Integer> skillIds = new TreeSet<>(refs.skills());
        for (LegendRow l : legends.values()) skillIds.add(l.swapSkillId());
        return new Metadata(specs, traits, named(con, BuildResourceType.SKILLS, skillIds),
                named(con, BuildResourceType.PETS, refs.pets()), legends);
    }

    private Map<Integer, NamedRow> named(Connection con, BuildResourceType type, Collection<Integer> ids) throws SQLException {
        Map<Integer, NamedRow> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try (PreparedStatement ps = con.prepareStatement("SELECT " + type.idColumn() + ", name, icon_url FROM "
                + type.table() + " WHERE " + type.idColumn() + " = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getInt(1), new NamedRow(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        return out;
    }

    /** Retained image source of one typed resource; empty when unknown or blank. */
    public Optional<String> findIconSource(Connection con, BuildResourceType type, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT icon_url FROM " + type.table() + " WHERE "
                + type.idColumn() + " = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String url = rs.getString(1);
                return url == null || url.isBlank() ? Optional.empty() : Optional.of(url);
            }
        }
    }

    public Optional<String> findIconSource(BuildResourceType type, int id) throws SQLException {
        try (Connection con = Db.open()) { return findIconSource(con, type, id); }
    }

    private static List<Integer> ints(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] raw = (Object[]) array.getArray();
        List<Integer> out = new ArrayList<>(raw.length);
        for (Object o : raw) if (o != null) out.add(((Number) o).intValue());
        return out;
    }
}
