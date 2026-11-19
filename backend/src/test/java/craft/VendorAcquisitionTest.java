package craft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Verified Forge vendor acquisitions: whole batches, wallet/cash charged once, honest blocking, rollback. */
class VendorAcquisitionTest {

    private static final int OUT = 900_000, STONE = 20796, CRYSTAL = 20799, SCROLL = 20852, WINE = 19663;
    private static final int ROOT_ID = -900_001;

    private static CraftingSettings settings(boolean buying, int budget) {
        return new CraftingSettings(true, buying, budget, false, false, false).withMysticForge(true);
    }

    private static Recipe root(int ingredient, int count) {
        return new Recipe(ROOT_ID, OUT, 1, 0, "", List.of(new Ingredient(ingredient, count)), List.of(),
                RecipeSource.MYSTIC_FORGE);
    }

    private static CraftResult run(Recipe root, Map<Integer, Integer> wallet, Map<Integer, Integer> inv,
                                   Map<Integer, PriceQuote> extraTp, CraftingSettings s) {
        List<Recipe> all = new ArrayList<>(List.of(root));
        all.addAll(VendorAcquisitions.recipes());
        Map<Integer, PriceQuote> tp = new java.util.HashMap<>(extraTp);
        tp.put(OUT, new PriceQuote(100_000, null));
        return new CraftingPlanner().withWallet(wallet).evaluateAll(all, inv, Map.of(), tp, s, Set.of(ROOT_ID))
                .get(ROOT_ID);
    }

    @Test
    void twoCraftsBuyTenStonesInOneBatchForOneShard() {
        CraftResult r = run(root(STONE, 5), Map.of(23, 1), Map.of(), Map.of(), settings(true, 0));
        assertEquals(2, r.craftableCount, "10 Stones = one batch = 1 Shard supplies exactly two crafts");
        assertEquals(Map.of(23, 1), r.currencyTotal, "the Shard is charged once for both crafts");
        assertEquals(Map.of(STONE, 10), r.missingToBuy);
        assertEquals(0, r.buyCostCopper, "Shards are never turned into gold");
        assertTrue(r.unpricedCurrency);
        assertEquals(Set.of(23), r.unpricedCurrencyIds);
        assertEquals(BlockedReason.INSUFFICIENT_CURRENCY, r.blockedReason, "a third craft needs a second batch");
    }

    @Test
    void unknownAndZeroWalletBlockHonestly() {
        CraftResult unknown = run(root(STONE, 5), null, Map.of(), Map.of(), settings(true, 0));
        assertEquals(0, unknown.craftableCount);
        assertEquals(BlockedReason.WALLET_UNAVAILABLE, unknown.blockedReason);
        CraftResult zero = run(root(STONE, 5), Map.of(), Map.of(), Map.of(), settings(true, 0));
        assertEquals(0, zero.craftableCount);
        assertEquals(BlockedReason.INSUFFICIENT_CURRENCY, zero.blockedReason);
    }

    @Test
    void buyingDisabledOrOwnedStockNeverSpendsTheWallet() {
        CraftResult off = run(root(STONE, 5), Map.of(23, 9), Map.of(), Map.of(), settings(false, 0));
        assertEquals(0, off.craftableCount);
        assertEquals(BlockedReason.BUYING_DISABLED, off.blockedReason);
        CraftResult owned = run(root(STONE, 5), Map.of(23, 9), Map.of(STONE, 10), Map.of(), settings(true, 0));
        assertEquals(20, owned.craftableCount, "2 crafts from owned Stones, then 9 Shards x 10 Stones for 18 more");
        assertEquals(Map.of(23, 9), owned.currencyTotal);
        assertEquals(Map.of(STONE, 90), owned.missingToBuy);
        CraftResult ownedEnough = run(root(STONE, 5), Map.of(23, 9), Map.of(STONE, 10), Map.of(), settings(true, 0).withBuyingDisabled());
        assertEquals(2, ownedEnough.craftableCount);
        assertTrue(ownedEnough.currencyTotal.isEmpty());
    }

    @Test
    void crystalIsFiveForThreeShardsAndScrollFiftyShards() {
        assertEquals(0, run(root(CRYSTAL, 6), Map.of(23, 5), Map.of(), Map.of(), settings(true, 0)).craftableCount,
                "6 Crystals need two batches = 6 Shards");
        CraftResult crystals = run(root(CRYSTAL, 6), Map.of(23, 6), Map.of(), Map.of(), settings(true, 0));
        assertEquals(1, crystals.craftableCount);
        assertEquals(Map.of(23, 6), crystals.currencyTotal);
        assertEquals(0, run(root(SCROLL, 1), Map.of(23, 49), Map.of(), Map.of(), settings(true, 0)).craftableCount);
        CraftResult scroll = run(root(SCROLL, 1), Map.of(23, 50), Map.of(), Map.of(), settings(true, 0));
        assertEquals(1, scroll.craftableCount);
        assertEquals(Map.of(23, 50), scroll.currencyTotal);
    }

