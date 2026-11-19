package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static craft.CraftTestFixtures.ingredient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #372: the resolution trace carries the backend's exact currency units at the node that spends them. */
class SingleCraftExplainerCurrencyTraceTest {

    private static final int NOTE = 61;
    private static final CraftingSettings OWN_ONLY = new CraftingSettings(true, false, 0, false, false, true);

    private static final Recipe INNER = new Recipe(2, 200, 1, 0, "Artificer", List.of(ingredient(300, 1)),
            List.of(new CurrencyRequirement(NOTE, 30)));
    private static final Recipe PARENT = new Recipe(1, 100, 1, 0, "Artificer", List.of(ingredient(200, 1)),
            List.of(new CurrencyRequirement(NOTE, 10)));

    private static CraftTraceNode root(Map<Integer, Integer> wallet, int crafts) {
        return new SingleCraftExplainer().withWallet(wallet).explainCoordinatedResult(PARENT,
                List.of(PARENT, INNER), Map.of(300, 10), Map.of(), Map.of(),
                List.of(new CharacterCraftingProfile("A", Map.of("Artificer", 500))),
                Map.of(), OWN_ONLY, Set.of(1, 2), crafts).root();
    }

    @Test
    void eachNodeCarriesTheUnitsOfItsOwnCraftAcrossAllCountedCrafts() {
        CraftTraceNode node = root(Map.of(NOTE, 1000), 3);
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 30)), node.currencies(), "3 crafts x 10");
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 90)), node.children().get(0).currencies(),
                "nested 3 crafts x 30, not the all-plan total of 120");
        assertTrue(node.children().get(0).children().get(0).currencies().isEmpty(), "item-only node");
    }

    @Test
    void aBlockedAttemptStillShowsTheRequirementOfOneAttempt() {
        CraftTraceNode node = root(Map.of(NOTE, 5), 1);
        assertTrue(node.blockedReasons().contains(BlockedReason.INSUFFICIENT_CURRENCY), node.toString());
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 10)), node.currencies());
    }
}
