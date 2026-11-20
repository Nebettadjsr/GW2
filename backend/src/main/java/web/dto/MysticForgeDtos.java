package web.dto;

import java.util.List;

/** Wire shapes of the Mystic Forge catalogue (public, cached metadata only) and one selected project (account-scoped). */
public final class MysticForgeDtos {
    private MysticForgeDtos() { }

    /** outcome: FIXED (guaranteed output), EXPECTED (verified mean; output varies), REFERENCE_RANDOM (no yield claimed). */
    public record RecipeRow(int recipeId, int outputItemId, String name, String iconUrl, String itemType,
                            String itemSubtype, String category, String outcome, int outputCount,
                            String expectedMean, Integer expectedMax, Integer buyUnitCopper, Integer sellUnitCopper,
                            String sourcePage, Long sourceRevision, String wikiUrl, int ingredientKinds) { }

    public record Catalogue(String status, String source, String license, int recipeCount, int referenceCount,
                            List<RecipeRow> recipes) { }

    public record ProjectRequest(Integer recipeId, Long target) { }

    public record Item(int itemId, String name, String iconUrl, String wikiUrl) { }

    /** needed == fromOwned + fromSurplus + toCraft + toAcquire; toCraft is planned work, never owned progress. */
    public record NodeDto(Item item, long needed, long fromOwned, long fromSurplus, long toCraft, long toAcquire,
                          Integer recipeId, String recipeSource, long crafts, int minRating, String disciplines,
                          String cutoff, List<NodeDto> children) { }

    /** null owned/missing = wallet unknown. */
    public record CurrencyDto(int currencyId, String name, long needed, Long owned, Long missing) { }

    /** unitCopper null = UNPRICED (never zero). */
    public record ShoppingDto(Item item, long needed, long fromOwned, long toAcquire, Long unitCopper, Long knownCopper) { }

    public record Project(int recipeId, Item output, String outcome, String targetKind, long requestedTarget, long batches,
                          Long guaranteedOutput, boolean complete, String incompleteReason, boolean walletKnown,
                          long knownGoldCopper, boolean goldCostPartial, List<Item> unpricedItems, NodeDto tree,
                          List<ShoppingDto> shopping, List<CurrencyDto> currencies, String note,
                          CalculationFreshnessDto freshness) { }
}