    @Test
    void wineIsFixedCashUnderTheSharedBudgetRegardlessOfBuyMode() {
        CraftResult r = run(root(WINE, 1), Map.of(), Map.of(), Map.of(), settings(true, 5_000));
        assertEquals(1, r.craftableCount, "a second Wine (5008c) would exceed the 5000c budget");
        assertEquals(2504, r.buyCostCopper);
        assertEquals(BlockedReason.INSUFFICIENT_BUDGET, r.blockedReason);
        assertEquals(new MaterialPurchaseCost(2504, 2504), r.materialPurchaseCosts.get(WINE));
        assertTrue(r.unpricedCurrencyIds.isEmpty(), "gold is never an unpriced currency");
        CraftResult both = run(root(WINE, 1), Map.of(), Map.of(), Map.of(), settings(true, 0));
        assertEquals(250, both.craftableCount);
        assertEquals(250 * 2504, both.buyCostCopper);
        // listing-buy mode makes no difference to a vendor price
        CraftingSettings buyListing = new CraftingSettings(true, true, 0, false, true, false).withMysticForge(true);
        assertEquals(250 * 2504, run(root(WINE, 1), Map.of(), Map.of(), Map.of(), buyListing).buyCostCopper);
    }

    @Test
    void aCheaperRealTradingPostRouteKeepsTheNormalComparison() {
        CraftResult cheap = run(root(WINE, 1), Map.of(), Map.of(), Map.of(WINE, new PriceQuote(null, 1000)), settings(true, 0));
        assertEquals(1000, cheap.buyCostCopper / cheap.craftableCount);
        CraftResult dear = run(root(WINE, 1), Map.of(), Map.of(), Map.of(WINE, new PriceQuote(null, 9000)), settings(true, 0));
        assertEquals(2504, dear.buyCostCopper / dear.craftableCount, "the vendor beats a dearer quote");
        assertEquals(2504, dear.materialPurchaseCosts.get(WINE).unitPriceCopper(), "never priced from the TP quote");
    }

    @Test
    void rejectedBudgetAttemptRollsBackShardsSurplusAndCash() {
        Recipe both = new Recipe(ROOT_ID, OUT, 1, 0, "",
                List.of(new Ingredient(STONE, 5), new Ingredient(WINE, 1)), List.of(), RecipeSource.MYSTIC_FORGE);
        CraftResult r = run(both, Map.of(23, 5), Map.of(), Map.of(), settings(true, 2_500));
        assertEquals(0, r.craftableCount);
        assertEquals(BlockedReason.INSUFFICIENT_BUDGET, r.blockedReason);
        assertTrue(r.currencyTotal.isEmpty(), "the Stone batch of the rejected craft is rolled back");
        assertTrue(r.missingToBuy.isEmpty());
        assertEquals(0, r.buyCostCopper);
        CraftResult ok = run(both, Map.of(23, 5), Map.of(), Map.of(), settings(true, 2_504));
        assertEquals(1, ok.craftableCount);
        assertEquals(Map.of(23, 1), ok.currencyTotal);
        assertEquals(2504, ok.buyCostCopper);
        assertEquals(Map.of(STONE, 10, WINE, 1), ok.missingToBuy);
    }

    @Test
    void protectedTokensAreNotConsumedSoTheVendorSuppliesThem() {
        CraftingSettings protectedStones = new CraftingSettings(true, true, 0, false, false, false, true,
                Set.of(STONE), Set.of(), Set.of(), Map.of(), Map.of(), List.of(), true);
        CraftResult r = run(root(STONE, 5), Map.of(23, 1), Map.of(STONE, 10), Map.of(), protectedStones);
        assertEquals(2, r.craftableCount);
        assertEquals(Map.of(23, 1), r.currencyTotal);
    }

    @Test
    void verifiedFactsFileAndVendorTableAgree() throws Exception {
        JsonNode vendors = new ObjectMapper()
                .readTree(new File("tools/mystic-forge/source/verified-facts.json")).get("vendors");
        assertEquals(VendorAcquisitions.recipes().size(), vendors.size());
        for (JsonNode v : vendors) {
            Recipe r = VendorAcquisitions.recipes().stream()
                    .filter(x -> x.outputItemId == v.get("itemId").asInt()).findFirst().orElseThrow();
            assertEquals(v.get("quantity").asInt(), r.outputCount);
            assertEquals(v.path("copper").asInt(0), r.vendorCopper);
            int units = r.currencies.isEmpty() ? 0 : r.currencies.getFirst().count();
            assertEquals(v.path("currencyUnits").asInt(0), units);
        }
    }

    @Test
    void vendorIdsNeverCollideWithForgeOrArenaNetIds() {
        for (Recipe r : VendorAcquisitions.recipes()) {
            assertTrue(r.recipeId <= -VendorAcquisitions.ID_BASE);
            assertTrue(r.isVendor());
        }
    }
}
