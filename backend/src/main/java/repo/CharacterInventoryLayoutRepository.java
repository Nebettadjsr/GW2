package repo;

import infra.icons.IconSourcePolicy;
import model.AccountScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Account-scoped, read-only bag layout of one selected character (STORY-CHAR-006). Reads the bag
 * manifest and the occupied {@code character_items} BAG rows inside one caller-supplied snapshot, then
 * derives empty cells from published capacity minus occupied coordinates. Constant query count: parent
 * marker, character, layout publication, manifest, occupied rows and one batched metadata lookup.
 * Equipment rows are never read, so nothing here can add to owned stock.
 */
public class CharacterInventoryLayoutRepository {

    /** Canonical shared item facts: name and icon only; {@code resolved} is false when unavailable. */
    public record ItemRef(int itemId, boolean resolved, String name, String icon) {}

    /** One capacity-defined cell; {@code item} is null exactly when empty. */
    public record Slot(int slot, ItemRef item, Integer quantity, String binding, String boundTo) {
        public boolean empty() { return item == null; }
    }

    /** {@code slots} is empty for an uninstalled position. */
    public record Bag(int bag, boolean installed, ItemRef container, Integer capacity, List<Slot> slots) {}

    public record Snapshot(boolean characterFound, boolean manifestPublished, List<Bag> bags,
                           Optional<AccountSourceStateRepository.SourceState> parent, boolean inconsistent) {}

    public Snapshot load(Connection con, AccountScope scope, String name) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "character inventory layout");
        var parent = new AccountSourceStateRepository().read(con, scope, AccountSyncStateRepository.CHARACTERS);
        if (parent.isEmpty()) return new Snapshot(false, false, List.of(), parent, false);
        Long characterId = null;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT character_id FROM characters WHERE account_id = ? AND name = ?")) {
            ps.setObject(1, scope.accountId());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) characterId = rs.getLong(1);
            }
        }
        if (characterId == null) return new Snapshot(false, false, List.of(), parent, false);
        boolean published;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM character_bag_layout_publication WHERE account_id = ?")) {
            ps.setObject(1, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                published = rs.next();
            }
        }
        if (!published) return new Snapshot(true, false, List.of(), parent, false);

        record RawBag(int bag, boolean installed, Integer container, Integer capacity) {}
        List<RawBag> rawBags = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT bag_index, installed, container_item_id, capacity FROM character_bags
                WHERE account_id = ? AND character_id = ? ORDER BY bag_index""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int c = rs.getInt(3);
                    Integer container = rs.wasNull() ? null : c;
                    int cap = rs.getInt(4);
                    rawBags.add(new RawBag(rs.getInt(1), rs.getBoolean(2), container, rs.wasNull() ? null : cap));
                }
            }
        }
        record RawItem(int bag, int slot, int itemId, int count, String binding, String boundTo) {}
        List<RawItem> rawItems = new ArrayList<>();
        Set<Integer> ids = new LinkedHashSet<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT bag_index, slot_index, item_id, count, binding, bound_to FROM character_items
                WHERE account_id = ? AND character_id = ? AND location = 'BAG'
                ORDER BY bag_index, slot_index""")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var it = new RawItem(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            rs.getString(5), rs.getString(6));
                    ids.add(it.itemId());
                    rawItems.add(it);
                }
            }
        }
        for (RawBag b : rawBags) if (b.container() != null) ids.add(b.container());
        Map<Integer, ItemRef> refs = metadata(con, ids);

        Map<Integer, Map<Integer, RawItem>> byBag = new HashMap<>();
        for (RawItem it : rawItems) byBag.computeIfAbsent(it.bag(), k -> new HashMap<>()).put(it.slot(), it);
        Set<Integer> manifestBags = new LinkedHashSet<>();
        List<Bag> bags = new ArrayList<>();
        boolean inconsistent = false;
        for (RawBag b : rawBags) {
            manifestBags.add(b.bag());
            Map<Integer, RawItem> occupied = byBag.getOrDefault(b.bag(), Map.of());
            if (!b.installed()) {
                if (!occupied.isEmpty()) inconsistent = true;
                bags.add(new Bag(b.bag(), false, null, null, List.of()));
                continue;
            }
            List<Slot> slots = new ArrayList<>(b.capacity());
            for (int s = 0; s < b.capacity(); s++) {
                RawItem it = occupied.get(s);
                slots.add(it == null ? new Slot(s, null, null, null, null)
                        : new Slot(s, refs.get(it.itemId()), it.count(), it.binding(), it.boundTo()));
            }
            for (int s : occupied.keySet()) if (s < 0 || s >= b.capacity()) inconsistent = true;
            bags.add(new Bag(b.bag(), true, refs.get(b.container()), b.capacity(), slots));
        }
        for (int bagIndex : byBag.keySet()) if (!manifestBags.contains(bagIndex)) inconsistent = true;
        return new Snapshot(true, true, bags, parent, inconsistent);
    }

    private static Map<Integer, ItemRef> metadata(Connection con, Set<Integer> ids) throws SQLException {
        Map<Integer, ItemRef> refs = new HashMap<>();
        if (!ids.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT item_id, name, icon_url FROM items WHERE item_id = ANY(?)")) {
                ps.setArray(1, con.createArrayOf("integer", ids.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        boolean ok = name != null && !name.isBlank();
                        String icon = IconSourcePolicy.accept(rs.getString(3))
                                .map(infra.icons.IconSource::canonicalUrl).orElse(null);
                        refs.put(rs.getInt(1), new ItemRef(rs.getInt(1), ok, ok ? name : null, icon));
                    }
                }
            }
        }
        for (int id : ids) refs.putIfAbsent(id, new ItemRef(id, false, null, null));
        return refs;
    }
}
