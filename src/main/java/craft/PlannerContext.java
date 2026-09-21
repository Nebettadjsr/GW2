package craft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        this.recipesByOutput = recipesByOutput;
        this.tp = tp;
        this.settings = settings;
        this.allowedRecipeIds = allowedRecipeIds;
        this.coordinatedRoster = coordinatedRoster;
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

        return eligible;
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
