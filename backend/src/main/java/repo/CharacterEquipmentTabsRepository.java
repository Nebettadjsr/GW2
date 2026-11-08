package repo;

import domain.EquipmentTotals;
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
    public record ItemRef(int itemId, boolean resolved, String name, String type, String rarity, String icon,
                          Details details) {
        public ItemRef(int itemId, boolean resolved, String name, String type, String rarity, String icon) {
            this(itemId, resolved, name, type, rarity, icon, null);
        }
    }

    /** Slim stored public details of an item, upgrade or infusion; absent until they have been fetched. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Details(String description, Integer level, String subtype, String weightClass, Integer defense,
                          Integer minPower, Integer maxPower, Map<String, Integer> attributes,
                          List<String> effects, List<String> runeBonuses, Integer vendorValue) {}

    /**
     * @param statAttributes effective item-only attributes (selected override, else fixed, else itemstats)
     * @param runeCount      armor entries with a rune: how many land armor pieces of the tab carry that rune
     */
    public record Entry(String slot, ItemRef item, String location, List<ItemRef> upgrades,
                        List<ItemRef> infusions, Integer statId, Map<String, Integer> statAttributes,
                        String binding, String boundTo, Integer runeCount) {
        public Entry(String slot, ItemRef item, String location, List<ItemRef> upgrades, List<ItemRef> infusions,
                     Integer statId, Map<String, Integer> statAttributes, String binding, String boundTo) {
            this(slot, item, location, upgrades, infusions, statId, statAttributes, binding, boundTo, null);
        }
    }

    /** Backend-derived totals: {@code scope} BASE_PLUS_EQUIPMENT with land weapon sets A and B separately. */
    public record Stats(String scope, Map<String, EquipmentTotals.SetResult> weaponSets) {}

    public record TabView(int number, String name, boolean active, List<Entry> equipment, Stats stats) {
        public TabView(int number, String name, boolean active, List<Entry> equipment) {
            this(number, name, active, equipment, null);
        }
    }

    /**
     * @param characterFound   whether the account owns a character with the requested name
     * @param coveredParentGeneration the CHARACTERS generation the stored coverage describes, empty if none
     * @param tabs             the character's tabs, ordered by tab number
     */
    public record Snapshot(boolean characterFound, Optional<Long> coveredParentGeneration, List<TabView> tabs,
                           Optional<AccountSourceStateRepository.SourceState> parent,
                           Optional<AccountSourceStateRepository.SourceState> equipment,
                           List<Entry> sharedEquipment) {
        public Snapshot(boolean characterFound, Optional<Long> coveredParentGeneration, List<TabView> tabs,
                        Optional<AccountSourceStateRepository.SourceState> parent,
                        Optional<AccountSourceStateRepository.SourceState> equipment) {
            this(characterFound, coveredParentGeneration, tabs, parent, equipment, List.of());
        }
    }

    public Snapshot load(Connection con, AccountScope scope, String name) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "character equipment tabs");
        var states = new AccountSourceStateRepository();
        var parent = states.read(con, scope, AccountSyncStateRepository.CHARACTERS);
        var equipment = states.read(con, scope, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS);
        Long characterId = null;
        String profession = null;
        Integer level = null;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT character_id, profession, level FROM characters WHERE account_id = ? AND name = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    characterId = rs.getLong(1);
                    profession = rs.getString(2);
                    int l = rs.getInt(3);
                    level = rs.wasNull() ? null : l;
                }
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
        return tabs(con, scope, characterId, profession, level, covered, parent, equipment);
    }

    /** Character-wide equipment slots that equipment tabs often omit; read from the CHARACTERS data only. */
    public static final List<String> SHARED_SLOTS = List.of("FishingRod", "FishingBait", "FishingLure", "PowerCore",
            "SensoryArray", "ServiceChip", "Sickle", "Axe", "Pick");

    private record RawEntry(int tab, String slot, int itemId, String location, List<Integer> upgrades,
                            List<Integer> infusions, Integer statId, String attributes, String binding,
                            String boundTo) {}

    private Snapshot tabs(Connection con, AccountScope scope, long characterId, String profession, Integer level,
                          Optional<Long> covered, Optional<AccountSourceStateRepository.SourceState> parent,
                          Optional<AccountSourceStateRepository.SourceState> equipment) throws SQLException {
        record RawTab(int number, String name, boolean active) {}
        List<RawTab> rawTabs = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT tab_number, name, is_active FROM character_equipment_tabs
                WHERE account_id = ? AND character_id = ? ORDER BY tab_number""")) {
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
        record RawShared(String slot, int itemId, String binding, String boundTo) {}
        List<RawShared> sharedRaw = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT equipment_slot, item_id, binding, bound_to FROM character_items
                WHERE account_id = ? AND character_id = ? AND location = 'EQUIPMENT' AND equipment_slot = ANY(?)
                ORDER BY equipment_slot""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            ps.setArray(3, con.createArrayOf("text", SHARED_SLOTS.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sharedRaw.add(new RawShared(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)));
                    ids.add(rs.getInt(2));
                }
            }
        }
        Map<Integer, ItemRef> refs = metadata(con, ids);
        Map<Integer, Double> adjustments = new LinkedHashMap<>();
        Map<Integer, Integer> fixedStats = new LinkedHashMap<>();
        refs = withDetails(con, refs, adjustments, fixedStats);
        Set<Integer> statIds = new LinkedHashSet<>();
        for (RawEntry e : raw) if (e.statId() != null) statIds.add(e.statId());
        Map<Integer, List<StatAttr>> stats = itemStats(con, statIds);
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
            ItemRef item = refs.get(e.itemId());
            Map<String, Integer> effective = effective(item, attributes, e.statId(), adjustments, fixedStats, stats);
            byTab.computeIfAbsent(e.tab(), k -> new ArrayList<>()).add(new Entry(
                    e.slot(), item, e.location(),
                    e.upgrades().stream().map(refs::get).toList(), e.infusions().stream().map(refs::get).toList(),
                    e.statId(), effective, e.binding(), e.boundTo(), null));
        }
        List<Entry> shared = new ArrayList<>();
        for (RawShared r : sharedRaw) {
            ItemRef ref = refs.get(r.itemId());
            Map<String, Integer> derived = ref == null || ref.details() == null ? null
                    : EquipmentTotals.coreAttributes(r.slot(), ref.details().description());
            shared.add(new Entry(r.slot(), ref, "Equipped", List.of(), List.of(), null, derived,
                    r.binding(), r.boundTo(), null));
        }
        List<TabView> out = new ArrayList<>();
        for (RawTab t : rawTabs) {
            List<Entry> entries = withRuneCounts(byTab.getOrDefault(t.number(), List.of()));
            out.add(new TabView(t.number(), t.name(), t.active(), entries,
                    new Stats(EquipmentTotals.SCOPE, totals(profession, level, entries, shared))));
        }
        return new Snapshot(true, covered, out, parent, equipment, shared);
    }

    private static Map<String, EquipmentTotals.SetResult> totals(String profession, Integer level,
            List<Entry> entries, List<Entry> shared) {
        List<EquipmentTotals.Slot> tab = entries.stream().map(CharacterEquipmentTabsRepository::slot).toList();
        List<EquipmentTotals.Slot> sharedSlots = shared.stream().map(CharacterEquipmentTabsRepository::slot).toList();
        var result = EquipmentTotals.compute(profession, level, tab, sharedSlots);
        Map<String, EquipmentTotals.SetResult> sets = new LinkedHashMap<>();
        sets.put("A", result.a());
        sets.put("B", result.b());
        return sets;
    }

    private static EquipmentTotals.Slot slot(Entry e) {
        return new EquipmentTotals.Slot(e.slot(), gear(e.item(), e.statAttributes(), true),
                e.upgrades().stream().map(u -> gear(u, u.details() == null ? null : u.details().attributes(), false)).toList(),
                e.infusions().stream().map(u -> gear(u, u.details() == null ? null : u.details().attributes(), false)).toList());
    }

    private static EquipmentTotals.Gear gear(ItemRef ref, Map<String, Integer> attributes, boolean gearItem) {
        Details d = ref == null ? null : ref.details();
        if (d == null) return new EquipmentTotals.Gear(ref == null ? 0 : ref.itemId(), false, null, null, 0, null, null, null, false);
        boolean needsStats = gearItem && ref.type() != null
                && List.of("Armor", "Weapon", "Trinket", "Back").contains(ref.type());
        boolean resolved = !needsStats || (attributes != null && !attributes.isEmpty());
        return new EquipmentTotals.Gear(ref.itemId(), true, d.subtype(), attributes,
                d.defense() == null ? 0 : d.defense(), d.effects(), d.runeBonuses(), d.description(), resolved);
    }

    /**
     * Selected attributes win; else a stat id that differs from the item's fixed one (or an item without
     * fixed attributes) resolves through itemstats, and is unresolved (null) when those are missing; else
     * the fixed attributes. Never both.
     */
    private static Map<String, Integer> effective(ItemRef item, Map<String, Integer> selected, Integer statId,
            Map<Integer, Double> adjustments, Map<Integer, Integer> fixedStats, Map<Integer, List<StatAttr>> stats) {
        if (selected != null) return selected;
        Details d = item == null ? null : item.details();
        if (d == null) return null;
        boolean sameAsFixed = statId == null || (!d.attributes().isEmpty() && statId.equals(fixedStats.get(item.itemId())));
        if (sameAsFixed) return d.attributes().isEmpty() ? null : d.attributes();
        Double adjustment = adjustments.get(item.itemId());
        List<StatAttr> rows = stats.get(statId);
        if (adjustment == null || rows == null) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (StatAttr a : rows) out.put(a.attribute(), (int) Math.round(adjustment * a.multiplier() + a.value()));
        return out;
    }

    private record StatAttr(String attribute, double multiplier, int value) {}

    private static List<Entry> withRuneCounts(List<Entry> entries) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            Integer rune = rune(e);
            if (rune != null) counts.merge(rune, 1, Integer::sum);
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            Integer rune = rune(e);
            out.add(rune == null ? e : new Entry(e.slot(), e.item(), e.location(), e.upgrades(), e.infusions(),
                    e.statId(), e.statAttributes(), e.binding(), e.boundTo(), counts.get(rune)));
        }
        return out;
    }

    private static Integer rune(Entry e) {
        if (!EquipmentTotals.ARMOR_SLOTS.contains(e.slot())) return null;
        for (ItemRef u : e.upgrades()) {
            if (u != null && u.details() != null && !u.details().runeBonuses().isEmpty()) return u.itemId();
        }
        return null;
    }

    /** One batched read of the stored details of every referenced item; absent rows stay detail-less. */
    private static Map<Integer, ItemRef> withDetails(Connection con, Map<Integer, ItemRef> refs,
                                                     Map<Integer, Double> adjustments,
                                                     Map<Integer, Integer> fixedStats) throws SQLException {
        if (refs.isEmpty()) return refs;
        Map<Integer, ItemRef> out = new LinkedHashMap<>(refs);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT item_id, details::text FROM equipment_item_details WHERE item_id = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", refs.keySet().toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    ItemRef r = refs.get(id);
                    if (r == null) continue;
                    try {
                        var n = mapper.readTree(rs.getString(2));
                        if (!parser.EquipmentDetailsParser.complete(n)) continue; // retried by the next sync
                        Map<String, Integer> attrs = new LinkedHashMap<>();
                        n.path("attributes").fields().forEachRemaining(f -> attrs.put(f.getKey(), f.getValue().asInt()));
                        if (n.has("statId")) fixedStats.put(id, n.get("statId").asInt());
                        if (n.has("attributeAdjustment")) adjustments.put(id, n.get("attributeAdjustment").asDouble());
                        out.put(id, new ItemRef(r.itemId(), r.resolved(), r.name(), r.type(), r.rarity(), r.icon(),
                                new Details(text(n, "description"), integer(n, "level"), text(n, "subtype"),
                                        text(n, "weightClass"), integer(n, "defense"), integer(n, "minPower"),
                                        integer(n, "maxPower"), attrs, strings(n.path("effects")),
                                        strings(n.path("runeBonuses")), integer(n, "vendorValue"))));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
                        // An unreadable cache row is treated as missing; it is re-fetched, never invented.
                    }
                }
            }
        }
        return out;
    }

    private static Map<Integer, List<StatAttr>> itemStats(Connection con, Set<Integer> ids) throws SQLException {
        Map<Integer, List<StatAttr>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT stat_id, attributes::text FROM equipment_item_stats WHERE stat_id = ANY(?)")) {
            ps.setArray(1, con.createArrayOf("integer", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        List<StatAttr> list = new ArrayList<>();
                        for (var a : mapper.readTree(rs.getString(2)).path("attributes")) {
                            list.add(new StatAttr(a.path("attribute").asText(), a.path("multiplier").asDouble(),
                                    a.path("value").asInt()));
                        }
                        out.put(rs.getInt(1), list);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
                        // treated as missing
                    }
                }
            }
        }
        return out;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static Integer integer(com.fasterxml.jackson.databind.JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    private static List<String> strings(com.fasterxml.jackson.databind.JsonNode n) {
        List<String> out = new ArrayList<>();
        for (var x : n) out.add(x.asText());
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
