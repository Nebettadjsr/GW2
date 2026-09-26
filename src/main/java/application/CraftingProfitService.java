package application;

import craft.CharacterCraftingProfile;
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
import repo.CharacterRepository;
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
 * Application-layer use case for the Crafting Profit flow (TARGET_ARCHITECTURE.md §8): loads the
 * recipe/graph/roster/inventory/price/item data the documented flow needs and invokes the
 * coordinated planner and (lazily) the recipe-tree builder. Holds no JavaFX dependency and
 * reimplements no domain calculation; it only coordinates repositories and {@code craft.*} domain
 * calls on their behalf (STORY-APP-001).
 */
public class CraftingProfitService {

    private final RecipeRepository recipeRepo;
    private final InventoryRepository invRepo;
    private final TpPriceRepository tpRepo;
    private final ItemRepository itemRepo;
    private final CharacterRepository charRepo;
    private final CraftingGraphCache graphCache;
    private final CraftingPlanner planner;

    // Scoped to the most recent reload(); backs the lazy per-recipe tree lookup below so a stale
    // cache from an earlier reload() is never consulted (STORY-APP-001 acceptance criterion 3).
    private List<Recipe> lastAllRecipes = List.of();
    private CraftingSettings lastSettings;
    private Set<Integer> lastAllowedRecipeIds = Collections.emptySet();
    private Map<Integer, PriceQuote> lastTp = Map.of();
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();

    public CraftingProfitService() {
        this(new RecipeRepository(), new InventoryRepository(), new TpPriceRepository(),
                new ItemRepository(), new CharacterRepository());
    }

    public CraftingProfitService(RecipeRepository recipeRepo,
                                 InventoryRepository invRepo,
                                 TpPriceRepository tpRepo,
                                 ItemRepository itemRepo,
                                 CharacterRepository charRepo) {
        this(recipeRepo, invRepo, tpRepo, itemRepo, charRepo,
                new CraftingGraphCache(recipeRepo), new CraftingPlanner());
    }

    /** Full seam used by application-layer tests to substitute fake/in-memory adapters (TARGET_ARCHITECTURE.md §25). */
    public CraftingProfitService(RecipeRepository recipeRepo,
                                 InventoryRepository invRepo,
                                 TpPriceRepository tpRepo,
                                 ItemRepository itemRepo,
                                 CharacterRepository charRepo,
                                 CraftingGraphCache graphCache,
                                 CraftingPlanner planner) {
        this.recipeRepo = recipeRepo;
        this.invRepo = invRepo;
        this.tpRepo = tpRepo;
        this.itemRepo = itemRepo;
        this.charRepo = charRepo;
        this.graphCache = graphCache;
        this.planner = planner;
    }

    /** Data loaded and computed for one reload() invocation, handed back to the presentation layer. */
    public record ProfitData(
            List<Recipe> visibleRecipes,
            List<Recipe> allRecipes,
            Map<Integer, CraftResult> resultsByRecipeId,
            Map<Integer, ItemRepository.ItemInfo> items,
            Map<Integer, PriceQuote> tp) {
    }

    public ProfitData reload(DiscChoice choice, CraftingSettings settings) throws SQLException {

        Candidates candidates = loadCandidates(choice);
        CalculationInputs inputs = loadCalculationInputs(choice, settings, candidates.allRecipes());

        Map<Integer, CraftResult> resultsByRecipeId = planner.evaluateAllCoordinated(
                candidates.allRecipes(), inputs.sellableInventory(), inputs.accountBoundInventory(),
                inputs.characterBoundInventory(), inputs.roster(), inputs.tp(), settings,
                candidates.allowedRecipeIds());

        this.lastAllRecipes = candidates.allRecipes();
        this.lastSettings = settings;
        this.lastAllowedRecipeIds = candidates.allowedRecipeIds();
        this.lastTp = inputs.tp();
        this.lastResultsByRecipeId = resultsByRecipeId;

        return new ProfitData(candidates.visibleRecipes(), candidates.allRecipes(),
                resultsByRecipeId, inputs.items(), inputs.tp());
    }

