package parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import craft.Ingredient;
import craft.MysticForgeCatalog;
import craft.Recipe;
import craft.RecipeSource;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MysticForgeCatalogParserTest {

    private static String slot(int idx, int qty, int id) {
        return "{\"Has ingredient index\":{\"item\":[" + idx + "]},\"Has ingredient quantity\":{\"item\":[" + qty
                + "]},\"Has ingredient id\":{\"item\":[" + id + "]}}";
    }

    private static String row(String page, String forge, String out, String qty, String ingredients) {
        return "\"" + page + "\":{\"printouts\":{\"Has mystic forge id\":" + forge + ",\"Has output game id\":" + out
                + ",\"Has output quantity\":" + qty + ",\"Has ingredient with id\":" + ingredients + "}}";
    }

    private static MysticForgeCatalogParser.Result parse(String rows, Set<Integer> fixed) throws Exception {
        return MysticForgeCatalogParser.parse(
                new ObjectMapper().readTree("{\"query\":{\"results\":{" + rows + "}}}"), fixed);
    }

    @Test
    void validFixedRowAggregatesRepeatedSlotsAndGetsNegativeIdentity() throws Exception {
        var r = parse(row("Accursed Chains#recipe1", "[3104601]", "[31046]", "[1]",
                "[" + slot(1, 1, 20852) + "," + slot(2, 30, 19976) + "," + slot(3, 100, 19688) + "," + slot(4, 150, 19688) + "]"),
                Set.of(3104601));
        assertEquals(1, r.recipes().size());
        Recipe rec = r.recipes().getFirst();
        assertEquals(-3104601, rec.recipeId);
        assertEquals(RecipeSource.MYSTIC_FORGE, rec.source);
        assertEquals(31046, rec.outputItemId);
        assertEquals(3, rec.ingredients.size());
        Ingredient merged = rec.ingredients.stream().filter(i -> i.itemId == 19688).findFirst().orElseThrow();
        assertEquals(250, merged.count);
        assertTrue(r.exclusions().isEmpty());
    }

    @Test
    void unverifiedOutcomeAndBrokenRowsAreExcludedNeverFree() throws Exception {
        String rows = String.join(",",
                row("Random#recipe1", "[100101]", "[1]", "[1]", "[" + slot(1, 1, 5) + "]"),
                row("NoIds#ct1", "[]", "[]", "[]", "[]"),
                row("ZeroCount#recipe1", "[200101]", "[2]", "[1]", "[" + slot(1, 0, 5) + "]"),
                row("NoIngredients#recipe1", "[300101]", "[3]", "[1]", "[]"),
                row("Dup#recipe2", "[400101]", "[4]", "[1]", "[" + slot(1, 1, 5) + "]"));
        var r = parse(rows, Set.of(200101, 300101, 400101));
        assertEquals(1, r.recipes().size(), "only the well-formed verified row survives");
        assertEquals(4, r.exclusions().size());
        assertTrue(r.exclusions().stream().anyMatch(e -> e.page().equals("Random#recipe1")
                && e.reason().contains("not verified fixed")));
    }

    @Test
    void missingResultsObjectIsReportedNotAssumedEmptyCoverage() throws Exception {
        var r = MysticForgeCatalogParser.parse(new ObjectMapper().readTree("{}"), Set.of());
        assertTrue(r.recipes().isEmpty());
        assertEquals(1, r.exclusions().size());
    }

    @Test
    void normalizedCatalogDropsInvalidRecipesAndFingerprintTracksContent() throws Exception {
        MysticForgeCatalog a = MysticForgeCatalogLoader.fromJson(new ObjectMapper().readTree(
                "{\"recipes\":[{\"forgeId\":3105801,\"outputItemId\":31058,\"outputCount\":1,\"ingredients\":[[20852,1],[19976,100]]},"
                        + "{\"forgeId\":0,\"outputItemId\":1,\"outputCount\":1,\"ingredients\":[[1,1]]},"
                        + "{\"forgeId\":5,\"outputItemId\":1,\"outputCount\":1,\"ingredients\":[]}]}"));
        assertEquals(1, a.recipes().size());
        assertEquals(-3105801, a.recipes().getFirst().recipeId);
        MysticForgeCatalog b = MysticForgeCatalogLoader.fromJson(new ObjectMapper().readTree(
                "{\"recipes\":[{\"forgeId\":3105801,\"outputItemId\":31058,\"outputCount\":1,\"ingredients\":[[20852,1],[19976,99]]}]}"));
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void bundledSnapshotLoadsWithoutInventingRecipes() {
        assertNotNull(MysticForgeCatalogLoader.bundled());
        MysticForgeCatalogLoader.bundled().recipes().forEach(r -> assertTrue(r.recipeId < 0 && r.isMysticForge()));
    }
}
