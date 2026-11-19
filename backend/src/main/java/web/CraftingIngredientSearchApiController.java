package web;

import craft.CraftingGraph;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import craft.Recipe;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import craft.Ingredient;
import repo.ItemRepository;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import parser.MysticForgeCatalogLoader;
import repo.CraftingGraphCache;
import web.dto.CraftingIngredientSearchResponse;

/** Search-only access to the cached static dependency graph; it never enters a crafting resolver. */
@RestController
@RequestMapping("/api/crafting")
public class CraftingIngredientSearchApiController {
    private final CraftingGraphCache graphCache;

    private final Supplier<List<Recipe>> forgeRecipes;
    private final ForgeNames forgeNames;

    /** One batched read of the cached names of the Forge catalog's reference ids (never an upstream call). */
    @FunctionalInterface
    interface ForgeNames { Map<Integer, String> load(Set<Integer> forgeItemIds) throws Exception; }

    @Autowired
    public CraftingIngredientSearchApiController(CraftingGraphCache graphCache) {
        this(graphCache, () -> MysticForgeCatalogLoader.bundled().recipes(), new ForgeNames() {
            private final ItemRepository items = new ItemRepository();
            @Override public Map<Integer, String> load(Set<Integer> ids) throws Exception {
                Map<Integer, String> names = new HashMap<>();
                items.loadItems(ids).forEach((id, item) -> { if (item.name != null) names.put(id, item.name); });
                return names;
            }
        });
    }

    CraftingIngredientSearchApiController(CraftingGraphCache graphCache, Supplier<List<Recipe>> forgeRecipes) {
        this(graphCache, forgeRecipes, ids -> Map.of());
    }

    CraftingIngredientSearchApiController(CraftingGraphCache graphCache, Supplier<List<Recipe>> forgeRecipes,
                                          ForgeNames forgeNames) {
        this.graphCache = graphCache;
        this.forgeRecipes = forgeRecipes;
        this.forgeNames = forgeNames;
    }

    @GetMapping(path = "/ingredient-search", produces = MediaType.APPLICATION_JSON_VALUE)
    public CraftingIngredientSearchResponse search(@RequestParam("query") String query) throws Exception {
        CraftingGraph graph = graphCache.load();
        List<Recipe> forge = forgeRecipes.get();
        // Forge-only names (e.g. a Stone no ordinary recipe references) come from the cached item metadata in one
        // batched read of the catalog's positive ids - only when Forge recipes exist and the query is not purely
        // numeric - and never from an upstream request.
        Map<Integer, String> names = Map.of();
        if (!forge.isEmpty() && query != null && !query.isBlank()) {
            Set<Integer> ids = new TreeSet<>();
            for (Recipe r : forge) {
                if (r.outputItemId > 0) ids.add(r.outputItemId);
                for (Ingredient i : r.ingredients) if (i.itemId > 0) ids.add(i.itemId);
            }
            names = forgeNames.load(ids);
        }
        return new CraftingIngredientSearchResponse(graph.parentRecipeIdsForIngredientName(query, forge, names).stream()
                .sorted().toList());
    }
}
