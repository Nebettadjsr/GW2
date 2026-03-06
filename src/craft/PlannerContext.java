package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.List;
import java.util.Map;

public class PlannerContext {

    public final Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput;
    public final Map<Integer, TpPriceRepository.TpQuote> tp;
    public final CraftingSettings settings;

    public PlannerContext(
            Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput,
            Map<Integer, TpPriceRepository.TpQuote> tp,
            CraftingSettings settings
                         ) {
        this.recipesByOutput = recipesByOutput;
        this.tp = tp;
        this.settings = settings;
    }
}