package application;

import craft.CraftResult;
import craft.CraftingSettings;
import craft.MaterialPurchaseCost;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.ItemRepository;
import repo.RecipeRepository;
import util.CoinUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014's required real-data equivalence check for the changed input capture: on the
 * developer's real PostgreSQL database, a calculation that reads every input through one captured
 * {@link ProfitInputSession} snapshot must produce exactly the same economic and domain result as
 * the previous capture, where each repository opened its own connection.
 *
 * <p>Both paths still exist in the code — a detached session is the previous one, which
 * {@code CraftingProfitServiceSessionTest} asserts is unchanged — so the reference and the
 * comparison run against the <em>same</em> database snapshot moments apart instead of across two
 * builds. Neither run writes, neither admits a refresh (the coordination layer is not involved
 * here), and no synchronization happens in between, so a difference means the capture change moved
 * a number rather than that the data moved.
 *
 * <p>The captured reference is written outside version control, to the JVM's temporary directory,
 * and the test prints its path. Nothing about the account's contents is asserted, printed inline or
 * committed, and no credential is read or reported by this test.
 *
 * <p>Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix
 * (docs/TEST_STRATEGY.md §34); CI cannot run it, because it needs real synchronized data. Run
 * explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitInputCaptureEquivalenceIT -DfailIfNoSpecifiedTests=false}
 */
class CraftingProfitInputCaptureEquivalenceIT {

    private static final int COMPARED_DETAIL_ROWS = 5;

    @Test
    void theCapturedSessionCalculationMatchesThePreviousPerRepositoryReadForTheDefaultAllScope()
            throws Exception {
        assertEquivalent("default All",
                DiscChoice.all(),
                new CraftingSettings(true, false, CoinUtils.parseToCopper("1g"), false, false, false));
    }

    @Test
    void theCapturedSessionCalculationMatchesThePreviousPerRepositoryReadWithBuyingEnabled()
            throws Exception {
        assertEquivalent("All, buying enabled at 10g, listing prices",
                DiscChoice.all(),
                new CraftingSettings(true, true, CoinUtils.parseToCopper("10g"), true, true, true));
    }

    private void assertEquivalent(String label, DiscChoice choice, CraftingSettings settings)
            throws Exception {

        CraftingGraphCache graphCache = new CraftingGraphCache(new RecipeRepository(), new ItemRepository());

        // Reference: the capture this story changed, as it read before the change.
        CraftingProfitService.ProfitData reference =
                new CraftingProfitService(graphCache).reload(choice, settings);
        CraftingResolutionDetail[] referenceDetails = details(
                new CraftingProfitService(graphCache), reference, choice, settings);

        // Comparison: every input read through one captured snapshot session.
        CraftingProfitService.ProfitData captured;
        CraftingResolutionDetail[] capturedDetails;
        try (ProfitInputSession session = ProfitInputSession.open()) {
            captured = new CraftingProfitService(graphCache, session).reload(choice, settings);
            capturedDetails = details(
                    new CraftingProfitService(graphCache, session), reference, choice, settings);
        }

        Path recorded = record(label, reference, referenceDetails);
        System.out.println("[" + label + "] reference recorded outside version control at " + recorded);

        assertTrue(reference.visibleRecipes().size() > 0,
                label + ": the reference calculation produced no rows, so it proves nothing - "
                        + "this check needs real synchronized data");
        assertEquals(ids(reference.visibleRecipes()), ids(captured.visibleRecipes()),
                label + ": the visible recipes and their order must be identical");
        assertEquals(reference.items().size(), captured.items().size(),
                label + ": the captured item metadata set must be identical in size");
        assertEquals(reference.tp().size(), captured.tp().size(),
                label + ": the captured quote set must be identical in size");

        for (Recipe recipe : reference.visibleRecipes()) {
            CraftResult expected = reference.resultsByRecipeId().get(recipe.recipeId);
            CraftResult actual = captured.resultsByRecipeId().get(recipe.recipeId);
            assertRowEquivalent(label + " recipe " + recipe.recipeId, expected, actual);
        }

        for (int i = 0; i < referenceDetails.length; i++) {
            assertDetailEquivalent(label + " detail " + i, referenceDetails[i], capturedDetails[i]);
        }
    }

    private static void assertRowEquivalent(String what, CraftResult expected, CraftResult actual) {
        if (expected == null) {
            assertEquals(null, actual, what + ": a recipe without a result must stay without one");
            return;
        }
        assertTrue(actual != null, what + ": the captured calculation produced no result");
        assertEquals(expected.outputItemId, actual.outputItemId, what + " outputItemId");
        assertEquals(expected.discipline, actual.discipline, what + " discipline");
        assertEquals(expected.craftableCount, actual.craftableCount, what + " craftableCount");
        assertEquals(expected.missingToBuy, actual.missingToBuy, what + " missingToBuy");
        assertEquals(expected.missingToBuyOne, actual.missingToBuyOne, what + " missingToBuyOne");
        assertEquals(expected.buyCostCopper, actual.buyCostCopper, what + " buyCostCopper");
        assertEquals(expected.matsSellValueCopper, actual.matsSellValueCopper, what + " matsSellValueCopper");
        assertEquals(expected.totalMatsSellValueCopper, actual.totalMatsSellValueCopper,
                what + " totalMatsSellValueCopper");
        assertEquals(expected.revenueCopper, actual.revenueCopper, what + " revenueCopper");
        assertEquals(expected.profitCopper, actual.profitCopper, what + " profitCopper");
        assertEquals(expected.totalProfitCopper, actual.totalProfitCopper, what + " totalProfitCopper");
        assertEquals(expected.totalSellValueCopper, actual.totalSellValueCopper, what + " totalSellValueCopper");
        assertEquals(expected.blockedReason, actual.blockedReason, what + " blockedReason");
        assertEquals(costs(expected.materialPurchaseCosts), costs(actual.materialPurchaseCosts),
                what + " materialPurchaseCosts");
        assertEquals(costs(expected.materialPurchaseCostsOne), costs(actual.materialPurchaseCostsOne),
                what + " materialPurchaseCostsOne");
    }

