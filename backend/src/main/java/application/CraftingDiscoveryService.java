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
 * Application-layer use case for the Crafting Discovery flow: loads
 * the missing-discoverable-recipe/graph/inventory/price/item data the documented flow needs and
 * invokes the single-character planner and (lazily) the recipe-tree builder. Holds no JavaFX
 * dependency and reimplements no domain calculation; it only coordinates repositories and
 * {@code craft.*} domain calls on their behalf (STORY-APP-002).
 */
public class CraftingDiscoveryService {

    private final TpPriceRepository tpRepo;
    private final ItemRepository itemRepo;
    private final CraftingGraphCache graphCache;
    private final CraftingPlanner planner;
    private boolean walletAuthorized;
    private final ScopedInputsBinding binding;
    /** The authorized account of this request-local instance; null only on the unbound builder. */
    private final model.AccountScope scope;
    /** Account-scoped reads of this instance; null only on the unbound builder. */
    private ScopedInputs inputs;
    private java.time.Clock clock = java.time.Clock.systemUTC();
    /** Account max age; null means published inputs are never reported stale. */
    private java.time.Duration accountMaxAge;

    // Scoped to the most recent reload(); backs getResultByRecipeId/itemName/itemSellUnit so a
    // stale cache from an earlier reload() - or one skipped by the "no missing recipes" early
    // return below - is never silently replaced with emptier data (mirrors STORY-APP-001
    // acceptance criterion 3, and preserves this controller's original "leave caches untouched
    // when there is nothing discoverable" behavior).
    private List<Recipe> lastAllRecipes = List.of();
    private CraftingSettings lastSettings;
    private Set<Integer> lastUsableRecipeIds = Collections.emptySet();
    private Map<Integer, PriceQuote> lastTp = Map.of();
    private Map<Integer, ItemRepository.ItemInfo> lastItems = Map.of();
    private Map<Integer, CraftResult> lastResultsByRecipeId = Map.of();

    /** The account-scoped reads one Discovery operation needs besides catalog, quotes and items. */
    public interface ScopedInputs {
        /** Missing discoverable recipe ids for the character/discipline plus the account's known ids. */
        MissingRecipes missingRecipes(String charName, String discipline) throws SQLException;

        /** The character's binding-aware owned inventory. */
        Map<Integer, InventoryRepository.OwnedQuantity> ownedInventory(String charName) throws SQLException;
    }

    /** Missing ids; {@code knownIds} is empty whenever nothing is missing. */
    public record MissingRecipes(List<Integer> missingIds, Set<Integer> knownIds) {
    }

    /** Creates the scoped reads of one request-local instance. */
    @FunctionalInterface
    public interface ScopedInputsBinding {
        ScopedInputs bind(model.AccountScope scope, java.time.Clock clock);
    }

    /**
     * Unbound builder backed by the shared immutable graph snapshot. It only serves
     * {@link #forScope}; every calculation runs on the bound request-local instance.
     */
    public CraftingDiscoveryService(CraftingGraphCache graphCache) {
        this(new TpPriceRepository(), new ItemRepository(), graphCache, new CraftingPlanner(),
                databaseInputs(new RecipeRepository(), new InventoryRepository()));
    }

    /** Full seam: tests substitute the quote/item adapters, graph cache, planner and scoped reads. */
    public CraftingDiscoveryService(TpPriceRepository tpRepo,
                                    ItemRepository itemRepo,
                                    CraftingGraphCache graphCache,
                                    CraftingPlanner planner,
                                    ScopedInputsBinding binding) {
        this(tpRepo, itemRepo, graphCache, planner, binding, null, null);
    }

    private CraftingDiscoveryService(TpPriceRepository tpRepo,
                                     ItemRepository itemRepo,
                                     CraftingGraphCache graphCache,
                                     CraftingPlanner planner,
                                     ScopedInputsBinding binding,
                                     model.AccountScope scope,
                                     ScopedInputs inputs) {
        this.tpRepo = tpRepo;
        this.itemRepo = itemRepo;
        this.graphCache = graphCache;
        this.planner = planner;
        this.binding = binding;
        this.scope = scope;
        this.inputs = inputs;
    }

    /**
     * A fresh request-local service bound to one authorized account (STORY-API-011). Account inputs
     * (recipe knowledge, characters, inventory) are then read only through that scope; the domain
     * calculation is unchanged and receives the already-scoped ordinary inputs.
     */
    public CraftingDiscoveryService forScope(model.AccountScope scope) {
        return forScope(scope, false);
    }

