package application;

import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.Node;
import craft.PlannerContext;
import craft.PriceQuote;
import craft.Recipe;
import craft.RecipeTreeBuilder;
import craft.SingleCraftExplainer;
import craft.SingleCraftExplanation;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application-layer use case for the Crafting Discovery flow (TARGET_ARCHITECTURE.md §8): loads
 * the missing-discoverable-recipe/graph/inventory/price/item data the documented flow needs and
 * invokes the single-character planner and (lazily) the recipe-tree builder. Holds no JavaFX
 * dependency and reimplements no domain calculation; it only coordinates repositories and
 * {@code craft.*} domain calls on their behalf (STORY-APP-002).
 */
public class CraftingDiscoveryService {

    private final RecipeRepository recipeRepo;
    private final InventoryRepository invRepo;
    private final TpPriceRepository tpRepo;
    private final ItemRepository itemRepo;
    private final CraftingGraphCache graphCache;
    private final CraftingPlanner planner;

    // Scoped to the most recent reload(); backs getResultByRecipeId/itemName/itemSellUnit so a
    // stale cache from an earlier reload() - or one skipped by the "no missing recipes" early
    // return below - is never silently replaced with emptier data (mirrors STORY-APP-001
    // acceptance criterion 3, and preserves this controller's original "leave caches untouched
    // when there is nothing discoverable" behavior).
    private List<Recipe> lastAllRecipes = List.of();
    private CraftingSettings lastSettings;
    private Set<Integer> lastAllowedRecipeIds = Collections.emptySet();
    private Map<Integer, PriceQuote> lastTp = Map.of();
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();

    public CraftingDiscoveryService() {
        this(new RecipeRepository(), new InventoryRepository(), new TpPriceRepository(), new ItemRepository());
    }

    /** Request-local calculation state backed by the application's shared immutable graph snapshot. */
    public CraftingDiscoveryService(CraftingGraphCache graphCache) {
        this(new RecipeRepository(), new InventoryRepository(), new TpPriceRepository(),
                new ItemRepository(), graphCache, new CraftingPlanner());
    }

    public CraftingDiscoveryService(RecipeRepository recipeRepo,
                                    InventoryRepository invRepo,
                                    TpPriceRepository tpRepo,
                                    ItemRepository itemRepo) {
        this(recipeRepo, invRepo, tpRepo, itemRepo, new CraftingGraphCache(recipeRepo), new CraftingPlanner());
    }

    /** Full seam used by application-layer tests to substitute fake/in-memory adapters (TARGET_ARCHITECTURE.md §25). */
    public CraftingDiscoveryService(RecipeRepository recipeRepo,
                                    InventoryRepository invRepo,
                                    TpPriceRepository tpRepo,
                                    ItemRepository itemRepo,
                                    CraftingGraphCache graphCache,
                                    CraftingPlanner planner) {
        this.recipeRepo = recipeRepo;
        this.invRepo = invRepo;
        this.tpRepo = tpRepo;
        this.itemRepo = itemRepo;
        this.graphCache = graphCache;
        this.planner = planner;
    }

    /** Data loaded and computed for one reload() invocation, handed back to the presentation layer. */
    public record DiscoveryData(
            List<Recipe> visibleRecipes,
            List<Recipe> allRecipes,
            Map<Integer, CraftResult> resultsByRecipeId,
            Map<Integer, ItemRepository.ItemInfo> items,
            Map<Integer, PriceQuote> tp) {
    }

    /**
     * Loads DISCOVERABLE recipes still missing for {@code choice}'s char+discipline
     * (DOMAIN_SPEC.md section 34), using that same character's owned inventory
     * (binding-aware, DOMAIN_SPEC.md section 11.1 / DQ-007).
     */
    public DiscoveryData reload(DiscChoice choice, CraftingSettings settings) throws SQLException {
        requireCharacterScope(choice);

        CraftingSettings oneCraftSettings = withoutBudget(settings);

        Candidates candidates = loadCandidates(choice);

        if (!candidates.missingRecipesFound()) {
            this.lastAllowedRecipeIds = Collections.emptySet();
            return new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }

        this.lastAllowedRecipeIds = candidates.allowedRecipeIds();

        CalculationInputs inputs =
                loadCalculationInputs(oneCraftSettings, characterName(choice), candidates.allRecipes());

        Map<Integer, CraftResult> results = planner.evaluateAllSingleCraft(
                candidates.allRecipes(), inputs.sellableInventory(), inputs.boundInventory(),
                inputs.tp(), oneCraftSettings, candidates.allowedRecipeIds());

        this.lastAllRecipes = candidates.allRecipes();
        this.lastSettings = oneCraftSettings;
        this.lastTp = inputs.tp();
        this.lastItems = inputs.items();
        this.lastResultsByRecipeId = results;

        return new DiscoveryData(candidates.visibleRecipes(), candidates.allRecipes(),
                results, inputs.items(), inputs.tp());
    }

