package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table rows in the {@code local-postgres-jsonl-1} shape (one {@code to_jsonb(row)} object per row), for
 * both the synthetic default fixtures and the optional local snapshot import. One shape means one loader,
 * so neither source can bypass the production Flyway schema.
 */
public record FixtureSnapshot(Kind kind, String version, Map<String, List<ObjectNode>> tables,
                              Map<String, Object> expected, String sourceRevision, String clockTransformation) {

    public enum Kind { SYNTHETIC, LOCAL_SNAPSHOT }

    /** Parent-first load order; the first group is shared cache data, the rest is account scoped. */
    public static final List<String> SHARED_TABLES = List.of("items", "recipes", "recipe_ingredients",
            "recipe_catalog_publication", "tp_tradeable_items", "tp_prices", "material_categories",
            "material_category_items", "equipment_item_details", "equipment_item_stats", "build_specializations",
            "build_traits", "build_skills", "build_pets", "build_legends");
    public static final List<String> SCOPED_TABLES = List.of("account_registry", "account_source_state",
            "account_bank", "account_materials", "account_recipes", "account_luck", "characters",
            "character_crafting", "character_recipes", "character_items", "character_bags",
            "character_bag_layout_publication", "character_equipment_coverage", "character_equipment_tabs",
            "character_equipment_tab_entries", "character_build_coverage", "character_build_tabs",
            "character_build_tab_specializations");
    public static final Set<String> ALL_TABLES;
    static {
        var all = new java.util.LinkedHashSet<>(SHARED_TABLES);
        all.addAll(SCOPED_TABLES);
        ALL_TABLES = Set.copyOf(all);
    }

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public FixtureSnapshot {
        Map<String, List<ObjectNode>> copy = new LinkedHashMap<>();
        for (String t : SHARED_TABLES) copy.put(t, List.copyOf(tables.getOrDefault(t, List.of())));
        for (String t : SCOPED_TABLES) copy.put(t, List.copyOf(tables.getOrDefault(t, List.of())));
        tables = java.util.Collections.unmodifiableMap(copy);
        expected = expected == null ? Map.of() : Map.copyOf(expected);
    }

    public List<ObjectNode> rows(String table) { return tables.get(table); }

    public Map<String, Integer> tableCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        tables.forEach((t, rows) -> out.put(t, rows.size()));
        return out;
    }

    /** SHA-256 of the canonical (key-sorted) rows in load order: stable for equal content. */
    public String identity() {
        return sha256(canonical(tables));
    }

    /** SHA-256 of the independently specified expected results; "unavailable" when there are none. */
    public String expectedIdentity() {
        return expected.isEmpty() ? "unavailable" : sha256(canonical(expected));
    }

    private static String canonical(Object o) {
        try {
            return CANONICAL.writeValueAsString(CANONICAL.convertValue(o, Object.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) { return sha256(s.getBytes(StandardCharsets.UTF_8)); }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