    /**
     * As {@link #forScope(model.AccountScope)}; {@code walletAuthorized} says the validated request context
     * permits {@code WALLET_READ}, so the cached wallet may decide currency affordability. Otherwise the wallet
     * stays unknown (never zero or unlimited) and item-only recipes are unaffected.
     */
    public CraftingDiscoveryService forScope(model.AccountScope scope, boolean walletAuthorized) {
        java.util.Objects.requireNonNull(scope);
        CraftingDiscoveryService bound = new CraftingDiscoveryService(tpRepo, itemRepo, graphCache, planner,
                binding, scope, binding.bind(scope, clock));
        bound.clock = clock;
        bound.accountMaxAge = accountMaxAge;
        bound.walletAuthorized = walletAuthorized;
        return bound;
    }

    private model.AccountScope requireScope() {
        if (scope == null) throw new IllegalStateException("Discovery requires forScope(account) before use");
        return scope;
    }

    private static ScopedInputsBinding databaseInputs(RecipeRepository recipes, InventoryRepository inventory) {
        return (scope, clock) -> new DatabaseScopedInputs(recipes, inventory, scope, clock);
    }

    /** Production reads: managed-schema validation, published-source and roster checks, then scoped SQL. */
    private static final class DatabaseScopedInputs implements ScopedInputs {
        private final RecipeRepository recipeRepo;
        private final InventoryRepository invRepo;
        private final model.AccountScope scope;
        private final java.time.Clock clock;

        DatabaseScopedInputs(RecipeRepository recipeRepo, InventoryRepository invRepo,
                             model.AccountScope scope, java.time.Clock clock) {
            this.recipeRepo = recipeRepo;
            this.invRepo = invRepo;
            this.scope = scope;
            this.clock = clock;
        }

        @Override
        public MissingRecipes missingRecipes(String charName, String discipline) throws SQLException {
            try (java.sql.Connection con = openScoped(charName)) {
                List<Integer> missingIds =
                        recipeRepo.loadMissingDiscoverableRecipeIdsForCharacter(con, scope, charName, discipline);
                return new MissingRecipes(missingIds,
                        missingIds.isEmpty() ? Set.of() : recipeRepo.loadKnownRecipeIds(con, scope));
            }
        }

        @Override
        public Map<Integer, InventoryRepository.OwnedQuantity> ownedInventory(String charName) throws SQLException {
            return invRepo.loadOwnedInventoryForCharacter(scope, charName);
        }

        /**
         * Opens the scoped connection, records authorized use (activity only, never freshness) and
         * refuses unless every source Discovery consumes has been published for this account.
         */
        private java.sql.Connection openScoped(String charName) throws SQLException {
            java.sql.Connection con = repo.Db.open();
            try {
                repo.ManagedSchema.requireManagedAccountSchema(con, "account-scoped Discovery");
                var states = new repo.AccountSourceStateRepository();
                for (String source : List.of(repo.AccountSyncStateRepository.BANK,
                        repo.AccountSyncStateRepository.MATERIALS, repo.AccountSyncStateRepository.RECIPES,
                        repo.AccountSyncStateRepository.CHARACTERS)) {
                    if (states.read(con, scope, source).isEmpty()) throw new AccountInputsUnavailableException();
                }
                if (!new repo.CharacterRepository().loadAllCharacterNames(con, scope).contains(charName)) {
                    throw new CharacterNotOwnedException();
                }
                new repo.AccountRegistryRepository().recordAuthorizedUse(con, scope, clock.instant());
                return con;
            } catch (SQLException | RuntimeException failure) {
                try { con.close(); } catch (SQLException ignored) { /* original failure wins */ }
                throw failure;
            }
        }
    }

    /** Sets the account freshness policy used to classify published inputs as stale. */
    public CraftingDiscoveryService withFreshness(java.time.Clock clock, java.time.Duration accountMaxAge) {
        this.clock = clock;
        // The scoped reads stamp activity with the bound clock: rebind so a policy set after
        // forScope still governs both marker classification and activity recording.
        if (scope != null) this.inputs = binding.bind(scope, clock);
        this.accountMaxAge = accountMaxAge;
        return this;
    }

    /** The account sources one Discovery calculation consumes. */
    public static final java.util.Set<String> SOURCES = java.util.Set.of(
            repo.AccountSyncStateRepository.BANK, repo.AccountSyncStateRepository.MATERIALS,
            repo.AccountSyncStateRepository.RECIPES, repo.AccountSyncStateRepository.CHARACTERS);

