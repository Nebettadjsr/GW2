package sync;

import com.fasterxml.jackson.databind.JsonNode;
import model.AccountScope;
import parser.CharacterEquipmentTabsParser;
import parser.CharacterEquipmentTabsParser.Tab;
import repo.AccountSourcePublication;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;
import repo.Db;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independently refreshed saved-equipment-tab coverage over the already-published {@code CHARACTERS}
 * roster (STORY-CHAR-005, Option A). It never fetches, advances or completes {@code CHARACTERS}, never
 * touches {@code character_items}, and runs through the shared account refresh coordinator.
 *
 * <p>Order: capture the published roster and its generation, fetch and validate every character's tabs
 * and resolve canonical item metadata, all before any publication transaction; then publish tab rows,
 * the {@code CHARACTER_EQUIPMENT_TABS} marker and the covered parent generation in one transaction that
 * first re-verifies the parent generation is unchanged.
 */
public final class CharacterEquipmentTabsSync {

    /** Centrally named schema version the equipment-tabs request is pinned to. */
    public static final String SCHEMA_VERSION = "2025-08-29T01:00:00.000Z";

    /** No usable published CHARACTERS parent: nothing was fetched. */
    public static final class PrerequisiteUnavailableException extends IllegalStateException {
        public PrerequisiteUnavailableException() {
            super("Equipment tabs need a published character roster; none is available");
        }
    }

    /** The roster generation changed while tabs were being fetched: nothing was published. */
    public static final class ParentChangedException extends IllegalStateException {
        public ParentChangedException() {
            super("The character roster changed while equipment tabs were fetched; nothing was published");
        }
    }

    /** The persisted roster and generation the coverage describes. */
    record Capture(long parentGeneration, List<String> names) {}

    private CharacterEquipmentTabsSync() {}

    static String url(String characterName) {
        String enc = URLEncoder.encode(characterName, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://api.guildwars2.com/v2/characters/" + enc + "/equipmenttabs?tabs=all&v=" + SCHEMA_VERSION;
    }

    static void sync(AccountScope scope, AccountSync.AuthFetcher fetcher, IconSync.ItemMetadataRepair metadata)
            throws Exception {
        AccountSync.requireCredentialOwner(scope, fetcher);
        Capture capture;
        try (Connection con = Db.open()) {
            capture = capture(con, scope);
        }
        Map<String, List<Tab>> fetched = new LinkedHashMap<>();
        for (String name : capture.names()) {
            fetched.put(name, CharacterEquipmentTabsParser.parse(fetcher.get(url(name))));
        }
        metadata.repair(itemIds(fetched));
        try (Connection con = Db.open()) {
            publish(con, scope, capture, fetched, java.time.Clock.systemUTC());
        }
    }

    /** One distinct set of positive equipment, upgrade and infusion ids across the whole roster. */
    static Set<Integer> itemIds(Map<String, List<Tab>> fetched) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (List<Tab> tabs : fetched.values())
            for (Tab tab : tabs)
                for (var e : tab.equipment()) {
                    ids.add(e.itemId());
                    ids.addAll(e.upgrades());
                    ids.addAll(e.infusions());
                }
        return ids;
    }

    /** @throws PrerequisiteUnavailableException before any fetch, when CHARACTERS was never published */
    static Capture capture(Connection con, AccountScope scope) throws SQLException {
        con.setReadOnly(true);
        con.setAutoCommit(false);
        con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            var parent = new AccountSourceStateRepository().read(con, scope, AccountSyncStateRepository.CHARACTERS);
            if (parent.isEmpty()) throw new PrerequisiteUnavailableException();
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT name FROM characters WHERE account_id = ? ORDER BY name, character_id")) {
                ps.setObject(1, scope.accountId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) names.add(rs.getString(1));
                }
            }
            return new Capture(parent.get().generation(), names);
        } finally {
            con.rollback();
            con.setReadOnly(false);
        }
    }

    static AccountSourcePublication.Published publish(Connection con, AccountScope scope, Capture capture,
                                                      Map<String, List<Tab>> fetched, java.time.Clock clock)
            throws Exception {
        return new AccountSourcePublication(clock).publish(
                con, scope, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS,
                (tx, owner, runTs) -> {
                    var current = new AccountSourceStateRepository()
                            .read(tx, owner, AccountSyncStateRepository.CHARACTERS);
                    if (current.isEmpty() || current.get().generation() != capture.parentGeneration()) {
                        throw new ParentChangedException();
                    }
                    try (PreparedStatement ps = tx.prepareStatement(
                            "DELETE FROM character_equipment_tabs WHERE account_id = ?")) {
                        ps.setObject(1, owner.accountId());
                        ps.executeUpdate();
                    }
                    for (var character : fetched.entrySet()) {
                        long characterId = characterId(tx, owner, character.getKey());
                        int tabOrder = 0;
                        for (Tab tab : character.getValue()) insertTab(tx, owner, characterId, tab, tabOrder++, runTs);
                    }
                    try (PreparedStatement ps = tx.prepareStatement("""
                            INSERT INTO character_equipment_coverage (account_id, parent_generation) VALUES (?, ?)
                            ON CONFLICT (account_id) DO UPDATE SET parent_generation = EXCLUDED.parent_generation
                            """)) {
                        ps.setObject(1, owner.accountId());
                        ps.setLong(2, capture.parentGeneration());
                        ps.executeUpdate();
                    }
                });
    }

    private static long characterId(Connection tx, AccountScope owner, String name) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT character_id FROM characters WHERE account_id = ? AND name = ?")) {
            ps.setObject(1, owner.accountId());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ParentChangedException();
                return rs.getLong(1);
            }
        }
    }

    private static void insertTab(Connection tx, AccountScope owner, long characterId, Tab tab, int tabOrder,
                                  Timestamp runTs)
            throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("""
                INSERT INTO character_equipment_tabs
                  (account_id, character_id, tab_number, tab_order, name, is_active, fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, owner.accountId());
            ps.setLong(2, characterId);
            ps.setInt(3, tab.number());
            ps.setInt(4, tabOrder);
            ps.setString(5, tab.name());
            ps.setBoolean(6, tab.active());
            ps.setTimestamp(7, runTs);
            ps.executeUpdate();
        }
        int order = 0;
        try (PreparedStatement ps = tx.prepareStatement("""
                INSERT INTO character_equipment_tab_entries
                  (account_id, character_id, tab_number, entry_order, slot, item_id, location, upgrade_ids,
                   infusion_ids, stat_id, stat_attributes, binding, bound_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """)) {
            for (var e : tab.equipment()) {
                ps.setObject(1, owner.accountId());
                ps.setLong(2, characterId);
                ps.setInt(3, tab.number());
                ps.setInt(4, order++);
                ps.setString(5, e.slot());
                ps.setInt(6, e.itemId());
                ps.setString(7, e.location());
                ps.setArray(8, tx.createArrayOf("integer", e.upgrades().toArray()));
                ps.setArray(9, tx.createArrayOf("integer", e.infusions().toArray()));
                if (e.statId() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, e.statId());
                ps.setString(11, e.statAttributes() == null ? null : attributesJson(e.statAttributes()));
                ps.setString(12, e.binding());
                ps.setString(13, e.boundTo());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String attributesJson(Map<String, Integer> attributes) {
        var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        attributes.forEach(node::put);
        return node.toString();
    }
}