    private static void assertDetailEquivalent(String what,
                                               CraftingResolutionDetail expected,
                                               CraftingResolutionDetail actual) {
        assertEquals(expected.recipeId(), actual.recipeId(), what + " recipeId");
        assertEquals(expected.status(), actual.status(), what + " status");
        assertRowEquivalent(what + " row", expected.row(), actual.row());
        assertEquals(describe(expected), describe(actual), what + " resolution tree");
    }

    /** The whole explanation as text, so a changed quantity, cost or state anywhere fails here. */
    private static String describe(CraftingResolutionDetail detail) {
        if (detail.explanation() == null || detail.explanation().root() == null) return "<no explanation>";
        StringBuilder out = new StringBuilder();
        describe(detail.explanation().root(), 0, out);
        return out.toString();
    }

    private static void describe(craft.CraftTraceNode node, int depth, StringBuilder out) {
        out.append("  ".repeat(depth))
                .append(node.itemId()).append(" req=").append(node.requestedQuantity())
                .append(" inv=").append(node.inventoryQuantity())
                .append(" craft=").append(node.craftedQuantity())
                .append(" buy=").append(node.boughtQuantity())
                .append(" missing=").append(node.missingQuantity())
                .append(" recipe=").append(node.recipeId())
                .append(" crafts=").append(node.craftCount())
                .append(" produced=").append(node.producedQuantity())
                .append(" char=").append(node.characterName())
                .append(" methods=").append(node.methods())
                .append(" states=").append(node.states())
                .append(" blocked=").append(node.blockedReasons())
                .append(" cash=").append(node.cashCostCopper())
                .append(" opportunity=").append(node.opportunityCostCopper())
                .append(" effective=").append(node.effectiveCostCopper())
                .append('\n');
        for (craft.CraftTraceNode child : node.children()) describe(child, depth + 1, out);
    }

    private static CraftingResolutionDetail[] details(CraftingProfitService service,
                                                      CraftingProfitService.ProfitData reference,
                                                      DiscChoice choice,
                                                      CraftingSettings settings) throws Exception {
        List<CraftingResolutionDetail> details = new ArrayList<>();
        for (Recipe recipe : reference.visibleRecipes()) {
            if (details.size() == COMPARED_DETAIL_ROWS) break;
            details.add(service.resolveDetail(recipe.recipeId, choice, settings));
        }
        return details.toArray(new CraftingResolutionDetail[0]);
    }

    /** Ordered text per item id, so a difference names the item rather than a map's toString. */
    private static Map<Integer, String> costs(Map<Integer, MaterialPurchaseCost> costs) {
        if (costs == null) return Map.of();
        Map<Integer, String> text = new java.util.TreeMap<>();
        costs.forEach((itemId, cost) -> text.put(itemId,
                cost == null ? "null" : cost.unitPriceCopper() + "/" + cost.totalPriceCopper()));
        return text;
    }

    private static List<Integer> ids(List<Recipe> recipes) {
        List<Integer> ids = new ArrayList<>(recipes.size());
        for (Recipe recipe : recipes) ids.add(recipe.recipeId);
        return ids;
    }

    /**
     * Writes the reference next to the JVM's temporary files, never into the repository, and
     * records only calculated quantities and costs keyed by recipe and item id.
     */
    private static Path record(String label,
                               CraftingProfitService.ProfitData reference,
                               CraftingResolutionDetail[] details) throws Exception {
        StringBuilder out = new StringBuilder("# ").append(label).append('\n')
                .append("rows=").append(reference.visibleRecipes().size())
                .append(" items=").append(reference.items().size())
                .append(" quotes=").append(reference.tp().size()).append('\n');
        for (Recipe recipe : reference.visibleRecipes()) {
            CraftResult row = reference.resultsByRecipeId().get(recipe.recipeId);
            out.append(recipe.recipeId).append(' ')
                    .append(row == null ? "<no result>" : row.craftableCount + " " + row.buyCostCopper
                            + " " + row.matsSellValueCopper + " " + row.totalMatsSellValueCopper
                            + " " + row.revenueCopper + " " + row.profitCopper
                            + " " + row.totalProfitCopper + " " + row.totalSellValueCopper
                            + " " + row.blockedReason + " " + row.missingToBuy
                            + " " + costs(row.materialPurchaseCosts))
                    .append('\n');
        }
        for (CraftingResolutionDetail detail : details) {
            out.append("detail ").append(detail.recipeId()).append(' ').append(detail.status()).append('\n')
                    .append(describe(detail));
        }

        Path file = Files.createTempFile("gw2-profit-capture-reference-",
                "-" + label.replaceAll("[^A-Za-z0-9]+", "-") + ".txt");
        Files.writeString(file, out.toString());
        return file;
    }
}
