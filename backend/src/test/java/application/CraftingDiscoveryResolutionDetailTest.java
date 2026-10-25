package application;

import craft.BlockedReason;
import craft.CraftResult;
import craft.CraftTraceNode;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import craft.SingleCraftExplanation;
import org.junit.jupiter.api.Test;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer tests for {@link CraftingDiscoveryService#resolveDetail} (STORY-APP-012,
 * TARGET_ARCHITECTURE.md §13.1/§13.2): fake/in-memory adapters replace every repository and the
 * crafting-graph cache, while the real {@link CraftingPlanner} and the real
 * {@link craft.SingleCraftExplainer} do the actual work. What is asserted here is orchestration -
 * one fresh load per operation, candidate-set membership, request-local isolation and captured
 * item metadata - not domain economics, which {@code craft.*} owns; where a calculated value is
 * compared it is compared against the same calculation run from a known-fixed loader, never
 * against a number computed by hand in the expectation.
 */
class CraftingDiscoveryResolutionDetailTest {

    private static final Recipe TARGET = new Recipe(
            1, 100, 1, 0, "Chef", List.of(new Ingredient(200, 2)));
    private static final Recipe OTHER = new Recipe(
            2, 101, 1, 0, "Chef", List.of(new Ingredient(200, 1)));
    /** Above the chosen entry's rating, so the rating ceiling excludes it from the candidate set. */
    private static final Recipe ABOVE_RATING = new Recipe(
            3, 102, 1, 500, "Chef", List.of());

    private static final List<Recipe> GRAPH = List.of(TARGET, OTHER, ABOVE_RATING);

    private static final Map<Integer, PriceQuote> QUOTES = Map.of(
            100, new PriceQuote(1000, 1200),
            101, new PriceQuote(500, 600),
            102, new PriceQuote(5, 6),
            200, new PriceQuote(10, 12));

    /** Same items, all quoted higher - a second read of these would visibly change the answer. */
    private static final Map<Integer, PriceQuote> OTHER_QUOTES = Map.of(
            100, new PriceQuote(4000, 4200),
            101, new PriceQuote(500, 600),
            102, new PriceQuote(5, 6),
            200, new PriceQuote(70, 72));

