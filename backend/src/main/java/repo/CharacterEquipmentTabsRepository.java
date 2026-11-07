package repo;

import infra.icons.IconSourcePolicy;
import model.AccountScope;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Account-scoped, read-only access to the saved equipment tabs of one selected character
 * (STORY-CHAR-005). Canonical item display metadata is joined from the shared {@code items} table at
 * read time and is never copied into the account-owned tab tables.
 */
public class CharacterEquipmentTabsRepository {

    /** Shared canonical metadata of one item; {@code resolved} is false when it is unavailable. */
    public record ItemRef(int itemId, boolean resolved, String name, String type, String rarity, String icon) {}

    public record Entry(String slot, ItemRef item, String location, List<ItemRef> upgrades,
                        List<ItemRef> infusions, Integer statId, Map<String, Integer> statAttributes,
                        String binding, String boundTo) {}

    public record TabView(int number, String name, boolean active, List<Entry> equipment) {}

    /**
     * @param characterFound   whether the account owns a character with the requested name
     * @param coveredParentGeneration the CHARACTERS generation the stored coverage describes, empty if none
     * @param tabs             the character's tabs, ordered by tab number
     */
    public record Snapshot(boolean characterFound, Optional<Long> coveredParentGeneration, List<TabView> tabs,
                           Optional<AccountSourceStateRepository.SourceState> parent,
                           Optional<AccountSourceStateRepository.SourceState> equipment) {}

    public Snapshot load(Connection con, AccountScope scope, String name) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "character equipment tabs");
        var states = new AccountSourceStateRepository();
        var parent = states.read(con, scope, AccountSyncStateRepository.CHARACTERS);
        var equipment = states.read(con, scope, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS);
        Long characterId = null;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT character_id FROM characters WHERE account_id = ? AND name = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) characterId = rs.getLong(1);
            }
        }
        if (characterId == null) return new Snapshot(false, Optional.empty(), List.of(), parent, equipment);
        Optional<Long> covered = Optional.empty();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT parent_generation FROM character_equipment_coverage WHERE account_id = ?")) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) covered = Optional.of(rs.getLong(1));
            }
        }
        // A marker without coverage (or vice versa) is not coverage; the service requires both.
        if (equipment.isEmpty() || covered.isEmpty()) {
            return new Snapshot(true, covered, List.of(), parent, equipment);
        }
        return new Snapshot(true, covered, tabs(con, scope, characterId), parent, equipment);
    }

    private record RawEntry(int tab, String slot, int itemId, String location, List<Integer> upgrades,
                            List<Integer> infusions, Integer statId, String attributes, String binding,
                            String boundTo) {}

    private List<TabView> tabs(Connection con, AccountScope scope, long characterId) throws SQLException {
        record RawTab(int number, String name, boolean active) {}
        List<RawTab> rawTabs = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT tab_number, name, is_active FROM character_equipment_tabs
                WHERE account_id = ? AND character_id = ? ORDER BY tab_order""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rawTabs.add(new RawTab(rs.getInt(1), rs.getString(2), rs.getBoolean(3)));
            }
        }
        List<RawEntry> raw = new ArrayList<>();
        Set<Integer> ids = new LinkedHashSet<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT tab_number, slot, item_id, location, upgrade_ids, infusion_ids, stat_id,
                       stat_attributes::text, binding, bound_to
                FROM character_equipment_tab_entries
                WHERE account_id = ? AND character_id = ? ORDER BY tab_number, entry_order""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int stat = rs.getInt(7);
                    Integer statId = rs.wasNull() ? null : stat;
                    var e = new RawEntry(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                            ints(rs.getArray(5)), ints(rs.getArray(6)), statId, rs.getString(8),
                            rs.getString(9), rs.getString(10));
                    ids.add(e.itemId());
                    ids.addAll(e.upgrades());
                    ids.addAll(e.infusions());
                    raw.add(e);
                }
            }
        }
        Map<Integer, ItemRef> refs = metadata(con, ids);
        Map<Integer, List<Entry>> byTab = new LinkedHashMap<>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (RawEntry e : raw) {
            Map<String, Integer> attributes = null;
            if (e.attributes() != null) {
                attributes = new LinkedHashMap<>();
                try {
                    var node = mapper.readTree(e.attributes());
                    var it = node.fields();
                    while (it.hasNext()) {
                        var f = it.next();
                        attributes.put(f.getKey(), f.getValue().intValue());
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
                    throw new SQLException("Stored equipment attributes are unreadable", bad);
                }
            }
            byTab.computeIfAbsent(e.tab(), k -> new ArrayList<>()).add(new Entry(
                    e.slot(), refs.get(e.itemId()), e.location(),
                    e.upgrades().stream().map(refs::get).toList(), e.infusions().stream().map(refs::get).toList(),
                    e.statId(), attributes, e.binding(), e.boundTo()));
        }
        List<TabView> out = new ArrayList<>();
        for (RawTab t : rawTabs) {
            out.add(new TabView(t.number(), t.name(), t.active(), byTab.getOrDefault(t.number(), List.of())));
        }
        return out;
    }

    private static Map<Integer, ItemRef> metadata(Connection con, Set<Integer> ids) throws SQLException {
        Map<Integer, ItemRef> refs = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT item_id, name, type, rarity, icon_url FROM items WHERE item_id = ANY(?)")) {
                ps.setArray(1, con.createArrayOf("integer", ids.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        refs.put(rs.getInt(1), new ItemRef(rs.getInt(1), complete(name, rs.getString(3), rs.getString(4)), name, rs.getString(3),
                                rs.getString(4), IconSourcePolicy.accept(rs.getString(5)).map(infra.icons.IconSource::canonicalUrl).orElse(null)));
                    }
                }
            }
        }
        for (int id : ids) refs.putIfAbsent(id, new ItemRef(id, false, null, null, null, null));
        return refs;
    }

    /** Required display facts: a name alone does not make an item resolved. */
    private static boolean complete(String name, String type, String rarity) {
        return name != null && !name.isBlank() && type != null && !type.isBlank()
                && rarity != null && !rarity.isBlank();
    }

    private static List<Integer> ints(Array array) throws SQLException {
        List<Integer> out = new ArrayList<>();
        if (array == null) return out;
        for (Object o : (Object[]) array.getArray()) out.add(((Number) o).intValue());
        return out;
    }
}
