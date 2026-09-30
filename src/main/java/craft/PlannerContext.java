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

    /**
     * Memoized {@code CraftingResolver.firstRecipeFor(itemId, ctx, parentDiscipline)} answers.
     * Lives here rather than on the resolver so that every recipe's simulation - and both of a
     * simulation's phases - reuses one table, since the choice depends only on this context's
     * fixed {@link #recipesByOutput}, {@link #allowedRecipeIds}, {@link #coordinatedRoster},
     * {@link #tp} and the buy-side price mode. See {@link #withBuyingDisabled()}.
     */
    private final Map<FirstRecipeKey, Object> firstRecipeByKey;

    /** Null-tolerant placeholder: {@link #firstRecipeByKey} is concurrent and cannot store nulls. */
    static final Object NO_RECIPE = new Object();

    /** Key into {@link #firstRecipeByKey}: the two arguments the choice actually varies with. */
    record FirstRecipeKey(int itemId, String parentDiscipline) {
    }

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
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    private PlannerContext(Map<Integer, List<Recipe>> recipesByOutput,
                           Map<Integer, PriceQuote> tp,
                           CraftingSettings settings,
                           Set<Integer> allowedRecipeIds,
                           List<CharacterCraftingProfile> coordinatedRoster,
                           Map<Integer, List<String>> eligibleCharactersByRecipeId,
                           Map<FirstRecipeKey, Object> firstRecipeByKey) {
        this.recipesByOutput = recipesByOutput;
        this.tp = tp;
        this.settings = settings;
        this.allowedRecipeIds = allowedRecipeIds;
        this.coordinatedRoster = coordinatedRoster;
        this.eligibleCharactersByRecipeId = eligibleCharactersByRecipeId;
        this.firstRecipeByKey = firstRecipeByKey;
    }

    /**
     * This context with buying disabled - the zero-cash "own mats first" phase
     * {@link RecipeSimulator} runs before the buying phase. Keeps every other setting, and shares
     * both memo tables with this context: neither memoized answer depends on
     * {@code allowBuying}/{@code maxBuyCopper}. Eligibility does not consult settings at all, and
     * the first-recipe choice consults only the buy-side price mode ({@code listingBuy}) and the
     * candidate/price data, all of which are carried over unchanged.
     */
    public PlannerContext withBuyingDisabled() {
        CraftingSettings noBuySettings = new CraftingSettings(
                settings.useOwnMats,
                false,
                0,
                settings.listingSell,
                settings.listingBuy,
                settings.allowDailyCrafts);

        return new PlannerContext(recipesByOutput, tp, noBuySettings, allowedRecipeIds,
                coordinatedRoster, eligibleCharactersByRecipeId, firstRecipeByKey);
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

    /**
     * The shared first-recipe memo table, for {@code CraftingResolver.firstRecipeFor} to consult
     * and fill. A racing pair of threads may both compute the same key; the choice is a pure
     * function of fixed context data, so both arrive at the same answer and the first one stored
     * wins.
     */
    Map<FirstRecipeKey, Object> firstRecipeMemo() {
        return firstRecipeByKey;
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