    private static final DiscChoice SCOPE = DiscChoice.charDiscipline("Chef", 400, "Aria");

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying) {
        return new CraftingSettings(useOwnMats, allowBuying, 0, false, false, false);
    }

    private static InventoryRepository.OwnedQuantity owned(int sellable, int bound) {
        return new InventoryRepository.OwnedQuantity(sellable, bound);
    }

    // ------------------------------------------------- captured inputs, one load per operation

    @Test
    void producesTheSelectedRowAndItsExplanationFromOneFreshLoad() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1, 2);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, false));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status());
        assertEquals(1, detail.recipeId());

        CraftResult row = detail.row();
        assertNotNull(row);
        assertEquals(100, row.outputItemId);
        assertEquals(1, row.craftableCount, "Discovery evaluates one attempt even when inventory could support more");

        CraftTraceNode root = detail.explanation().root();
        assertEquals(100, root.itemId());
        assertEquals(1, root.requestedQuantity(), "the root requests the recipe's own output count");
        assertEquals(Integer.valueOf(1), root.recipeId());
        assertEquals(1, root.craftCount(), "the explanation is one craft, never the row's two");

        assertEquals(1, fakes.graphLoads.get());
        assertEquals(1, fakes.missingIdLoads.get());
        assertEquals(1, fakes.inventoryLoads.get());
        assertEquals(1, fakes.quoteLoads.get());
        assertEquals(1, fakes.itemLoads.get());
    }

    @Test
    void theRowAndTheTreeAgreeOnWhatOneCraftHadToBuy() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        // One owned unit against a two-per-craft requirement, buying enabled: the very first craft
        // has to purchase, so the row's "missing to buy for one further craft" and the tree's
        // bought quantities are the same fact about the same craft and must agree without either
        // being recomputed.
        fakes.perCharacter = Map.of(200, owned(1, 0));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, true));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status());
        Map<Integer, Integer> bought = boughtQuantities(detail.explanation().root());
        assertEquals(Map.of(200, 1), bought, "the explained craft bought its shortfall");
        assertEquals(bought, detail.row().missingToBuyOne);
    }

    @Test
    void theExplanationStartsFromTheCapturedInventoryNotTheRowSimulationsLeftovers() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, false));

        // The row's simulation crafted twice and left 1 of the 5 owned units. The explanation must
        // still see the full initial pool: from a single leftover unit it could only have sourced
        // 1 and reported 1 missing.
        assertEquals(1, detail.row().craftableCount);
        CraftTraceNode ingredient = onlyChild(detail.explanation().root());
        assertEquals(200, ingredient.itemId());
        assertEquals(2, ingredient.requestedQuantity());
        assertEquals(2, ingredient.inventoryQuantity());
        assertEquals(0, ingredient.missingQuantity());
    }

    @Test
    void noSecondPriceOrInventoryReadHappensWhileTheExplanationIsBuilt() throws SQLException {
        var changing = new Fakes();
        changing.missingIds = List.of(1);
        changing.perCharacter = Map.of(200, owned(5, 0));
        // Every read after the first answers differently; an accidental reload would show up as a
        // detail that is neither the first answer's nor internally consistent.
        changing.laterQuotes = OTHER_QUOTES;
        changing.laterPerCharacter = Map.of(200, owned(1, 0));

        var fixed = new Fakes();
        fixed.missingIds = List.of(1);
        fixed.perCharacter = Map.of(200, owned(5, 0));

        var drained = new Fakes();
        drained.missingIds = List.of(1);
        drained.quotes = OTHER_QUOTES;
        drained.perCharacter = Map.of(200, owned(1, 0));

        CraftingResolutionDetail actual =
                changing.service().resolveDetail(1, SCOPE, settings(true, true));

        assertEquals(1, changing.quoteLoads.get(), "prices must be read exactly once per operation");
        assertEquals(1, changing.inventoryLoads.get(), "inventory must be read exactly once per operation");
        assertEquals(1, changing.graphLoads.get(), "the graph must be read exactly once per operation");

        assertEquals(fixed.service().resolveDetail(1, SCOPE, settings(true, true)).explanation(),
                actual.explanation(),
                "the explanation must be the one the first (and only) read supports");

        assertNotEquals(drained.service().resolveDetail(1, SCOPE, settings(true, true)).explanation(),
                actual.explanation(),
                "the assertion above has teeth only if the later answers would have differed");
    }

    @Test
    void eachOperationRecalculatesRatherThanReturningTheEarlierAnswer() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        var service = fakes.service();

        CraftingResolutionDetail first =
                service.resolveDetail(1, SCOPE, settings(true, true));

        fakes.quotes = OTHER_QUOTES;
        CraftingResolutionDetail second =
                service.resolveDetail(1, SCOPE, settings(true, true));

        assertNotEquals(first.explanation(), second.explanation(),
                "a detail operation is a fresh calculation, not a cached earlier result");
    }

    // ------------------------------------------------------------- candidate-set membership

    @Test
    void aRecipeOutsideTheVisibleCandidateSetIsReportedAsNotInTheCalculation() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(2, SCOPE, settings(true, true));

        assertEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION, detail.status());
        assertEquals(2, detail.recipeId());
        assertNull(detail.row());
        assertNull(detail.explanation());
        assertTrue(detail.itemNames().isEmpty());
        assertEquals(0, fakes.quoteLoads.get(),
                "a recipe this operation has no row for needs no price or item load");
        assertEquals(0, fakes.inventoryLoads.get());
    }

    @Test
    void aRecipeExcludedByTheRatingCeilingIsNotInTheCalculationEither() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1, 3);
        var service = fakes.service();

        assertEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION,
                service.resolveDetail(3, SCOPE, settings(true, true)).status());
        assertEquals(CraftingResolutionDetail.Status.AVAILABLE,
                service.resolveDetail(1, SCOPE, settings(true, true)).status());
    }

    @Test
    void anEmptyDiscoveryOperationCannotReturnAnEarlierOperationsRowMetadataOrTrace() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        var service = fakes.service();

        // A populated table reload first, so every lookup cache the JavaFX views use is full.
        service.reload(SCOPE, settings(true, true));
        assertNotNull(service.getResultByRecipeId(1));
        assertEquals(CraftingResolutionDetail.Status.AVAILABLE,
                service.resolveDetail(1, SCOPE, settings(true, true)).status());

        fakes.missingIds = List.of();
        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, true));

        assertEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION, detail.status());
        assertNull(detail.row());
        assertNull(detail.explanation());
        assertTrue(detail.itemNames().isEmpty());
    }

    // ---------------------------------------------------- blocked, unavailable and row states

    @Test
    void aBlockedExplanationIsAvailableAndCarriesItsReasons() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        var service = fakes.service();

        // Own materials allowed but none owned, and buying disabled: nothing can be sourced.
        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, false));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status(),
                "a blocked explanation still exists and must not be reported as absent");
        assertNotNull(detail.explanation().root());
        assertFalse(detail.explanation().root().blockedReasons().isEmpty());
        // The row for the same operation says no craft completed - the two are separate facts.
        assertEquals(0, detail.row().craftableCount);
        assertEquals(BlockedReason.BUYING_DISABLED, detail.row().blockedReason);
    }

    @Test
    void anAbsentResolutionResultMapsToResultUnavailableWithoutAFabricatedTree() {
        // The domain's own "no result to explain" value, mapped by the application exactly as the
        // available case is. It is kept distinct from both a blocked tree and an absent recipe.
        CraftingResolutionDetail detail = CraftingResolutionDetail.of(
                1,
                TARGET,
                null,
                SingleCraftExplanation.unavailable(1, 100, 1),
                Map.of(100, new ItemRepository.ItemInfo(100, "Bowl of Soup", null, null)),
                Map.of());

        assertEquals(CraftingResolutionDetail.Status.RESULT_UNAVAILABLE, detail.status());
        assertNull(detail.explanation().root(), "an absent result must not become an empty tree");
        assertTrue(detail.itemNames().isEmpty(), "there are no traced items to name");
    }

    // ------------------------------------------------------------------- item-name enrichment

    @Test
    void itemNamesComeOnlyFromThisOperationsMetadataAndMissingMetadataStaysAbsent() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        fakes.items = Map.of(
                100, new ItemRepository.ItemInfo(100, "Bowl of Soup", null, null),
                200, new ItemRepository.ItemInfo(200, "  ", null, null));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, false));

        assertEquals(Map.of(100, "Bowl of Soup"), detail.itemNames());
        assertFalse(detail.itemNames().containsKey(200),
                "a traced item with no usable name must stay absent, not gain a fabricated one");
    }

    @Test
    void theSelectedCraftingCharacterIsAlsoTheInventoryCharacter() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(1, 4));
        fakes.unfiltered = Map.of();
        var service = fakes.service();

        CraftingResolutionDetail detail = service.resolveDetail(
                1, DiscChoice.charDiscipline("Chef", 400, "Aria"), settings(true, false));

        assertEquals("Aria", fakes.capturedMissingCharName, "the scope's crafting character");
        assertEquals("Aria", fakes.capturedInventoryCharacterName, "Discovery derives inventory scope from the selected character");
        assertTrue(fakes.calledForCharacter);
        assertFalse(fakes.calledUnfiltered, "a named inventory character must not use the unfiltered pool");
        // The character-scoped sellable/bound split reached the domain: neither pool alone covers
        // the two-unit requirement.
        assertEquals(2, onlyChild(detail.explanation().root()).inventoryQuantity());
    }

    // ---------------------------------------------------------------- request-local isolation

    @Test
    void successiveOperationsWithDifferentSettingsDoNotContaminateOneAnother() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1, 2);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        var service = fakes.service();

        CraftingResolutionDetail first =
                service.resolveDetail(1, SCOPE, settings(true, false));
        service.resolveDetail(2, DiscChoice.charDiscipline("Chef", 400, "Bran"), settings(false, true));
        CraftingResolutionDetail repeated =
                service.resolveDetail(1, SCOPE, settings(true, false));

        assertEquals(first.explanation(), repeated.explanation());
        assertEquals(first.itemNames(), repeated.itemNames());
        assertEquals(first.row().craftableCount, repeated.row().craftableCount);
        assertEquals(first.row().totalProfitCopper, repeated.row().totalProfitCopper);
    }

    @Test
    void concurrentOperationsWithDifferentScopesAndSettingsStayIsolated() throws Exception {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1, 2);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        fakes.unfiltered = Map.of(200, 1);
        var service = fakes.service();

        SingleCraftExplanation expectedA =
                service.resolveDetail(1, SCOPE, settings(true, false)).explanation();
        SingleCraftExplanation expectedB =
                service.resolveDetail(2, SCOPE, settings(true, true)).explanation();
        assertNotEquals(expectedA, expectedB, "the two operations must genuinely differ");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> work = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                work.add(() -> expectedA.equals(
                        service.resolveDetail(1, SCOPE, settings(true, false)).explanation()));
                work.add(() -> expectedB.equals(
                        service.resolveDetail(2, SCOPE, settings(true, true)).explanation()));
            }

            for (Future<Boolean> f : pool.invokeAll(work)) {
                assertTrue(f.get(), "a concurrent operation saw another operation's inputs or result");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void aFailedOperationLeavesNoStateBehindForTheNextOne() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        var service = fakes.service();

        service.reload(SCOPE, settings(true, false));
        CraftResult tableResult = service.getResultByRecipeId(1);
        assertNotNull(tableResult);

        fakes.inventoryFailure = new SQLException("simulated inventory failure");
        assertThrows(SQLException.class,
                () -> service.resolveDetail(1, SCOPE, settings(true, false)));

        // The failed operation held everything locally, so the table's lookup state is untouched
        // and the next operation succeeds normally.
        assertEquals(tableResult.craftableCount, service.getResultByRecipeId(1).craftableCount);
        assertEquals("Item 100", service.itemName(100));

        fakes.inventoryFailure = null;
        assertEquals(CraftingResolutionDetail.Status.AVAILABLE,
                service.resolveDetail(1, SCOPE, settings(true, false)).status());
    }

    @Test
    void aDetailOperationNeitherReadsNorWritesTheTableLookupState() throws SQLException {
        var fakes = new Fakes();
        fakes.missingIds = List.of(1);
        fakes.perCharacter = Map.of(200, owned(5, 0));
        fakes.items = Map.of(100, new ItemRepository.ItemInfo(100, "Bowl of Soup", null, null));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, SCOPE, settings(true, false));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status());
        assertNull(detail.row().tree, "no legacy JavaFX resolution tree is built for a detail row");
        assertNull(service.getResultByRecipeId(1),
                "resolveDetail must not populate the reload-scoped result cache");
        assertEquals("Item 100", service.itemName(100),
                "resolveDetail must not populate the reload-scoped item cache");
    }

    // ---------------------------------------------------------------------------- helpers

    private static CraftTraceNode onlyChild(CraftTraceNode node) {
        assertEquals(1, node.children().size(), "expected exactly one child of item " + node.itemId());
        return node.children().get(0);
    }

    /** Bought units per item across the whole explained craft, as the row's own summary counts them. */
    private static Map<Integer, Integer> boughtQuantities(CraftTraceNode root) {
        Map<Integer, Integer> bought = new java.util.HashMap<>();
        collectBought(root, bought);
        return bought;
    }

    private static void collectBought(CraftTraceNode node, Map<Integer, Integer> out) {
        if (node.boughtQuantity() > 0) {
            out.merge(node.itemId(), node.boughtQuantity(), Integer::sum);
        }
        for (CraftTraceNode child : node.children()) {
            collectBought(child, out);
        }
    }

    // ---------- Fake/in-memory adapters (TARGET_ARCHITECTURE.md §25) ----------

    /**
     * One bundle of fakes per service. Every loader counts its calls and can be told to answer
     * differently from the second call onwards, which is what exposes an accidental second read.
     */
    private static class Fakes {
        List<Integer> missingIds = List.of();
        Set<Integer> knownRecipeIds = Set.of();
        Map<Integer, PriceQuote> quotes = QUOTES;
        Map<Integer, PriceQuote> laterQuotes;
        Map<Integer, ItemRepository.ItemInfo> items = Map.of();
        Map<Integer, Integer> unfiltered = Map.of();
        Map<Integer, InventoryRepository.OwnedQuantity> perCharacter = Map.of();
        Map<Integer, InventoryRepository.OwnedQuantity> laterPerCharacter;
        SQLException inventoryFailure;

        final AtomicInteger graphLoads = new AtomicInteger();
        final AtomicInteger missingIdLoads = new AtomicInteger();
        final AtomicInteger inventoryLoads = new AtomicInteger();
        final AtomicInteger quoteLoads = new AtomicInteger();
        final AtomicInteger itemLoads = new AtomicInteger();

        volatile boolean calledUnfiltered;
        volatile boolean calledForCharacter;
        volatile String capturedMissingCharName;
        volatile String capturedInventoryCharacterName;

        CraftingDiscoveryService service() {
            return new CraftingDiscoveryService(
                    new FakeRecipeRepository(this), new FakeInventoryRepository(this),
                    new FakeTpPriceRepository(this), new FakeItemRepository(this),
                    new FakeCraftingGraphCache(this), new CraftingPlanner());
        }
    }

    private static class FakeCraftingGraphCache extends CraftingGraphCache {
        private final Fakes fakes;

        FakeCraftingGraphCache(Fakes fakes) {
            super(null);
            this.fakes = fakes;
        }

        @Override
        public CraftingGraph load() {
            fakes.graphLoads.incrementAndGet();
            return new CraftingGraph(GRAPH);
        }
    }

    private static class FakeRecipeRepository extends RecipeRepository {
        private final Fakes fakes;

        FakeRecipeRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public List<Integer> loadMissingDiscoverableRecipeIdsForCharacter(String charName, String discipline) {
            fakes.missingIdLoads.incrementAndGet();
            fakes.capturedMissingCharName = charName;
            return fakes.missingIds;
        }

        @Override
        public Set<Integer> loadKnownRecipeIds() {
            return fakes.knownRecipeIds;
        }
    }

    private static class FakeInventoryRepository extends InventoryRepository {
        private final Fakes fakes;

        FakeInventoryRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public Map<Integer, Integer> loadOwnedInventory() throws SQLException {
            if (fakes.inventoryFailure != null) throw fakes.inventoryFailure;
            fakes.inventoryLoads.incrementAndGet();
            fakes.calledUnfiltered = true;
            return fakes.unfiltered;
        }

        @Override
        public Map<Integer, OwnedQuantity> loadOwnedInventoryForCharacter(String selectedCharacterName)
                throws SQLException {
            if (fakes.inventoryFailure != null) throw fakes.inventoryFailure;
            int call = fakes.inventoryLoads.incrementAndGet();
            fakes.calledForCharacter = true;
            fakes.capturedInventoryCharacterName = selectedCharacterName;
            return (call > 1 && fakes.laterPerCharacter != null)
                    ? fakes.laterPerCharacter
                    : fakes.perCharacter;
        }
    }

    private static class FakeTpPriceRepository extends TpPriceRepository {
        private final Fakes fakes;

        FakeTpPriceRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> itemIds) {
            int call = fakes.quoteLoads.incrementAndGet();
            return (call > 1 && fakes.laterQuotes != null) ? fakes.laterQuotes : fakes.quotes;
        }
    }

    private static class FakeItemRepository extends ItemRepository {
        private final Fakes fakes;

        FakeItemRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public Map<Integer, ItemInfo> loadItems(Set<Integer> itemIds) {
            fakes.itemLoads.incrementAndGet();
            return fakes.items;
        }
    }
}
