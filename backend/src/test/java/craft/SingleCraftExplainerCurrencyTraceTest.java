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
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 30, 30, 0, 0, 0, 1000)), node.currencies(), "3 crafts x 10");
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 90, 90, 0, 0, 0, 1000)), node.children().get(0).currencies(),
                "nested 3 crafts x 30, not the all-plan total of 120");
        assertTrue(node.children().get(0).children().get(0).currencies().isEmpty(), "item-only node");
    }

    @Test
    void aBlockedAttemptStillShowsTheRequirementOfOneAttempt() {
        CraftTraceNode node = root(Map.of(NOTE, 5), 1);
        assertTrue(node.blockedReasons().contains(BlockedReason.INSUFFICIENT_CURRENCY), node.toString());
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 10, 5, 0, 0, 5, 5)), node.currencies(),
                "wallet 5 of 10 needed: 5 missing, balance captured");
    }

    @Test
    void craftedNotesAppearAsProducerSubtreeAndSourceSplit() {
        Recipe prr = new Recipe(15120, 104934, 1, 0, "Artificer", List.of(ingredient(300, 4)));
        // 10 + 30 per craft x 1 = 40 Notes; wallet 15 -> 25 crafted = one 150 batch, 125 surplus.
        CraftTraceNode node = new SingleCraftExplainer().withWallet(Map.of(NOTE, 15)).explainCoordinatedResult(PARENT,
                List.of(PARENT, INNER, prr), Map.of(300, 10), Map.of(), Map.of(),
                List.of(new CharacterCraftingProfile("A", Map.of("Artificer", 500))),
                Map.of(), OWN_ONLY, Set.of(1, 2, 15120), 1).root();
        assertTrue(node.blockedReasons().isEmpty(), node.toString());
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 10, 10, 0, 0, 0, 15)), node.currencies());
        CraftTraceNode inner = node.children().get(0);
        assertEquals(List.of(new CraftTraceNode.CurrencyQuantity(NOTE, 30, 5, 0, 25, 0, 15)), inner.currencies());
        CraftTraceNode producer = inner.children().stream().filter(c -> c.itemId() == 104934).findFirst().orElseThrow();
        assertEquals(1, producer.craftedQuantity());
        assertEquals(Integer.valueOf(15120), producer.recipeId());
        assertEquals(4, producer.children().get(0).requestedQuantity());
    }

    @Test
    void aProducerNeededOnlyByALaterCraftDoesNotSplitTheOrdinaryIngredientNodes() {
        Recipe prr = new Recipe(15120, 104934, 1, 0, "Artificer", List.of(ingredient(300, 4)));
        // Wallet 45 covers craft 1 (40 Notes) entirely; craft 2 needs 35 more, which must be produced.
        CraftTraceNode node = new SingleCraftExplainer().withWallet(Map.of(NOTE, 45)).explainCoordinatedResult(PARENT,
                List.of(PARENT, INNER, prr), Map.of(300, 20), Map.of(), Map.of(),
                List.of(new CharacterCraftingProfile("A", Map.of("Artificer", 500))),
                Map.of(), OWN_ONLY, Set.of(1, 2, 15120), 2).root();
        // The producer appears only for craft 2 yet must not shift or split the ordinary Item 200 node.
        assertEquals(1, node.children().stream().filter(c -> c.itemId() == 200).count(), node.toString());
        assertEquals(2, node.children().stream().filter(c -> c.itemId() == 200).findFirst().orElseThrow().requestedQuantity());
        assertEquals(1, node.children().stream().filter(c -> c.itemId() == 104934).count(), node.toString());
    }
}