    /**
     * Freshly calculated detail for one selected recipe (TARGET_ARCHITECTURE.md section 13.1/13.2):
     * loads this operation's own inputs, checks {@code recipeId} against the candidate set those
     * inputs just produced, and derives both the row summary and the semantic explanation from
     * them. This is a fresh calculation, not retrieval of an earlier reload()'s result.
     *
     * <p>The character in {@code choice} is also the sole inventory source for the calculation.
     *
     * <p>Nothing here reads or writes the {@code last*} fields above, so an operation that finds
     * nothing discoverable reports exactly that instead of an earlier operation's row, metadata or
     * trace, and successive, concurrent or failing invocations cannot contaminate one another.
     * The explanation starts from the same captured initial inventory the row's simulation started
     * from, never from what that simulation had left, and no price, inventory, item or graph read
     * happens while it is built.
     */
    public CraftingResolutionDetail resolveDetail(int recipeId,
                                                  DiscChoice choice,
                                                  CraftingSettings settings) throws SQLException {
        requireCharacterScope(choice);

        CraftingSettings oneCraftSettings = withoutBudget(settings);

        Candidates candidates = loadCandidates(choice);

        Recipe selected = candidates.selected(recipeId);
        if (selected == null) {
            return CraftingResolutionDetail.recipeNotInCalculation(recipeId);
        }

        CalculationInputs inputs =
                loadCalculationInputs(oneCraftSettings, characterName(choice), candidates.allRecipes());

        CraftResult row = planner.evaluateSingleCraft(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.boundInventory(), inputs.tp(), oneCraftSettings, candidates.allowedRecipeIds());

        SingleCraftExplanation explanation = new SingleCraftExplainer().explainIndividual(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.boundInventory(), inputs.tp(), oneCraftSettings, candidates.allowedRecipeIds());

        return CraftingResolutionDetail.of(
                recipeId, selected, row, explanation, inputs.items(), inputs.tp());
    }

    private String characterName(DiscChoice choice) {
        return choice.charName;
    }

    private void requireCharacterScope(DiscChoice choice) {
        if (choice == null || choice.kind != DiscChoice.Kind.CHAR_DISCIPLINE
                || choice.charName == null || choice.charName.isBlank()
                || choice.discipline == null || choice.discipline.isBlank()) {
            throw new IllegalArgumentException("Crafting Discovery requires one character and discipline");
        }
    }

    private CraftingSettings withoutBudget(CraftingSettings settings) {
        return new CraftingSettings(settings.useOwnMats, settings.allowBuying, 0,
                settings.listingSell, settings.listingBuy, settings.allowDailyCrafts);
    }

    /**
     * The recipes this operation may show and resolve through. Request-local: an instance belongs
     * to the one reload()/resolveDetail() call that loaded it.
     *
     * @param missingRecipesFound whether the character has any missing discoverable recipe at all,
     *                            which reload(...) short-circuits on before loading anything else.
     *                            An empty {@code visibleRecipes} with this set is the different
     *                            case where every missing recipe was filtered out by the rating
     *                            ceiling.
     */
    private record Candidates(boolean missingRecipesFound,
                              List<Recipe> visibleRecipes,
                              List<Recipe> allRecipes,
                              Set<Integer> allowedRecipeIds) {

        static Candidates noMissingRecipes() {
            return new Candidates(false, List.of(), List.of(), Set.of());
        }

        /** The recipe this operation would produce a row for, or null when it would produce none. */
        Recipe selected(int recipeId) {
            if (!allowedRecipeIds.contains(recipeId)) return null;
            for (Recipe r : allRecipes) {
                if (r.recipeId == recipeId) return r;
            }
            return null;
        }
    }

    /** The inventory pools, quotes and item metadata one operation captured. */
    private record CalculationInputs(Map<Integer, Integer> sellableInventory,
                                     Map<Integer, Integer> boundInventory,
                                     Map<Integer, PriceQuote> tp,
                                     Map<Integer, ItemRepository.ItemInfo> items) {
    }

