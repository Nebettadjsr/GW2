package application;

import craft.CharacterCraftingProfile;
import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import parser.MysticForgeCatalogLoader;
import craft.Node;
import craft.PlannerContext;
import craft.PriceQuote;
import craft.Recipe;
import craft.RecipeTreeBuilder;
import craft.SingleCraftExplainer;
import craft.SingleCraftExplanation;
import model.AccountScope;
import repo.CharacterRepository;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.Connection;
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
 * Application-layer use case for the Crafting Profit flow: loads the
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
    private final repo.WalletRepository walletRepo = new repo.WalletRepository();

    /**
     * The captured read view this service's operations load their inputs through.
     * When it carries a connection, every input read below runs on
     * it, so one operation cannot mix input generations or pair part-written refresh data with an
     * older completion marker. A {@link ProfitInputSession#detached(AccountScope)} session carries a scope but no
     * connection: the in-memory seam tests use to substitute scoped repository reads. Every connection-carrying
     * session is bound to one verified account, so pinned reads are always account-scoped.
     */
    private final ProfitInputSession session;

    // Scoped to the most recent reload(); backs the lazy per-recipe tree lookup below so a stale
    // cache from an earlier reload() is never consulted (STORY-APP-001 acceptance criterion 3).
    private List<Recipe> lastAllRecipes = List.of();
    private CraftingSettings lastSettings;
    private Set<Integer> lastAllowedRecipeIds = Collections.emptySet();
    private Map<Integer, PriceQuote> lastTp = Map.of();
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();

    /**
     * Production wiring: request-local calculation state backed by the shared immutable graph
     * snapshot, reading every input through one captured account-scoped snapshot session.
     */
    public CraftingProfitService(CraftingGraphCache graphCache, ProfitInputSession session) {
        this(new RecipeRepository(), new InventoryRepository(), new TpPriceRepository(),
                new ItemRepository(), new CharacterRepository(), graphCache, new CraftingPlanner(),
                session);
    }

    /** The same seam, with the captured snapshot session the inputs are read through. */
    public CraftingProfitService(RecipeRepository recipeRepo,
                                 InventoryRepository invRepo,
                                 TpPriceRepository tpRepo,
                                 ItemRepository itemRepo,
                                 CharacterRepository charRepo,
                                 CraftingGraphCache graphCache,
                                 CraftingPlanner planner,
                                 ProfitInputSession session) {
        this.recipeRepo = recipeRepo;
        this.invRepo = invRepo;
        this.tpRepo = tpRepo;
        this.itemRepo = itemRepo;
        this.charRepo = charRepo;
        this.graphCache = graphCache;
        this.planner = planner;
        this.session = java.util.Objects.requireNonNull(session, "session");
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
        settings = CraftingSettings.ordinaryOnly(settings);
        Candidates candidates = loadCandidates(choice, settings);
        CalculationInputs inputs = loadCalculationInputs(choice, settings, candidates.allRecipes());

        Map<Integer, CraftResult> resultsByRecipeId = planner.withVendorValues(ItemRepository.vendorValues(inputs.items())).withWallet(inputs.wallet()).evaluateAllCoordinated(
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
     * Freshly calculated detail for one selected recipe:
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
        settings = CraftingSettings.ordinaryOnly(settings);
        Candidates candidates = loadCandidates(choice, settings);

        Recipe selected = candidates.selected(recipeId);
        if (selected == null) {
            return CraftingResolutionDetail.recipeNotInCalculation(recipeId);
        }

        CalculationInputs inputs = loadCalculationInputs(choice, settings, candidates.allRecipes());

        CraftResult row = planner.withVendorValues(ItemRepository.vendorValues(inputs.items())).withWallet(inputs.wallet()).evaluateOneCoordinated(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.accountBoundInventory(), inputs.characterBoundInventory(), inputs.roster(),
                inputs.tp(), settings, candidates.allowedRecipeIds());

        SingleCraftExplanation explanation = new SingleCraftExplainer(ItemRepository.vendorValues(inputs.items())).withWallet(inputs.wallet()).explainCoordinatedResult(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.accountBoundInventory(), inputs.characterBoundInventory(), inputs.roster(),
                inputs.tp(), settings, candidates.allowedRecipeIds(), row.craftableCount);

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

    /** One selected Forge project over this session's snapshot: the plan plus the names/quotes it needs. */
    public record ForgeProjectData(craft.ForgeProjectPlanner.Project project, boolean referenceOnly, boolean batchTarget,
                                   long requestedTarget, Map<Integer, ItemRepository.ItemInfo> items,
                                   Map<Integer, PriceQuote> tp, boolean walletKnown) {
    }

    /**
     * Exact planning of ONE Mystic Forge target (no profit simulation, no budget, no per-root loop). Shared stock is the
     * account Materials/Bank pool plus account-bound and Bank-resident soulbound stock (Forge Bank exception); carried
     * character-bound stock is not pooled across owners. Returns null when {@code recipeId} is not a known Forge recipe.
     */
    /**
     * The explicit quote ids this project's public path needs (null when the recipe is unknown): structure only, so
     * no calculation or price is needed to discover them.
     */
    public Set<Integer> forgeProjectItemIds(int recipeId) throws SQLException {
        craft.ForgeReferenceRecipes.Reference ref = craft.ForgeReferenceRecipes.byId(recipeId);
        Recipe root = ref != null ? ref.asPlanningTarget()
                : MysticForgeCatalogLoader.bundled().recipes().stream().filter(r -> r.recipeId == recipeId).findFirst().orElse(null);
        if (root == null) return null;
        return new craft.ForgeProjectPlanner(forgeProducers()).pathItemIds(root);
    }

    private List<Recipe> forgeProducers() throws SQLException {
        List<Recipe> producers = new ArrayList<>();
        try {
            producers.addAll(graphCache.loadForSnapshot(session.connection()).getRecipes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load crafting graph cache", e);
        }
        producers.addAll(MysticForgeCatalogLoader.bundled().recipes());
        producers.addAll(craft.VendorAcquisitions.recipes());
        return producers;
    }

    public ForgeProjectData planForgeProject(int recipeId, long target) throws SQLException {
        craft.ForgeReferenceRecipes.Reference ref = craft.ForgeReferenceRecipes.byId(recipeId);
        Recipe root = ref != null ? ref.asPlanningTarget()
                : MysticForgeCatalogLoader.bundled().recipes().stream().filter(r -> r.recipeId == recipeId).findFirst().orElse(null);
        if (root == null) return null;
        Connection con = session.connection();
        List<Recipe> producers = forgeProducers();
        // Clover references are never producers: they are deliberately not in this list.
        craft.ForgeProjectPlanner planner = new craft.ForgeProjectPlanner(producers);
        // Quotes for exactly this project's public path (the same ids its source identity/admission cover).
        Map<Integer, PriceQuote> tp = tpRepo.loadCurrentTradeableQuotes(con, planner.pathItemIds(root));

        // Binding pools stay separate (sellable / account-bound / per-owner carried and Bank namespaces): the planner
        // applies the shared PlanState rules, so a Forge step may use any owner's Bank stock and an ordinary step only
        // its assigned eligible character's own stock. Nothing is collapsed into one shared map here.
        InventoryRepository.CoordinatedInventory inv = invRepo.loadOwnedInventoryForCharacters(con, session.scope(), Set.of(), true);
        craft.ForgeProjectPlanner.Inventory inventory = new craft.ForgeProjectPlanner.Inventory(
                inv.sellable(), inv.accountBound(), inv.characterBound());
        craft.ForgeProjectPlanner.Context context = new craft.ForgeProjectPlanner.Context(
                buildCoordinatedRoster(null), recipeRepo.loadKnownRecipeIds(con, session.scope()));

        Map<Integer, Long> wallet = null;
        if (session.walletAuthorized()) {
            Map<Integer, Integer> w = walletRepo.load(con, session.scope(), session.walletEvaluatedAt(), session.walletMaxAge()).orElse(null);
            if (w != null) { wallet = new HashMap<>(); for (var e : w.entrySet()) wallet.put(e.getKey(), (long) e.getValue()); }
        }

        boolean batchTarget = ref != null || root.hasExpectedYield();
        craft.ForgeProjectPlanner.Project project = batchTarget
                ? planner.plan(root, target, true, inventory, context, wallet, tp)
                : planner.planOutput(root, target, inventory, context, wallet, tp);
        Set<Integer> shown = new HashSet<>();
        shown.add(root.outputItemId);
        for (Ingredient i : root.ingredients) shown.add(i.itemId);
        project.items().forEach(l -> shown.add(l.itemId()));
        project.steps().forEach(st -> shown.add(st.outputItemId()));
        if (project.notes() != null) {
            for (var v : project.notes().estimate().variants()) {
                shown.add(v.outputItemId());
                v.ingredients().forEach(l -> shown.add(l.itemId()));
            }
        }
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(con, shown);
        return new ForgeProjectData(project, ref != null, batchTarget, target, items, tp, wallet != null);
    }

    /**
     * The roster, inventory pools, quotes and item metadata captured for one operation. Both the
     * table calculation and one row's fresh detail use one captured set of inputs.
     */
    private record CalculationInputs(List<CharacterCraftingProfile> roster,
                                     Map<Integer, Integer> sellableInventory,
                                     Map<Integer, Integer> accountBoundInventory,
                                     Map<String, Map<Integer, Integer>> characterBoundInventory,
                                     Map<Integer, PriceQuote> tp,
                                     Map<Integer, ItemRepository.ItemInfo> items,
                                     Map<Integer, Integer> wallet) {
    }

    private Candidates loadCandidates(DiscChoice choice, CraftingSettings settings) throws SQLException {
        Connection con = session.connection();
        AccountScope scope = session.scope();
        List<Recipe> visibleRecipes;
        if (choice == null || choice.kind == DiscChoice.Kind.ALL) {
            visibleRecipes = recipeRepo.loadRecipes(con, scope, "All");
        } else if (choice.kind == DiscChoice.Kind.DISCIPLINE_ONLY) {
            visibleRecipes = recipeRepo.loadRecipes(con, scope, choice.discipline);
        } else { // CHAR_DISCIPLINE
            visibleRecipes = recipeRepo.loadRecipesForCharacter(con, scope, choice.charName, choice.discipline);
        }

        Set<Integer> allowedRecipeIds = visibleRecipes.stream()
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());

        CraftingGraph graph;
        try {
            graph = graphCache.loadForSnapshot(con);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load crafting graph cache", e);
        }

        List<Recipe> allRecipes = graph.getRecipes();
        if (settings != null && settings.allowMysticForge) {
            // Forge recipes are a separate, setting-gated source: negative ids never collide with ordinary
            // ids or account unlocks, and the toggle-off path above is untouched.
            List<Recipe> forge = MysticForgeCatalogLoader.bundled().recipes();
            if (!forge.isEmpty()) {
                visibleRecipes = concat(visibleRecipes, forge);
                // Vendor purchases of Forge inputs resolve nested needs only: not visible roots, never unlocks.
                allRecipes = concat(concat(allRecipes, forge), craft.VendorAcquisitions.recipes());
                Set<Integer> withForge = new HashSet<>(allowedRecipeIds);
                forge.forEach(r -> withForge.add(r.recipeId));
                allowedRecipeIds = withForge;
            }
        }
        return new Candidates(visibleRecipes, allRecipes, allowedRecipeIds);
    }

    private static List<Recipe> concat(List<Recipe> a, List<Recipe> b) {
        List<Recipe> out = new java.util.ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
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

        Connection con = session.connection();
        Map<Integer, PriceQuote> tp = tpRepo.loadTpQuotes(con, itemIds);
        Map<Integer, ItemRepository.ItemInfo> items = itemRepo.loadItems(con, itemIds);

        // Every scope uses the same per-step eligibility and ownership checks.
        List<CharacterCraftingProfile> roster = buildCoordinatedRoster(choice);
        Map<Integer, Integer> sellableInv = Map.of();
        Map<Integer, Integer> accountBoundInv = Map.of();
        Map<String, Map<Integer, Integer>> characterBoundInv = Map.of();
        // Enabled Forge needs no crafting character: shared Bank/Materials stock stays available with an empty roster.
        if (settings.useOwnMats && (!roster.isEmpty() || settings.allowMysticForge)) {
            Set<String> names = roster.stream().map(CharacterCraftingProfile::name).collect(Collectors.toSet());
            InventoryRepository.CoordinatedInventory inv = loadOwnedInventory(con, names, settings);
            sellableInv = inv.sellable();
            accountBoundInv = inv.accountBound();
            characterBoundInv = inv.characterBound();
        }

        // Wallet facts are optional and authority-gated: no grant/proof, no publication or a failed read all
        // leave null = unknown (never zero, never unlimited). Item-only recipes never look at it.
        Map<Integer, Integer> wallet = null;
        if (session.walletAuthorized() && allRecipes.stream().anyMatch(r -> !r.currencies.isEmpty())) {
            wallet = walletRepo.load(con, session.scope(), session.walletEvaluatedAt(), session.walletMaxAge()).orElse(null);
        }

        return new CalculationInputs(roster, sellableInv, accountBoundInv, characterBoundInv, tp,
                items, wallet);
    }

    private InventoryRepository.CoordinatedInventory loadOwnedInventory(Connection con, Set<String> names,
                                                                        CraftingSettings settings) throws SQLException {
        AccountScope scope = session.scope();
        if (settings.materialProtectionEnabled
                && (!settings.protectedBankItemIds.isEmpty() || !settings.protectedMaterialItemIds.isEmpty()
                    || settings.hasFiniteReservations())) {
            // Forge keeps every account character's carried stock under its actual owner; off keeps the legacy overloads.
            if (settings.allowMysticForge) {
                return invRepo.loadOwnedInventoryForCharacters(con, scope, names, settings.protectedBankItemIds, settings.protectedMaterialItemIds,
                        settings.protectedBankQuantities, settings.protectedMaterialQuantities, settings.protectedBankAllocations, true);
            }
            return invRepo.loadOwnedInventoryForCharacters(con, scope, names, settings.protectedBankItemIds, settings.protectedMaterialItemIds,
                    settings.protectedBankQuantities, settings.protectedMaterialQuantities, settings.protectedBankAllocations);
        }
        if (settings.allowMysticForge) return invRepo.loadOwnedInventoryForCharacters(con, scope, names, true);
        return invRepo.loadOwnedInventoryForCharacters(con, scope, names);
    }

    /**
     * Candidate characters for coordinated planning (DOMAIN_SPEC.md section 2.2.1): every synced
     * character's discipline ratings, restricted to characters who hold the chosen discipline
     * when a discipline is selected, and to the named character for a specific entry.
     * Ratings come from synced data, not the selector's display snapshot.
     */
    private List<CharacterCraftingProfile> buildCoordinatedRoster(DiscChoice choice) throws SQLException {
        Connection con = session.connection();
        Map<String, Map<String, Integer>> ratingByCharacter = new HashMap<>();
        AccountScope scope = session.scope();
        List<CharacterRepository.DiscRow> craftingRows = charRepo.loadAllCharacterCrafting(con, scope);
        for (CharacterRepository.DiscRow row : craftingRows) {
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

    /**
     * Lazy per-recipe detail lookup (view calls this on row selection): returns the cached
     * coordinated result for {@code recipeId}, building and caching its resolution tree on first
     * request. Returns {@code null} if {@code recipeId} is not part of the most recent reload().
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
                cr.blockedReason,
                cr.totalMatsSellValueCopper,
                cr.materialPurchaseCosts,
                cr.materialPurchaseCostsOne
        );
        enriched.unvaluedOwnedInput = cr.unvaluedOwnedInput;
        enriched.currencyPerCraft = cr.currencyPerCraft;
        enriched.currencyTotal = cr.currencyTotal;
        enriched.walletState = cr.walletState;
        enriched.unpricedCurrency = cr.unpricedCurrency;
        enriched.unpricedCurrencyIds = cr.unpricedCurrencyIds;
        enriched.estimatedNotesPerCraft = cr.estimatedNotesPerCraft;
        enriched.estimatedNotesTotal = cr.estimatedNotesTotal;
        enriched.estimatedNotesCostPerCraftCopper = cr.estimatedNotesCostPerCraftCopper;
        enriched.estimatedNotesCostTotalCopper = cr.estimatedNotesCostTotalCopper;
        enriched.noteEstimate = cr.noteEstimate;
        enriched.economicsFromHypotheticalCraft = cr.economicsFromHypotheticalCraft;
        enriched.profitUnknown = cr.profitUnknown;
        enriched.expectedYield = cr.expectedYield;
        enriched.estimated = cr.estimated;
        enriched.expectedSellableUnitsTotal = cr.expectedSellableUnitsTotal;
        enriched.guaranteedSellableUnitsTotal = cr.guaranteedSellableUnitsTotal;
        enriched.reusedSeedUnits = cr.reusedSeedUnits;

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
                lastAllowedRecipeIds,
                null
        );

        RecipeTreeBuilder treeBuilder = new RecipeTreeBuilder();
        return treeBuilder.buildTree(target, ctx);
    }
}
