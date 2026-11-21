package web.dto;

import java.util.List;

/** Wire shapes of the Mystic Forge catalogue (public, cached metadata only) and one selected project (account-scoped). */
public final class MysticForgeDtos {
    private MysticForgeDtos() { }

    /** outcome: FIXED (guaranteed output), EXPECTED (verified mean; output varies), REFERENCE_RANDOM (no yield claimed). */
    public record RecipeRow(int recipeId, int outputItemId, String name, String iconUrl, String itemType,
                            String itemSubtype, String category, String outcome, int outputCount,
                            String expectedMean, Integer expectedMax, Integer buyUnitCopper, Integer sellUnitCopper,
                            String sourcePage, Long sourceRevision, String wikiUrl, int ingredientKinds,
                            String rarity, String weightClass, String pathLabel, Integer materialTier) { }

    public record Catalogue(String status, String source, String license, int recipeCount, int referenceCount,
                            List<RecipeRow> recipes, boolean completing) { }

    /** Optional menu settings (null = default); unknown values are rejected, never guessed. */
    public record ProjectRequest(Integer recipeId, Long target, String priceMode, Boolean useOwn, Boolean valueOwn,
                                 String daily, Boolean promotions, String reduce) {
        public ProjectRequest(Integer recipeId, Long target) { this(recipeId, target, null, null, null, null, null, null); }
    }

    /** The effective settings the backend actually planned with. */
    public record SettingsDto(String priceMode, boolean useOwn, boolean valueOwn, String daily, boolean promotions,
                              String reduce) { }

    /** Opportunity value of consumed owned materials: known subtotal and the items that could not be priced. */
    public record OwnValueDto(long knownCopper, List<Item> unpricedItems) { }

    /** Estimated (never guaranteed) Mystic Clover Forge attempts: missing Clovers, rounded-up attempts, percent chance. */
    /**
     * Backend comparison of buying the requested output with the plan. Unit quotes are per item at the selected price
     * mode (buy = ask/bid, sell = bid); totals are for the whole project. A null amount is unknown, never zero.
     * {@code conditional}: currencies, unpriced parts or an estimate remain outside the gold figures;
     * {@code excludesOwnValue}: own-material value is switched off, so savings are cash-only, not economic profit.
     */
    public record ComparisonDto(Long unitBuyCopper, Long unitSellCopper, Long outputBuyTotalCopper, Long savingsCopper,
                                boolean conditional, boolean excludesOwnValue) { }

    public record CloverEstimateDto(long missing, long attempts, int percent) { }

    public record Item(int itemId, String name, String iconUrl, String wikiUrl) { }

    /**
     * produced = units the node's step actually makes/buys (crafts x output; differs from demand for batch outputs);
     * newSurplus = produced beyond this node's own unmet demand, kept for later reuse (never paid twice).
     * step = 1-based position of this node's recipe in the backend's dependency-ordered step list (null = none).
     * needed == fromOwned + fromSurplus + toCraft + toAcquire; toCraft is planned work, never owned progress. */
    public record NodeDto(Item item, long needed, long fromOwned, long fromSurplus, long toCraft, long toAcquire,
                          Integer recipeId, String recipeSource, long crafts, int minRating, String disciplines,
                          String cutoff, List<NodeDto> children, Boolean recipeKnown, boolean performable,
                          String assignedCharacter, String hint,
                          long produced, long newSurplus, Integer step) { }

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
    public record ShoppingDto(Item item, long needed, long fromOwned, long toAcquire, Long unitCopper, Long knownCopper,
                              String source, long purchases, List<CurrencyCostDto> currencyCosts, String hint) { }

    /** A currency cost of a vendor purchase: exact, never converted to gold. */
    public record CurrencyCostDto(int currencyId, String name, long count) { }

    /** Total consumed-stock value: zero when valuation is off; null when its value is unavailable. */
    public record OwnedItemDto(Item item, long count, List<String> locations, Long valueCopper) { }

    public record RecipeCurrencyDto(int currencyId, String name, String iconUrl, String wikiUrl, long needed,
                                    Long acquired, Integer progressPercent, boolean coveredByParent, boolean approximate) { }

    public record RecipePartDto(Item item, long quantity, List<RecipePartDto> children, List<RecipeCurrencyDto> currencies,
                                Long copper, Long attempts, boolean approximate, String cutoff,
                                long acquired, int progressPercent, boolean coveredByParent) { }

    public record MaterialProgressDto(double percent, boolean estimated, boolean partial) { }

    /** Direct inputs for exactly one batch of the selected recipe, independent of target quantity and owned stock. */
    public record BaseIngredientDto(Item item, long quantity) { }

    public record CraftingIngredientDto(Item item, long quantity) { }

    /** Aggregated, dependency-ordered work; distinct recipes and assigned crafting characters stay distinct. */
    public record CraftingStepDto(Item item, Integer recipeId, String recipeSource, long crafts, long produced,
                                  long toAcquire, long newSurplus, String disciplines, String hint, Boolean recipeKnown,
                                  boolean performable, String assignedCharacter, String cutoff, boolean root,
                                  List<CraftingIngredientDto> ingredients) { }

    public record Project(int recipeId, Item output, String outcome, String targetKind, long requestedTarget, long batches,
                          Long guaranteedOutput, boolean complete, String incompleteReason, boolean walletKnown,
                          long knownGoldCopper, boolean goldCostPartial, List<Item> unpricedItems, NodeDto tree,
                          List<ShoppingDto> shopping, List<CurrencyDto> currencies, String note,
                          SeedDto seed, EconomicsDto economics, NotesDto notes, CalculationFreshnessDto freshness,
                          SettingsDto settings, OwnValueDto ownValue, CloverEstimateDto clover, long rootOwned,
                          ComparisonDto comparison, List<OwnedItemDto> ownedItems, RecipePartDto recipeTree,
                          MaterialProgressDto materialProgress, List<BaseIngredientDto> baseIngredients,
                          List<CraftingStepDto> craftingSteps) { }
}
