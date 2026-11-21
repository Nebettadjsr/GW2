package web;

import craft.CraftingGraph;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import repo.CraftingGraphCache;
import web.dto.CraftingIngredientSearchResponse;

/** Search-only access to the cached static dependency graph; it never enters a crafting resolver. */
@RestController
@RequestMapping("/api/crafting")
public class CraftingIngredientSearchApiController {
    private final CraftingGraphCache graphCache;

    @Autowired
    public CraftingIngredientSearchApiController(CraftingGraphCache graphCache) {
        this.graphCache = graphCache;
    }

    @GetMapping(path = "/ingredient-search", produces = MediaType.APPLICATION_JSON_VALUE)
    public CraftingIngredientSearchResponse search(@RequestParam("query") String query) throws Exception {
        // Ordinary graph only: Profit/Discovery no longer plan Mystic Forge edges, so neither does their search.
        CraftingGraph graph = graphCache.load();
        return new CraftingIngredientSearchResponse(graph.parentRecipeIdsForIngredientName(query).stream()
                .sorted().toList());
    }
}
