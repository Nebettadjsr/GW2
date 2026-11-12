package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, deterministic, populated synthetic fixtures (STORY-KPI-007). Everything is a literal: no
 * clock, no randomness, no network. Five independent principals share the same item/recipe/character
 * IDs but differ in stocks, unlocks, characters and expected results:
 * A and B are populated, C is an empty granted account, D has stale markers, E is a failed/partial sync.
 * The {@link #expected()} values are written by hand, not derived from the rows; a unit test derives the
 * same numbers from the rows to prove the two specifications agree.
 */
public final class SyntheticFixtures {

    public static final String VERSION = "kpi-fixtures-1";
    public static final String FRESH = "2026-01-01T00:00:00+00:00";
    public static final String STALE = "2020-01-01T00:00:00+00:00";

    public static final String A = "00000000-0000-4000-8000-00000000a001";
    public static final String B = "00000000-0000-4000-8000-00000000b002";
    public static final String C = "00000000-0000-4000-8000-00000000c003";
    public static final String D = "00000000-0000-4000-8000-00000000d004";
    public static final String E = "00000000-0000-4000-8000-00000000e005";
    public static final List<String> ACCOUNTS = List.of(A, B, C, D, E);

    private static final ObjectMapper M = new ObjectMapper();

    private SyntheticFixtures() {}

    static ObjectNode row(Object... kv) {
        ObjectNode n = M.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.set((String) kv[i], M.valueToTree(kv[i + 1]));
        return n;
    }

    /** Synthetic API key for a principal: never a real proof. */
    public static String keyOf(String account) { return "synthetic-key-" + account.substring(account.length() - 4); }

    public static FixtureSnapshot build() {
        Map<String, List<ObjectNode>> t = new LinkedHashMap<>();
        for (String name : FixtureSnapshot.ALL_TABLES) t.put(name, new ArrayList<>());
        shared(t);
        accountA(t);
        accountB(t);
        accountC(t);
        accountD(t);
        accountE(t);
        return new FixtureSnapshot(FixtureSnapshot.Kind.SYNTHETIC, VERSION, t, expected(), null, null);
    }

    private static void shared(Map<String, List<ObjectNode>> t) {
        for (int id : new int[] {1, 2, 3, 4, 5, 6}) {
            t.get("items").add(row("item_id", id, "name", "Fixture Material " + id, "type", "CraftingMaterial",
                    "rarity", "Basic", "level", 0, "vendor_value", 1, "fetched_at", FRESH));
        }
        t.get("items").add(row("item_id", 100, "name", "Fixture Dish", "type", "Consumable", "rarity", "Fine", "level", 80, "vendor_value", 5, "fetched_at", FRESH));
        t.get("items").add(row("item_id", 101, "name", "Fixture Feast", "type", "Consumable", "rarity", "Fine", "level", 80, "vendor_value", 5, "fetched_at", FRESH));
        t.get("items").add(row("item_id", 200, "name", "Fixture Bag", "type", "Bag", "rarity", "Basic", "level", 0, "vendor_value", 1, "fetched_at", FRESH));
        t.get("items").add(row("item_id", 300, "name", "Fixture Coat", "type", "Armor", "rarity", "Masterwork", "level", 80, "vendor_value", 10, "fetched_at", FRESH));
        t.get("recipes").add(row("recipe_id", 20001, "type", "Food", "output_item_id", 100, "output_item_count", 1,
                "time_to_craft_ms", 1000, "min_rating", 0, "disciplines", List.of("Chef"), "flags", List.of(), "fetched_at", FRESH));
        t.get("recipes").add(row("recipe_id", 20002, "type", "Feast", "output_item_id", 101, "output_item_count", 1,
                "time_to_craft_ms", 1000, "min_rating", 100, "disciplines", List.of("Chef"), "flags", List.of(), "fetched_at", FRESH));
        t.get("recipe_ingredients").add(row("recipe_id", 20001, "item_id", 1, "count", 2));
        t.get("recipe_ingredients").add(row("recipe_id", 20001, "item_id", 2, "count", 3));
        t.get("recipe_ingredients").add(row("recipe_id", 20002, "item_id", 100, "count", 1));
        t.get("recipe_ingredients").add(row("recipe_id", 20002, "item_id", 3, "count", 2));
        t.get("recipe_catalog_publication").add(row("singleton", true, "complete", true, "updated_at", FRESH));
        for (int id : new int[] {1, 2, 3, 4, 5, 6, 100, 101}) {
            t.get("tp_tradeable_items").add(row("item_id", id, "fetched_at", FRESH));
            int base = id < 100 ? 40 + id : 3000 + id;
            t.get("tp_prices").add(row("item_id", id, "buy_quantity", 500, "buy_unit_price", base,
                    "sell_quantity", 500, "sell_unit_price", base + base / 10, "fetched_at", FRESH));
        }
        t.get("material_categories").add(row("category_id", 5, "name", "Fixture", "display_order", 1));
        for (int p = 1; p <= 6; p++) t.get("material_category_items").add(row("category_id", 5, "position", p, "item_id", p));
        t.get("equipment_item_details").add(row("item_id", 300, "details", Map.of("type", "Coat", "weight_class", "Light"), "fetched_at", FRESH));
        t.get("equipment_item_stats").add(row("stat_id", 1, "attributes", Map.of("Power", 10), "fetched_at", FRESH));
        t.get("build_specializations").add(row("specialization_id", 1, "name", "Fixture Spec", "profession", "Guardian", "elite", false,
                "minor_traits", List.of(10, 11, 12), "major_traits", List.of(13, 14, 15, 16, 17, 18, 19, 20, 21), "fetched_at", FRESH));
        for (int id = 10; id <= 21; id++) t.get("build_traits").add(row("trait_id", id, "name", "Fixture Trait " + id, "specialization_id", 1, "tier", 1 + (id % 3), "trait_order", id % 3, "fetched_at", FRESH));
        for (int id : new int[] {400, 401, 402, 403}) t.get("build_skills").add(row("skill_id", id, "name", "Fixture Skill " + id, "fetched_at", FRESH));
        for (int id : new int[] {1, 2}) t.get("build_pets").add(row("pet_id", id, "name", "Fixture Pet " + id, "fetched_at", FRESH));
        t.get("build_legends").add(row("legend_id", "Legend1", "swap_skill_id", 500, "fetched_at", FRESH));
    }

    private static void register(Map<String, List<ObjectNode>> t, String acct, String display, String at) {
        t.get("account_registry").add(row("account_id", acct, "display_name", display, "created_at", FRESH, "last_seen_at", at, "last_successful_sync_at", at));
    }

    private static void marker(Map<String, List<ObjectNode>> t, String acct, String source, long gen, String at) {
        t.get("account_source_state").add(row("account_id", acct, "source", source, "generation", gen, "completed_at", at));
    }

    private static void bank(Map<String, List<ObjectNode>> t, String acct, int slot, int item, int count) {
        t.get("account_bank").add(row("account_id", acct, "slot", slot, "item_id", item, "count", count, "fetched_at", FRESH));
    }

    private static void material(Map<String, List<ObjectNode>> t, String acct, int item, int count) {
        t.get("account_materials").add(row("account_id", acct, "item_id", item, "category", 5, "count", count, "fetched_at", FRESH));
    }

    private static void character(Map<String, List<ObjectNode>> t, String acct, int id, String name, String profession) {
        t.get("characters").add(row("account_id", acct, "character_id", id, "name", name, "profession", profession,
                "race", "Human", "gender", "Female", "level", 80, "created_at_gw", FRESH, "fetched_at", FRESH, "age_seconds", 3600 * id, "deaths", id));
    }

    private static void allSources(Map<String, List<ObjectNode>> t, String acct, String at) {
        long g = 1;
        for (String s : List.of("BANK", "MATERIALS", "ACCOUNT_RECIPES", "LUCK", "CHARACTERS", "CHARACTER_EQUIPMENT_TABS", "CHARACTER_BUILD_TABS")) {
            marker(t, acct, s, g, at);
        }
    }

    /** Bag, saved equipment and a saved build for one character, so Inventory/Equipment/Builds have rows. */
    private static void characterDetail(Map<String, List<ObjectNode>> t, String acct, int cid, boolean installedBag) {
        t.get("character_bags").add(installedBag
                ? row("account_id", acct, "character_id", cid, "bag_index", 0, "installed", true, "container_item_id", 200, "capacity", 20, "fetched_at", FRESH)
                : row("account_id", acct, "character_id", cid, "bag_index", 0, "installed", false, "container_item_id", null, "capacity", null, "fetched_at", FRESH));
        t.get("character_equipment_tabs").add(row("account_id", acct, "character_id", cid, "tab_number", 1, "name", "Tab 1", "is_active", true, "fetched_at", FRESH));
        t.get("character_equipment_tab_entries").add(row("account_id", acct, "character_id", cid, "tab_number", 1, "entry_order", 0,
                "slot", "Coat", "item_id", 300, "location", "Equipped", "upgrade_ids", List.of(), "infusion_ids", List.of(), "stat_id", 1, "stat_attributes", Map.of("Power", 10)));
        t.get("character_build_tabs").add(row("account_id", acct, "character_id", cid, "tab_number", 1, "name", "Build 1", "profession", "Guardian", "is_active", true,
                "land_heal", 400, "land_elite", 401, "land_utilities", List.of(402, 403, 400), "aquatic_heal", 400, "aquatic_elite", 401,
                "aquatic_utilities", List.of(402, 403, 400), "fetched_at", FRESH));
        for (int pos = 1; pos <= 3; pos++) {
            t.get("character_build_tab_specializations").add(row("account_id", acct, "character_id", cid, "tab_number", 1, "position", pos,
                    "specialization_id", 1, "traits", List.of(13, 14, 15)));
        }
    }

    private static void coverage(Map<String, List<ObjectNode>> t, String acct) {
        t.get("character_bag_layout_publication").add(row("account_id", acct, "fetched_at", FRESH));
        t.get("character_equipment_coverage").add(row("account_id", acct, "parent_generation", 1));
        t.get("character_build_coverage").add(row("account_id", acct, "parent_generation", 1));
    }

    private static void accountA(Map<String, List<ObjectNode>> t) {
        register(t, A, "Synthetic A", FRESH);
        allSources(t, A, FRESH);
        bank(t, A, 0, 1, 50); bank(t, A, 1, 2, 30); bank(t, A, 2, 100, 5);
        material(t, A, 1, 200); material(t, A, 2, 150); material(t, A, 3, 40); material(t, A, 4, 10);
        t.get("account_recipes").add(row("account_id", A, "recipe_id", 20001, "fetched_at", FRESH));
        t.get("account_recipes").add(row("account_id", A, "recipe_id", 20002, "fetched_at", FRESH));
        t.get("account_luck").add(row("account_id", A, "consumed_luck", 1000, "fetched_at", FRESH));
        character(t, A, 1, "Aria", "Guardian");
        character(t, A, 2, "Bran", "Ranger");
        t.get("character_crafting").add(row("account_id", A, "character_id", 1, "discipline", "Chef", "rating", 400, "is_active", true, "fetched_at", FRESH));
        t.get("character_recipes").add(row("account_id", A, "character_id", 1, "recipe_id", 20001, "fetched_at", FRESH));
        t.get("character_items").add(row("account_id", A, "character_id", 1, "location", "Inventory", "bag_index", 0, "slot_index", 0,
                "equipment_slot", null, "item_id", 3, "count", 7, "upgrade_ids", List.of(), "fetched_at", FRESH));
        characterDetail(t, A, 1, true);
        characterDetail(t, A, 2, false);
        coverage(t, A);
    }

    private static void accountB(Map<String, List<ObjectNode>> t) {
        register(t, B, "Synthetic B", FRESH);
        allSources(t, B, FRESH);
        bank(t, B, 0, 1, 7); bank(t, B, 1, 3, 900);
        material(t, B, 1, 5); material(t, B, 2, 9); material(t, B, 5, 77);
        t.get("account_recipes").add(row("account_id", B, "recipe_id", 20001, "fetched_at", FRESH));
        t.get("account_luck").add(row("account_id", B, "consumed_luck", 0, "fetched_at", FRESH));
        // Character IDs 1 and 2 deliberately overlap account A's; names and professions differ.
        character(t, B, 1, "Cato", "Engineer");
        character(t, B, 2, "Dara", "Thief");
        character(t, B, 3, "Edda", "Mesmer");
        t.get("character_crafting").add(row("account_id", B, "character_id", 1, "discipline", "Chef", "rating", 200, "is_active", true, "fetched_at", FRESH));
        t.get("character_items").add(row("account_id", B, "character_id", 1, "location", "Inventory", "bag_index", 0, "slot_index", 0,
                "equipment_slot", null, "item_id", 3, "count", 2, "upgrade_ids", List.of(), "fetched_at", FRESH));
        characterDetail(t, B, 1, true);
        coverage(t, B);
    }

    /** Granted but never synchronized: registry only, no markers, no rows. */
    private static void accountC(Map<String, List<ObjectNode>> t) {
        t.get("account_registry").add(row("account_id", C, "display_name", "Synthetic C", "created_at", FRESH, "last_seen_at", null, "last_successful_sync_at", null));
    }

    /** Stale: data and markers exist but are years old. */
    private static void accountD(Map<String, List<ObjectNode>> t) {
        register(t, D, "Synthetic D", STALE);
        marker(t, D, "BANK", 1, STALE);
        marker(t, D, "MATERIALS", 1, STALE);
        bank(t, D, 0, 1, 11);
        material(t, D, 1, 13);
    }

    /** Failure variant: a materials publication succeeded; a bank attempt failed and left rows without a marker. */
    private static void accountE(Map<String, List<ObjectNode>> t) {
        register(t, E, "Synthetic E", FRESH);
        marker(t, E, "MATERIALS", 1, FRESH);
        material(t, E, 2, 21);
        bank(t, E, 0, 1, 99);
    }

    /** Hand-specified expected results per principal; never computed from the rows above. */
    public static Map<String, Object> expected() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put(A, Map.of("bankSlots", 3, "bankItemCountSum", 85, "materialRows", 4, "materialTotal", 400,
                "characters", 2, "accountRecipes", 2, "overviewSources", List.of("CHARACTERS", "MATERIALS", "ACCOUNT_RECIPES")));
        e.put(B, Map.of("bankSlots", 2, "bankItemCountSum", 907, "materialRows", 3, "materialTotal", 91,
                "characters", 3, "accountRecipes", 1, "overviewSources", List.of("CHARACTERS", "MATERIALS", "ACCOUNT_RECIPES")));
        e.put(C, Map.of("bankSlots", 0, "bankItemCountSum", 0, "materialRows", 0, "materialTotal", 0,
                "characters", 0, "accountRecipes", 0, "overviewSources", List.of()));
        e.put(D, Map.of("bankSlots", 1, "bankItemCountSum", 11, "materialRows", 1, "materialTotal", 13,
                "characters", 0, "accountRecipes", 0, "overviewSources", List.of("MATERIALS"), "markersCompletedAt", STALE));
        e.put(E, Map.of("bankSlots", 1, "bankItemCountSum", 99, "materialRows", 1, "materialTotal", 21,
                "characters", 0, "accountRecipes", 0, "overviewSources", List.of("MATERIALS"), "bankMarker", "missing"));
        return e;
    }
}
