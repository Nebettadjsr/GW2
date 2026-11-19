package craft;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Accepted source/yield/execution facts carried by the explainer trace (independent hand-computed oracles). */
class ForgeSourceFactsTest {
    private static final int X = 920_001, OTHER = 920_002, PARENT = 920_003, OUT = 920_004, STONE = 20796;
    private static final int XR = -920_001, PR = -920_003, VR = -920_004;

    private static CraftingSettings settings(boolean buy) {
        return new CraftingSettings(true, buy, buy ? 1_000_000 : 0, false, false, false).withMysticForge(true);
    }

    private static Recipe promotion() {
        return Recipe.forgeWithExpectedYield(XR, X, ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(X, 1), new Ingredient(OTHER, 1)));
    }

    @Test
    void rootTwoBatchesReuseOneSeedAndReportNetNotGross() {
        CraftTraceNode root = new SingleCraftExplainer().explainCoordinatedResult(promotion(), List.of(promotion()),
                Map.of(X, 1, OTHER, 10), Map.of(), Map.of(), List.of(),
                Map.of(X, new PriceQuote(100, 100)), settings(false), Set.of(XR), 2).root();
        CraftTraceNode.SourceFacts f = root.source();
        assertNotNull(f);
        assertEquals("MYSTIC_FORGE", f.kind());
        assertEquals(2, f.batches());
        assertEquals(20, f.grossProduced());
        assertEquals(1, f.reusedSeed());
        assertEquals(19, f.guaranteedNet());
        assertEquals(0, new BigDecimal("36.84").compareTo(f.expectedNet()));
        assertEquals(19, f.used() + f.surplus());
    }

    @Test
    void nestedNeedOfTwentyShowsThreeBatchesAndEightSurplus() {
        Recipe parent = new Recipe(PR, PARENT, 1, 0, "", List.of(new Ingredient(X, 20)), List.of(),
                RecipeSource.MYSTIC_FORGE);
        CraftTraceNode root = new SingleCraftExplainer().explainCoordinated(parent, List.of(parent, promotion()),
                Map.of(X, 1, OTHER, 10), Map.of(), Map.of(), List.of(), Map.of(PARENT, new PriceQuote(9_999, 9_999)),
                settings(false), Set.of(PR, XR)).root();
        CraftTraceNode node = root.children().stream().filter(c -> c.itemId() == X).findFirst().orElseThrow();
        CraftTraceNode.SourceFacts f = node.source();
        assertNotNull(f);
        assertEquals(3, f.batches());
        assertEquals(30, f.grossProduced());
        assertEquals(2, f.reusedSeed());
        assertEquals(28, f.guaranteedNet());
        assertEquals(0, new BigDecimal("54.76").compareTo(f.expectedNet()));
        assertEquals(20, f.used());
        assertEquals(8, f.surplus());
    }

    @Test
    void vendorMultipurchaseKeepsRealBatchesUnitsAndUsedVersusSurplus() {
        Recipe root = new Recipe(VR, OUT, 1, 0, "", List.of(new Ingredient(STONE, 5)), List.of(),
                RecipeSource.MYSTIC_FORGE);
        List<Recipe> all = new ArrayList<>(List.of(root));
        all.addAll(VendorAcquisitions.recipes());
        CraftTraceNode tree = new SingleCraftExplainer().withWallet(Map.of(23, 3)).explainCoordinatedResult(root, all,
                Map.of(), Map.of(), Map.of(), List.of(), Map.of(OUT, new PriceQuote(100_000, null)),
                settings(true), Set.of(VR), 2).root();
        CraftTraceNode stones = tree.children().stream().filter(c -> c.itemId() == STONE).findFirst().orElseThrow();
        CraftTraceNode.SourceFacts f = stones.source();
        assertNotNull(f, "vendor purchase must be recorded as an execution");
        assertEquals("VENDOR", f.kind());
        assertEquals(1, f.batches(), "10 Stones = one batch of the verified vendor offer");
        assertEquals(1, stones.craftCount());
        assertEquals(10, f.guaranteedNet());
        assertEquals(5, f.used(), "this occurrence uses 5 of the 10 bought; the second root batch draws the rest");
        assertEquals(5, f.surplus());
        assertNull(f.expectedNet());
        assertEquals(1, stones.currencies().get(0).quantity());
    }
}
