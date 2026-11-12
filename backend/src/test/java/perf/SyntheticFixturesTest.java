package perf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticFixturesTest {

    @Test
    void rebuildingYieldsTheSameVersionedIdentityAndExpectedResults() {
        FixtureSnapshot a = SyntheticFixtures.build();
        FixtureSnapshot b = SyntheticFixtures.build();
        assertEquals(a.identity(), b.identity());
        assertEquals(a.expectedIdentity(), b.expectedIdentity());
        assertEquals(SyntheticFixtures.VERSION, a.version());
        assertEquals(64, a.identity().length());
        assertNotEquals("unavailable", a.expectedIdentity());
    }

    @Test
    void expectedResultsAreIndependentlySpecifiedAndAgreeWithTheRows() {
        FixtureSnapshot s = SyntheticFixtures.build();
        for (String account : SyntheticFixtures.ACCOUNTS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exp = (Map<String, Object>) SyntheticFixtures.expected().get(account);
            assertEquals(exp.get("bankSlots"), (int) s.rows("account_bank").stream().filter(r -> account.equals(r.get("account_id").asText())).count(), account);
            assertEquals(exp.get("bankItemCountSum"), (int) s.rows("account_bank").stream().filter(r -> account.equals(r.get("account_id").asText())).mapToLong(r -> r.get("count").asLong()).sum());
            assertEquals(exp.get("materialRows"), (int) s.rows("account_materials").stream().filter(r -> account.equals(r.get("account_id").asText())).count());
            assertEquals(exp.get("materialTotal"), (int) s.rows("account_materials").stream().filter(r -> account.equals(r.get("account_id").asText())).mapToLong(r -> r.get("count").asLong()).sum());
            assertEquals(exp.get("characters"), (int) s.rows("characters").stream().filter(r -> account.equals(r.get("account_id").asText())).count());
            assertEquals(exp.get("accountRecipes"), (int) s.rows("account_recipes").stream().filter(r -> account.equals(r.get("account_id").asText())).count());
        }
    }

    @Test
    void accountsShareIdsButDifferInStocksUnlocksAndCharacters() {
        FixtureSnapshot s = SyntheticFixtures.build();
        assertTrue(s.rows("account_registry").size() >= 5);
        Set<String> aChars = names(s, SyntheticFixtures.A);
        Set<String> bChars = names(s, SyntheticFixtures.B);
        assertTrue(Collections_disjoint(aChars, bChars));
        Set<Long> aIds = ids(s, SyntheticFixtures.A), bIds = ids(s, SyntheticFixtures.B);
        assertFalse(java.util.Collections.disjoint(aIds, bIds), "character IDs overlap across accounts");
        long aStock = s.rows("account_materials").stream().filter(r -> r.get("account_id").asText().equals(SyntheticFixtures.A) && r.get("item_id").asInt() == 1).findFirst().orElseThrow().get("count").asLong();
        long bStock = s.rows("account_materials").stream().filter(r -> r.get("account_id").asText().equals(SyntheticFixtures.B) && r.get("item_id").asInt() == 1).findFirst().orElseThrow().get("count").asLong();
        assertNotEquals(aStock, bStock, "same item, different stock");
    }

    @Test
    void coversMaterialsCharactersBagsEquipmentBuildsRecipesAndStatusSources() {
        FixtureSnapshot s = SyntheticFixtures.build();
        for (String t : List.of("account_bank", "account_materials", "characters", "character_crafting", "character_items",
                "character_bags", "character_equipment_tab_entries", "character_build_tab_specializations",
                "account_recipes", "recipes", "recipe_ingredients", "tp_prices", "account_source_state",
                "build_specializations", "equipment_item_details")) {
            assertFalse(s.rows(t).isEmpty(), t + " must be populated");
        }
        Set<String> variants = new HashSet<>();
        s.rows("account_source_state").forEach(r -> variants.add(r.get("account_id").asText() + r.get("completed_at").asText().substring(0, 4)));
        assertTrue(variants.stream().anyMatch(v -> v.endsWith("2020")), "stale variant");
    }

    private static boolean Collections_disjoint(Set<String> a, Set<String> b) { return java.util.Collections.disjoint(a, b); }

    private static Set<String> names(FixtureSnapshot s, String account) {
        Set<String> out = new HashSet<>();
        for (ObjectNode r : s.rows("characters")) if (r.get("account_id").asText().equals(account)) out.add(r.get("name").asText());
        return out;
    }

    private static Set<Long> ids(FixtureSnapshot s, String account) {
        Set<Long> out = new HashSet<>();
        for (ObjectNode r : s.rows("characters")) if (r.get("account_id").asText().equals(account)) out.add(r.get("character_id").asLong());
        return out;
    }
}