    /**
     * Source state of the account's Discovery inputs: {@code missing} were never published (no
     * calculation is possible), {@code stale} were published but expired (still usable).
     */
    public record InputState(java.util.Set<String> missing, java.util.Set<String> stale,
                             java.util.Set<String> optionalUnpublished) {
        public InputState(java.util.Set<String> missing, java.util.Set<String> stale) {
            this(missing, stale, java.util.Set.of());
        }

        /** Required missing/stale sources plus the optional wallet when authorized and unpublished. */
        public java.util.Set<String> needingRefresh() {
            java.util.Set<String> all = new java.util.TreeSet<>(missing);
            all.addAll(stale);
            all.addAll(optionalUnpublished);
            return all;
        }
    }

    /** Reads the markers only (no rows, no activity). */
    public InputState inputState() throws SQLException {
        model.AccountScope scope = requireScope();
        java.util.Set<String> missing = new java.util.TreeSet<>();
        java.util.Set<String> stale = new java.util.TreeSet<>();
        try (java.sql.Connection con = repo.Db.open()) {
            repo.ManagedSchema.requireManagedAccountSchema(con, "account-scoped Discovery");
            var states = new repo.AccountSourceStateRepository();
            java.time.Instant now = clock.instant();
            for (String source : SOURCES) {
                var state = states.read(con, scope, source);
                if (state.isEmpty()) missing.add(source);
                else if (accountMaxAge != null && state.get().completedAt().plus(accountMaxAge).isBefore(now)) {
                    stale.add(source);
                }
            }
        }
        // Optional wallet: tracked only with authority. Unpublished is an explicit unknown (never pending,
        // never blocking item-only rows); an expired publication is stale like any other source.
        java.util.Set<String> optional = new java.util.TreeSet<>();
        if (walletAuthorized) {
            try (java.sql.Connection con = repo.Db.open()) {
                var state = new repo.AccountSourceStateRepository().read(con, scope, repo.AccountSyncStateRepository.WALLET);
                if (state.isEmpty()) optional.add(repo.AccountSyncStateRepository.WALLET);
                else if (accountMaxAge != null && state.get().completedAt().plus(accountMaxAge).isBefore(clock.instant())) {
                    stale.add(repo.AccountSyncStateRepository.WALLET);
                }
            }
        }
        return new InputState(missing, stale, optional);
    }

    /** Records ordinary authorized use (activity only, never freshness) for a request that calculated nothing. */
    public void recordAuthorizedUse() throws SQLException {
        model.AccountScope scope = requireScope();
        try (java.sql.Connection con = repo.Db.open()) {
            new repo.AccountRegistryRepository().recordAuthorizedUse(con, scope, clock.instant());
        }
    }

    /** The account's stored inputs are missing: a keyed sync must publish them first. */
    public static final class AccountInputsUnavailableException extends RuntimeException {
        public AccountInputsUnavailableException() {
            super("account inputs have not been synchronized");
        }
    }

    /** The selected character is not on the authorized account's roster; nothing was calculated. */
    public static final class CharacterNotOwnedException extends RuntimeException {
        public CharacterNotOwnedException() {
            super("selected character is not on the authorized account");
        }
    }

    /**
     * Validates, before any quote, source or calculation work and without recording activity, that
     * the selected character belongs to this account.
     */
    public void verifySelectedCharacter(DiscChoice choice) throws SQLException {
        requireCharacterScope(choice);
        model.AccountScope scope = requireScope();
        try (java.sql.Connection con = repo.Db.open()) {
            repo.ManagedSchema.requireManagedAccountSchema(con, "account-scoped Discovery");
            // A never-published roster has nothing to compare with: that is the pending case.
            if (new repo.AccountSourceStateRepository()
                    .read(con, scope, repo.AccountSyncStateRepository.CHARACTERS).isEmpty()) return;
            if (!new repo.CharacterRepository().loadAllCharacterNames(con, scope).contains(choice.charName)) {
                throw new CharacterNotOwnedException();
            }
        }
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
            this.lastUsableRecipeIds = Collections.emptySet();
            return new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }

        this.lastUsableRecipeIds = candidates.usableRecipeIds();

        CalculationInputs inputs =
                loadCalculationInputs(oneCraftSettings, choice.charName, candidates.allRecipes());

        Map<Integer, CraftResult> results = planner.withVendorValues(ItemRepository.vendorValues(inputs.items())).withWallet(inputs.wallet()).evaluateAllSingleCraft(
                candidates.allRecipes(), inputs.sellableInventory(), inputs.boundInventory(),
                inputs.tp(), oneCraftSettings, candidates.usableRecipeIds());