    private Candidates loadCandidates(DiscChoice choice) throws SQLException {
        String charName = (choice == null) ? null : choice.charName;
        String discipline = (choice == null) ? "All" : choice.discipline;
        int maxLevel = (choice != null) ? choice.rating : Integer.MAX_VALUE;

        CraftingGraph graph;
        try {
            graph = graphCache.load();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load crafting graph cache", e);
        }

        List<Recipe> allRecipes = graph.getRecipes();

        List<Integer> missingIds = recipeRepo.loadMissingDiscoverableRecipeIdsForCharacter(charName, discipline);

        if (missingIds.isEmpty()) {
            return Candidates.noMissingRecipes();
        }

        Set<Integer> missingSet = new HashSet<>(missingIds);

        List<Recipe> visibleRecipes = allRecipes.stream()
                .filter(r -> missingSet.contains(r.recipeId))
                .collect(Collectors.toList());

        if (choice != null) {
            visibleRecipes = visibleRecipes.stream()
                    .filter(r -> r.minRating <= maxLevel)
                    .toList();
        }

        Set<Integer> allowedRecipeIds = visibleRecipes.stream()
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());

        return new Candidates(true, visibleRecipes, allRecipes, allowedRecipeIds);
    }

    private CalculationInputs loadCalculationInputs(CraftingSettings settings,
                                                    String selectedCharacterName,
                                                    List<Recipe> allRecipes) throws SQLException {
        Map<Integer, Integer> sellableInv = Map.of();
        Map<Integer, Integer> boundInv = Map.of();

        if (settings.useOwnMats) {
            Map<Integer, InventoryRepository.OwnedQuantity> owned =
                    invRepo.loadOwnedInventoryForCharacter(selectedCharacterName);

            Map<Integer, Integer> sellable = new HashMap<>();
            Map<Integer, Integer> bound = new HashMap<>();
            for (var e : owned.entrySet()) {
                if (e.getValue().sellableQty() > 0) sellable.put(e.getKey(), e.getValue().sellableQty());
                if (e.getValue().boundQty() > 0) bound.put(e.getKey(), e.getValue().boundQty());
            }
            sellableInv = sellable;
            boundInv = bound;
        }

        Set<Integer> itemIds = new HashSet<>();
        for (Recipe r : allRecipes) {
            itemIds.add(r.outputItemId);
            for (Ingredient ing : r.ingredients) {
                itemIds.add(ing.itemId);
            }
        }

        Map<Integer, PriceQuote> tp = tpRepo.loadTpQuotes(itemIds);
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(itemIds);

        return new CalculationInputs(sellableInv, boundInv, tp, items);
    }

    /**
     * Lazy per-recipe detail lookup (view calls this on row selection): returns the cached
     * result for {@code recipeId}, building and caching its resolution tree on first request.
     * Returns {@code null} if {@code recipeId} is not part of the most recent reload().
     */
    public CraftResult getResultByRecipeId(int recipeId) {
        CraftResult cr = lastResultsByRecipeId.get(recipeId);
        if (cr == null) return null;

        if (cr.tree != null) {
            return cr;
        }

        Node lazyTree = buildTreeForRecipeId(recipeId);

        CraftResult enriched = new CraftResult(
                cr.outputItemId,
                cr.discipline,
                cr.craftableCount,
                cr.missingToBuy,
                cr.missingToBuyOne,
                cr.buyCostCopper,
                cr.matsSellValueCopper,
                cr.revenueCopper,
                cr.profitCopper,
                cr.totalProfitCopper,
                cr.totalSellValueCopper,
                lazyTree,
                cr.blockedReason
        );

        Map<Integer, CraftResult> copy = new HashMap<>(lastResultsByRecipeId);
        copy.put(recipeId, enriched);
        lastResultsByRecipeId = copy;

        return enriched;
    }

    /** View calls this for labels in the tree/shopping list. */
    public String itemName(int itemId) {
        ItemRepository.ItemInfo it = lastItems.get(itemId);
        if (it != null && it.name != null && !it.name.isBlank()) return it.name;
        return "Item " + itemId;
    }

    public int itemSellUnit(int itemId, boolean listingSell) {
        PriceQuote q = lastTp.get(itemId);
        if (q == null) return 0;
        Integer v = listingSell ? q.sellUnit : q.buyUnit;
        return (v == null) ? 0 : v;
    }

    private Node buildTreeForRecipeId(int recipeId) {
        if (lastAllRecipes == null || lastAllRecipes.isEmpty() || lastSettings == null) {
            return null;
        }

        Recipe target = null;
        for (Recipe r : lastAllRecipes) {
            if (r.recipeId == recipeId) {
                target = r;
                break;
            }
        }

        if (target == null) return null;

        Map<Integer, List<Recipe>> recipesByOutput = new HashMap<>();
        for (Recipe r : lastAllRecipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }

        PlannerContext ctx = new PlannerContext(
                recipesByOutput,
                lastTp,
                lastSettings,
                lastAllowedRecipeIds
        );

        RecipeTreeBuilder treeBuilder = new RecipeTreeBuilder();
        return treeBuilder.buildTree(target, ctx);
    }
}
