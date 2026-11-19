package craft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlannerContext {
    public final Map<Integer, List<Recipe>> recipesByOutput;
    public final Map<Integer, PriceQuote> tp;
    public final CraftingSettings settings;
    public final Set<Integer> allowedRecipeIds;

    /** How currency requirements are checked (see {@link WalletMode}). */
    public enum WalletMode { ENFORCED, IGNORED }

    /**
     * Account wallet balances by currency id, or {@code null} when the wallet is unknown (no permission,
     * never refreshed, failed refresh). Unknown is neither zero nor unlimited: a recipe consuming a currency is
     * then blocked with {@link BlockedReason#WALLET_UNAVAILABLE}. Currency ids are not item ids.
     */
    public final Map<Integer, Integer> wallet;
    public final WalletMode walletMode;

    /**
     * Budget-retry mode: when both a purchase and a currency-consuming craft are feasible for a requirement, the
     * craft wins instead of the fully priced purchase. {@link RecipeSimulator} uses it only after the purchase route
     * exceeded the cumulative {@code maxBuyCopper}.
     */
    public final boolean preferCurrencyCraft;

    /** Vendor sell value by item id (DOMAIN_SPEC.md 11.2); empty when unknown. Values <= 0 mean none. */
    public final Map<Integer, Integer> vendorValues;

    /**
     * Candidate characters for coordinated multi-character planning (DOMAIN_SPEC.md section
     * 2.2.1 / STORY-DOM-014's "All"/generic-discipline scope). {@code null} means "not
     * coordinated" - the single-character/legacy resolution path is used instead, exactly as
     * before this field existed, and {@link #eligibleCharactersFor(Recipe)}
     * must never be called.
     */
    public final List<CharacterCraftingProfile> coordinatedRoster;

    /**
     * Memoized {@link #eligibleCharactersFor(Recipe)} answers, keyed by recipe id. Eligibility is a
     * pure function of the recipe's disciplines/minimum rating and {@link #coordinatedRoster}, all
     * of which are fixed for the lifetime of a context, so memoizing cannot change an answer - it
     * only stops the recursive planner from re-splitting the same discipline string and rescanning
     * the same roster millions of times per page load (STORY-PERF-001). Shared with contexts
     * derived via {@link #withBuyingDisabled()}, which change neither input.
     */
    private final Map<Integer, List<String>> eligibleCharactersByRecipeId;

    public PlannerContext(Map<Integer, List<Recipe>> recipesByOutput,
                          Map<Integer, PriceQuote> tp,
                          CraftingSettings settings,
                          Set<Integer> allowedRecipeIds) {
        this(recipesByOutput, tp, settings, allowedRecipeIds, null);
    }

    public PlannerContext(Map<Integer, List<Recipe>> recipesByOutput,
                          Map<Integer, PriceQuote> tp,
                          CraftingSettings settings,
                          Set<Integer> allowedRecipeIds,
                          List<CharacterCraftingProfile> coordinatedRoster) {
        this(recipesByOutput, tp, settings, allowedRecipeIds, coordinatedRoster,
                new ConcurrentHashMap<>(), Map.of(), null, WalletMode.ENFORCED, false);
    }

    private PlannerContext(Map<Integer, List<Recipe>> recipesByOutput,
                           Map<Integer, PriceQuote> tp,
                           CraftingSettings settings,
                           Set<Integer> allowedRecipeIds,
                           List<CharacterCraftingProfile> coordinatedRoster,
                           Map<Integer, List<String>> eligibleCharactersByRecipeId,
                           Map<Integer, Integer> vendorValues,
                           Map<Integer, Integer> wallet,
                           WalletMode walletMode,
                           boolean preferCurrencyCraft) {
        this.preferCurrencyCraft = preferCurrencyCraft;
        this.vendorValues = vendorValues;
        this.wallet = wallet;
        this.walletMode = walletMode;
        this.recipesByOutput = recipesByOutput;
        this.tp = tp;
        this.settings = settings;
        this.allowedRecipeIds = allowedRecipeIds;
        this.coordinatedRoster = coordinatedRoster;
        this.eligibleCharactersByRecipeId = eligibleCharactersByRecipeId;
    }

    /**
     * This context with buying disabled - the zero-cash "own mats first" phase
     * {@link RecipeSimulator} runs before the buying phase. Keeps every other setting, and shares
     * the eligibility memo with this context: eligibility does not consult buying settings.
     */
    public PlannerContext withBuyingDisabled() {
        CraftingSettings noBuySettings = settings.withBuyingDisabled();

        return new PlannerContext(recipesByOutput, tp, noBuySettings, allowedRecipeIds,
                coordinatedRoster, eligibleCharactersByRecipeId, vendorValues, wallet, walletMode, preferCurrencyCraft);
    }

    /** This context with vendor sell values for the untradeable-material fallback; shares the memo tables. */
    public PlannerContext withVendorValues(Map<Integer, Integer> vendorValues) {
        return new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds,
                coordinatedRoster, eligibleCharactersByRecipeId, vendorValues, wallet, walletMode, preferCurrencyCraft);
    }

    /** This context with the account wallet ({@code null} = unknown); shares the memo tables. */
    public PlannerContext withWallet(Map<Integer, Integer> wallet) {
        return new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds,
                coordinatedRoster, eligibleCharactersByRecipeId, vendorValues,
                wallet == null ? null : Map.copyOf(wallet), walletMode, preferCurrencyCraft);
    }

    /** Hypothetical single-craft economics: currency is still tallied but never limits the craft. */
    public PlannerContext withWalletIgnored() {
        return new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds,
                coordinatedRoster, eligibleCharactersByRecipeId, vendorValues, wallet, WalletMode.IGNORED, preferCurrencyCraft);
    }

    /** Prefer currency crafts over fully valued routes during a budget retry; shares the memo tables. */
    public PlannerContext withCurrencyCraftPreferred() {
        return new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds,
                coordinatedRoster, eligibleCharactersByRecipeId, vendorValues, wallet, walletMode, true);
    }

    public boolean isCoordinated() {
        return coordinatedRoster != null;
    }

    /**
     * Characters in {@link #coordinatedRoster} who may perform {@code recipe}: at least one of
     * the recipe's disciplines is a discipline the character has trained at a rating meeting
     * {@code recipe.minRating} (DOMAIN_SPEC.md sections 7-8). Never call when
     * {@link #coordinatedRoster} is {@code null}.
     */
    public List<String> eligibleCharactersFor(Recipe recipe) {
        return eligibleCharactersByRecipeId.computeIfAbsent(recipe.recipeId, id -> computeEligible(recipe));
    }

    private List<String> computeEligible(Recipe recipe) {
        Set<String> recipeDisciplines = splitDisciplines(recipe.disciplinesText);
        List<String> eligible = new ArrayList<>();

        for (CharacterCraftingProfile profile : coordinatedRoster) {
            for (String discipline : recipeDisciplines) {
                Integer rating = profile.ratingByDiscipline().get(discipline);
                if (rating != null && rating >= recipe.minRating) {
                    eligible.add(profile.name());
                    break;
                }
            }
        }

        return List.copyOf(eligible);
    }

    private static Set<String> splitDisciplines(String disciplinesText) {
        Set<String> result = new HashSet<>();
        if (disciplinesText == null) return result;
        for (String part : disciplinesText.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