        this.lastAllRecipes = candidates.allRecipes();
        this.lastSettings = oneCraftSettings;
        this.lastTp = inputs.tp();
        this.lastItems = inputs.items();
        this.lastResultsByRecipeId = results;

        return new DiscoveryData(candidates.visibleRecipes(), candidates.allRecipes(),
                results, inputs.items(), inputs.tp());
    }

    /**
     * Freshly calculated detail for one selected recipe:
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
                loadCalculationInputs(oneCraftSettings, choice.charName, candidates.allRecipes());

        CraftResult row = planner.withVendorValues(ItemRepository.vendorValues(inputs.items())).withWallet(inputs.wallet()).evaluateSingleCraft(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.boundInventory(), inputs.tp(), oneCraftSettings, candidates.usableRecipeIds());

        SingleCraftExplanation explanation = new SingleCraftExplainer(ItemRepository.vendorValues(inputs.items())).withWallet(inputs.wallet()).explainIndividual(
                selected, candidates.allRecipes(), inputs.sellableInventory(),
                inputs.boundInventory(), inputs.tp(), oneCraftSettings, candidates.usableRecipeIds());

        Set<Integer> knownUsableRecipeIds = candidates.knownRecipeIds().stream()
                .filter(candidates.usableRecipeIds()::contains)
                .collect(Collectors.toSet());
        return CraftingResolutionDetail.ofDiscovery(
                recipeId, selected, row, explanation, inputs.items(), inputs.tp(),
                knownUsableRecipeIds, candidates.candidateRecipeIds());
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
                              Set<Integer> candidateRecipeIds,
                              Set<Integer> knownRecipeIds,
                              Set<Integer> usableRecipeIds) {

        static Candidates noMissingRecipes() {
            return new Candidates(false, List.of(), List.of(), Set.of(), Set.of(), Set.of());
        }

        /** The recipe this operation would produce a row for, or null when it would produce none. */
        Recipe selected(int recipeId) {
            if (!candidateRecipeIds.contains(recipeId)) return null;
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
                                     Map<Integer, ItemRepository.ItemInfo> items,
                                     Map<Integer, Integer> wallet) {
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

        List<Integer> missingIds;
        Set<Integer> knownIds;
        requireScope();
        MissingRecipes missing = inputs.missingRecipes(charName, discipline);
        missingIds = missing.missingIds();
        knownIds = missing.knownIds();

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

        Set<Integer> candidateRecipeIds = visibleRecipes.stream()
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());

        Set<Integer> usableRecipeIds = allRecipes.stream()
                .filter(r -> r.minRating <= maxLevel)
                .filter(r -> supportsDiscipline(r.disciplinesText, discipline))
                .filter(r -> knownIds.contains(r.recipeId) || candidateRecipeIds.contains(r.recipeId))
                .map(r -> r.recipeId)
                .collect(Collectors.toSet());

        return new Candidates(true, visibleRecipes, allRecipes, candidateRecipeIds, knownIds, usableRecipeIds);
    }

    private boolean supportsDiscipline(String disciplinesText, String discipline) {
        return discipline == null || discipline.isBlank() || "All".equalsIgnoreCase(discipline)
                || (disciplinesText != null && java.util.Arrays.stream(disciplinesText.split(","))
                .map(String::trim).anyMatch(discipline::equalsIgnoreCase));
    }

    private CalculationInputs loadCalculationInputs(CraftingSettings settings,
                                                    String selectedCharacterName,
                                                    List<Recipe> allRecipes) throws SQLException {
        Map<Integer, Integer> sellableInv = Map.of();
        Map<Integer, Integer> boundInv = Map.of();

        if (settings.useOwnMats) {
            Map<Integer, InventoryRepository.OwnedQuantity> owned =
                    inputs.ownedInventory(selectedCharacterName);

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

        // Optional, authority-gated wallet: unknown (null) unless authorized, published and needed.
        Map<Integer, Integer> wallet = null;
        if (walletAuthorized && scope != null && allRecipes.stream().anyMatch(r -> !r.currencies.isEmpty())) {
            try (java.sql.Connection con = repo.Db.open()) {
                wallet = new repo.WalletRepository().load(con, scope).orElse(null);
            }
        }

        return new CalculationInputs(sellableInv, boundInv, tp, items, wallet);
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
                cr.blockedReason,
                cr.totalMatsSellValueCopper,
                cr.materialPurchaseCosts,
                cr.materialPurchaseCostsOne
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
                lastUsableRecipeIds
        );

        RecipeTreeBuilder treeBuilder = new RecipeTreeBuilder();
        return treeBuilder.buildTree(target, ctx);
    }
}
