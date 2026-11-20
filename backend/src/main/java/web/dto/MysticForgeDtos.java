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
                            List<RecipeRow> recipes, boolean completing) { }

    public record ProjectRequest(Integer recipeId, Long target) { }

    public record Item(int itemId, String name, String iconUrl, String wikiUrl) { }

    /**
     * produced = units the node's step actually makes/buys (crafts x output; differs from demand for batch outputs);
     * newSurplus = produced beyond this node's own unmet demand, kept for later reuse (never paid twice).
     * needed == fromOwned + fromSurplus + toCraft + toAcquire; toCraft is planned work, never owned progress. */
    public record NodeDto(Item item, long needed, long fromOwned, long fromSurplus, long toCraft, long toAcquire,
                          Integer recipeId, String recipeSource, long crafts, int minRating, String disciplines,
                          String cutoff, List<NodeDto> children, Boolean recipeKnown, boolean performable,
                          String assignedCharacter, String hint,
                          long produced, long newSurplus) { }

    /** null owned/missing = wallet unknown. */
    public record CurrencyDto(int currencyId, String name, String iconUrl, String wikiUrl, long needed, Long owned,
                              Long missing) { }

    /** One external seed then guaranteed reuse only; expected figures are estimates, guaranteed figures are exact. */
    public record SeedDto(long externalSeedUnits, long reusedSeedUnits, long grossGuaranteedOutput,
                          long netGuaranteedOutput, String grossExpectedOutput, String netExpectedOutput) { }

    /**
     * Backend-computed promotion economics (copper). A null amount is not claimable (unpriced output/inputs). Currencies
     * stay separate and unpriced; {@code conditional} marks a margin that excludes them or other unpriced parts.
     */
    public record EconomicsDto(Integer outputUnitSellCopper, Long expectedGrossProceedsCopper, Long feeCopper,
                               Long expectedNetProceedsCopper, long knownShoppingCopper, long goldInputCopper,
                               List<CurrencyDto> currencyInputs, List<Item> unpricedInputs, Long marginOnShoppingCopper,
                               Long marginCopper, boolean conditional, boolean fixedOutput) { }

    /**
     * Research Notes of the selected how-to. null walletUsed/shortfall = wallet unknown (no production promised).
     * estimateCopper values only wallet-used Notes (null = unavailable, see estimateIssue); produced Notes are charged
     * by their crafting inputs. {@code estimate} carries both recipe variants for the shared popup.
     */
    public record NotesDto(long needed, boolean walletKnown, Long walletUsed, Long shortfall, long containersNeeded,
                           long containersCrafted, long producedNotes, long surplus, Integer producerRecipeId,
                           Long estimateCopper, String estimateIssue, Integer basisRecipeId,
                           List<CurrencyCostsDto.VariantDto> variants, long ownedContainers, long ownedNotes,
                           long acquireContainers, long uncoveredNotes, boolean producerLocked) { }

    /** unitCopper null = UNPRICED (never zero). */
    public record ShoppingDto(Item item, long needed, long fromOwned, long toAcquire, Long unitCopper, Long knownCopper) { }

    public record Project(int recipeId, Item output, String outcome, String targetKind, long requestedTarget, long batches,
                          Long guaranteedOutput, boolean complete, String incompleteReason, boolean walletKnown,
                          long knownGoldCopper, boolean goldCostPartial, List<Item> unpricedItems, NodeDto tree,
                          List<ShoppingDto> shopping, List<CurrencyDto> currencies, String note,
                          SeedDto seed, EconomicsDto economics, NotesDto notes, CalculationFreshnessDto freshness) { }
}
