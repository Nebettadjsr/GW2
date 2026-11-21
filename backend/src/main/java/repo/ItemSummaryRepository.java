package repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import parser.EquipmentDetailsParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Public item facts from the same slim cache used by character equipment; never stores image bytes. */
public final class ItemSummaryRepository {
    private static final Set<String> EQUIPMENT = Set.of("Weapon", "Armor", "Trinket", "Back");
    public record Summary(String type, String subtype, String rarity, Integer level, Integer minPower,
                          Integer maxPower, String damageType, Integer defense, String weightClass,
                          String binding, String prefix, boolean complete) { }

    public Summary find(int itemId) throws SQLException {
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement("""
                SELECT i.type, i.subtype, i.rarity, d.details::text
                FROM items i LEFT JOIN equipment_item_details d ON d.item_id = i.item_id
                WHERE i.item_id = ?
                """)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String type = rs.getString(1), subtype = rs.getString(2), rarity = rs.getString(3);
                JsonNode details = null;
                try {
                    if (rs.getString(4) != null) details = new ObjectMapper().readTree(rs.getString(4));
                } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                    // The background repair replaces unreadable or incomplete cached projections.
                }
                if (type == null || !EQUIPMENT.contains(type)) {
                    String binding = details != null && details.path("flags").isArray() ? binding(details) : null;
                    return new Summary(type, subtype, rarity, null, null, null, null, null, null,
                            binding, null, type != null && rarity != null);
                }
                if (!EquipmentDetailsParser.completeSummary(details)) {
                    return new Summary(type, subtype, rarity, null, null, null, null, null, null, null, null, false);
                }
                boolean selectable = !details.path("statChoices").isEmpty();
                String prefix = selectable ? "Selectable" : null;
                boolean complete = true;
                if (!selectable && details.path("statId").isIntegralNumber() && details.get("statId").asInt() > 0) {
                    prefix = statName(con, details.get("statId").asInt());
                    complete = prefix != null;
                }
                return new Summary(type, text(details, "subtype"), rarity, integer(details, "level"),
                        integer(details, "minPower"), integer(details, "maxPower"), text(details, "damageType"),
                        integer(details, "defense"), text(details, "weightClass"), binding(details), prefix, complete);
            }
        }
    }

    private static String statName(Connection con, int statId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT attributes->>'name' FROM equipment_item_stats WHERE stat_id = ?")) {
            ps.setInt(1, statId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static String binding(JsonNode details) {
        Set<String> flags = new HashSet<>();
        details.path("flags").forEach(flag -> flags.add(flag.asText()));
        if (flags.contains("AccountBound")) return "Account bound";
        if (flags.contains("AccountBindOnUse")) return "Account bound on use";
        if (flags.contains("SoulbindOnAcquire")) return "Soulbound on acquire";
        if (flags.contains("SoulBindOnUse") || flags.contains("SoulbindOnUse")) return "Soulbound on use";
        return "Unbound";
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.get(field).asText() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.get(field).asInt() : null;
    }
}