    /**
     * Freshly calculated detail for one selected recipe (TARGET_ARCHITECTURE.md section 13.1/13.2):
     * loads this operation's own inputs, checks {@code recipeId} against the candidate set those
     * inputs just produced, and derives both the row summary and the semantic explanation from
     * them. This is a fresh calculation, not retrieval of an earlier reload()'s result.
     *
     * <p>Nothing here reads or writes the {@code last*} fields above, and every mutable planning
     * state belongs to one {@link CraftingPlanner}/{@link SingleCraftExplainer} call, so
     * successive or concurrent invocations with different scopes and settings cannot contaminate
     * one another and a failure leaves nothing behind. The explanation starts from the same
     * captured initial inventory the row's simulation started from, never from what that
     * simulation had left, and no price, inventory, item or graph read happens while it is built.
     */
    public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice, CraftingSettings settings)
            throws SQLException {

        Candidates candidates = loadCandidates(choice);

        Recipe selected = candidates.selected(recipeId);
        if (selected == null) {
            return CraftingResolutionDetail.recipeNotInCalculation(recipeId);
        }

        CalculationInputs inputs = loadCalculationInputs(choice, settings, candidates.allRecipes());

        CraftResult row = planner.evaluateOneCoordinated(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.accountBoundInventory(), inputs.characterBoundInventory(), inputs.roster(),
                inputs.tp(), settings, candidates.allowedRecipeIds());

        SingleCraftExplanation explanation = new SingleCraftExplainer().explainCoordinated(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.accountBoundInventory(), inputs.characterBoundInventory(), inputs.roster(),
                inputs.tp(), settings, candidates.allowedRecipeIds());

        return CraftingResolutionDetail.of(
                recipeId, selected, row, explanation, inputs.items(), inputs.tp());
    }

    /**
     * The recipes this operation may show and resolve through. Request-local: an instance belongs
     * to the one reload()/resolveDetail() call that loaded it.
     */
    private record Candidates(List<Recipe> visibleRecipes,
                              List<Recipe> allRecipes,
                              Set<Integer> allowedRecipeIds) {

        /**
         * The graph recipe this operation would produce a row for, or null when it would produce
         * none - either because the recipe is outside the visible set, or because the visible set
         * named a recipe the crafting graph does not contain, for which the table has no row
         * either.
         */
        Recipe selected(int recipeId) {
            if (!allowedRecipeIds.contains(recipeId)) return null;
            for (Recipe r : allRecipes) {
                if (r.recipeId == recipeId) return r;
            }
            return null;
        }
    }

    /** The roster, inventory pools, quotes and item metadata one operation captured. */
    private record CalculationInputs(List<CharacterCraftingProfile> roster,
                                     Map<Integer, Integer> sellableInventory,
                                     Map<Integer, Integer> accountBoundInventory,
                                     Map<String, Map<Integer, Integer>> characterBoundInventory,
                                     Map<Integer, PriceQuote> tp,
                                     Map<Integer, ItemRepository.ItemInfo> items) {
    }

    private Candidates loadCandidates(DiscChoice choice) throws SQLException {
        List<Recipe> visibleRecipes;
        if (choice == null || choice.kind == DiscChoice.Kind.ALL) {
            visibleRecipes = recipeRepo.loadRecipes("All");
        } else if (choice.kind == DiscChoice.Kind.DISCIPLINE_ONLY) {
            visibleRecipes = recipeRepo.loadRecipes(choice.discipline);
        } else { // CHAR_DISCIPLINE
            visibleRecipes = recipeRepo.loadRecipesForCharacter(choice.charName, choice.discipline);
        }

        Set<Integer> allowedRecipeIds = visibleRecipes.stream()
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());

        CraftingGraph graph;
        try {
            graph = graphCache.load();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load crafting graph cache", e);
        }

        return new Candidates(visibleRecipes, graph.getRecipes(), allowedRecipeIds);
    }

    private CalculationInputs loadCalculationInputs(DiscChoice choice,
                                                    CraftingSettings settings,
                                                    List<Recipe> allRecipes) throws SQLException {
        Set<Integer> itemIds = new HashSet<>();
        for (Recipe r : allRecipes) {
            itemIds.add(r.outputItemId);
            for (Ingredient ing : r.ingredients) {
                itemIds.add(ing.itemId);
            }
        }

        Map<Integer, PriceQuote> tp = tpRepo.loadTpQuotes(itemIds);
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(itemIds);

        // Every scope uses the same per-step eligibility and ownership checks.
        List<CharacterCraftingProfile> roster = buildCoordinatedRoster(choice);
        Map<Integer, Integer> sellableInv = Map.of();
        Map<Integer, Integer> accountBoundInv = Map.of();
        Map<String, Map<Integer, Integer>> characterBoundInv = Map.of();
        if (settings.useOwnMats && !roster.isEmpty()) {
            Set<String> names = roster.stream().map(CharacterCraftingProfile::name).collect(Collectors.toSet());
            InventoryRepository.CoordinatedInventory inv = invRepo.loadOwnedInventoryForCharacters(names);
            sellableInv = inv.sellable();
            accountBoundInv = inv.accountBound();
            characterBoundInv = inv.characterBound();
        }

        return new CalculationInputs(roster, sellableInv, accountBoundInv, characterBoundInv, tp, items);
    }

    /**
     * Candidate characters for coordinated planning (DOMAIN_SPEC.md section 2.2.1): every synced
     * character's discipline ratings, restricted to characters who hold the chosen discipline
     * when a discipline is selected, and to the named character for a specific entry.
     * Ratings come from synced data, not the selector's display snapshot.
     */
    private List<CharacterCraftingProfile> buildCoordinatedRoster(DiscChoice choice) throws SQLException {
        Map<String, Map<String, Integer>> ratingByCharacter = new HashMap<>();
        for (CharacterRepository.DiscRow row : charRepo.loadAllCharacterCrafting()) {
            ratingByCharacter.computeIfAbsent(row.charName, k -> new HashMap<>()).put(row.discipline, row.rating);
        }

        String requiredDiscipline = (choice != null && choice.kind != DiscChoice.Kind.ALL)
                ? choice.discipline
                : null;

        List<CharacterCraftingProfile> roster = new ArrayList<>();
        for (var e : ratingByCharacter.entrySet()) {
            if (choice != null && choice.kind == DiscChoice.Kind.CHAR_DISCIPLINE
                    && !e.getKey().equals(choice.charName)) continue;
            if (requiredDiscipline == null || e.getValue().containsKey(requiredDiscipline)) {
                Map<String, Integer> ratings = requiredDiscipline == null
                        ? e.getValue()
                        : Map.of(requiredDiscipline, e.getValue().get(requiredDiscipline));
                roster.add(new CharacterCraftingProfile(e.getKey(), ratings));
            }
        }
        return roster;
    }

    /**
     * Lazy per-recipe detail lookup (view calls this on row selection): returns the cached
     * coordinated result for {@code recipeId}, building and caching its resolution tree on first
     * request. Returns {@code null} if {@code recipeId} is not part of the most recent reload().
     */
    /**
     * The most recent reload()'s result for {@code recipeId} exactly as the planner produced it,
     * without building its resolution tree. For callers that only read the computed numbers (such
     * as the reload-summary counters logged to the console), since
     * {@link #getResultByRecipeId(int)}'s lazy tree build is expensive enough that doing it once
     * per visible recipe dominated page-load time (STORY-PERF-001). Returns {@code null} if
     * {@code recipeId} is not part of the most recent reload().
     */
    public CraftResult getRawResultByRecipeId(int recipeId) {
        return lastResultsByRecipeId.get(recipeId);
    }

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
