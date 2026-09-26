package application;

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
import repo.CharacterRepository;
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
 * Application-layer tests for {@link CraftingProfitService#resolveDetail} (STORY-APP-012,
 * TARGET_ARCHITECTURE.md §13.1/§13.2). Companion to
 * {@link CraftingDiscoveryResolutionDetailTest}; this suite covers what is specific to the
 * coordinated Profit path - the roster built from the chosen scope, the three-way
 * sellable/account-bound/character-bound inventory split, and character assignment - with the same
 * fake adapters, real planner and real explainer, and the same rule that no economic value is
 * computed by hand in an expectation.
 */
class CraftingProfitResolutionDetailTest {

    private static final Recipe TARGET = new Recipe(
            1, 100, 1, 0, "Artificer", List.of(new Ingredient(200, 2)));
    private static final Recipe OTHER = new Recipe(
            2, 101, 1, 0, "Artificer", List.of(new Ingredient(200, 1)));

    private static final List<Recipe> GRAPH = List.of(TARGET, OTHER);

    private static final Map<Integer, PriceQuote> QUOTES = Map.of(
            100, new PriceQuote(1000, 1200),
            101, new PriceQuote(500, 600),
            200, new PriceQuote(10, 12));

    private static final Map<Integer, PriceQuote> OTHER_QUOTES = Map.of(
            100, new PriceQuote(4000, 4200),
            101, new PriceQuote(500, 600),
            200, new PriceQuote(70, 72));

    private static CraftingSettings settings(boolean useOwnMats, boolean allowBuying) {
        return new CraftingSettings(useOwnMats, allowBuying, 0, false, false, false);
    }

    // -------------------------------------------- captured inputs, one load per operation

    @Test
    void producesTheSelectedRowAndItsExplanationFromOneFreshLoad() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = GRAPH;
        fakes.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status());

        CraftResult row = detail.row();
        assertNotNull(row);
        assertEquals(100, row.outputItemId);
        assertEquals(2, row.craftableCount);

        CraftTraceNode root = detail.explanation().root();
        assertEquals(100, root.itemId());
        assertEquals(1, root.requestedQuantity());
        assertEquals(1, root.craftCount(), "the explanation is one craft, never the row's two");
        assertEquals("Aria", root.characterName(), "the coordinated assignment is part of the explanation");

        assertEquals(1, fakes.recipeLoads.get());
        assertEquals(1, fakes.graphLoads.get());
        assertEquals(1, fakes.characterLoads.get());
        assertEquals(1, fakes.inventoryLoads.get());
        assertEquals(1, fakes.quoteLoads.get());
        assertEquals(1, fakes.itemLoads.get());
    }

    @Test
    void theExplanationStartsFromTheCapturedInventoryNotTheRowSimulationsLeftovers() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = GRAPH;
        fakes.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));

        assertEquals(2, detail.row().craftableCount, "the row's simulation consumed 4 of the 5 owned units");
        CraftTraceNode ingredient = onlyChild(detail.explanation().root());
        assertEquals(2, ingredient.inventoryQuantity(),
                "the explanation must see the initial pool, not the single unit the row left");
        assertEquals(0, ingredient.missingQuantity());
    }

    @Test
    void noSecondPriceOrInventoryReadHappensWhileTheExplanationIsBuilt() throws SQLException {
        var changing = new Fakes();
        changing.visible = GRAPH;
        changing.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        changing.sellable = Map.of(200, 5);
        changing.laterQuotes = OTHER_QUOTES;
        changing.laterSellable = Map.of(200, 1);

        var fixed = new Fakes();
        fixed.visible = GRAPH;
        fixed.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fixed.sellable = Map.of(200, 5);

        var drained = new Fakes();
        drained.visible = GRAPH;
        drained.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        drained.quotes = OTHER_QUOTES;
        drained.sellable = Map.of(200, 1);

        CraftingResolutionDetail actual =
                changing.service().resolveDetail(1, DiscChoice.all(), settings(true, true));

        assertEquals(1, changing.quoteLoads.get(), "prices must be read exactly once per operation");
        assertEquals(1, changing.inventoryLoads.get(), "inventory must be read exactly once per operation");
        assertEquals(1, changing.graphLoads.get(), "the graph must be read exactly once per operation");
        assertEquals(1, changing.characterLoads.get(), "the roster must be read exactly once per operation");

        assertEquals(fixed.service().resolveDetail(1, DiscChoice.all(), settings(true, true)).explanation(),
                actual.explanation(),
                "the explanation must be the one the first (and only) read supports");

        assertNotEquals(drained.service().resolveDetail(1, DiscChoice.all(), settings(true, true)).explanation(),
                actual.explanation(),
                "the assertion above has teeth only if the later answers would have differed");
    }

    // ------------------------------------------------------ coordinated scope and roster

    @Test
    void theScopeDecidesTheCandidateSetAndTheRosterForThisOperation() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET);
        fakes.characters = List.of(
                new CharacterRepository.DiscRow("Aria", "Artificer", 400, true),
                new CharacterRepository.DiscRow("Bran", "Chef", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        CraftingResolutionDetail detail = service.resolveDetail(
                1, DiscChoice.disciplineOnly("Artificer"), settings(true, false));

        assertEquals(List.of("Artificer"), fakes.disciplineQueries,
                "the visible candidate set is loaded for the chosen discipline");
        assertEquals("Aria", detail.explanation().root().characterName(),
                "only the character holding the chosen discipline is eligible");
    }

    @Test
    void characterBoundInventoryStaysWithItsOwningCharacter() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET);
        fakes.characters = List.of(
                new CharacterRepository.DiscRow("Aria", "Artificer", 400, true),
                new CharacterRepository.DiscRow("Bran", "Artificer", 400, true));
        fakes.characterBound = Map.of("Bran", Map.of(200, 4));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status());
        CraftTraceNode root = detail.explanation().root();
        assertEquals("Bran", root.characterName(),
                "only the owner of the soulbound materials can perform the craft");
        assertEquals(2, onlyChild(root).inventoryQuantity());
    }

    @Test
    void aCharacterDisciplineScopeRestrictsTheRosterToThatCharacter() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET);
        fakes.characters = List.of(
                new CharacterRepository.DiscRow("Aria", "Artificer", 400, true),
                new CharacterRepository.DiscRow("Bran", "Artificer", 400, true));
        fakes.characterBound = Map.of("Bran", Map.of(200, 4));
        var service = fakes.service();

        // Aria alone cannot reach Bran's soulbound materials, so the same inputs now block.
        CraftingResolutionDetail detail = service.resolveDetail(
                1, DiscChoice.charDiscipline("Artificer", 400, "Aria"), settings(true, false));

        assertEquals(1, fakes.characterQueries.size());
        assertEquals("Aria", fakes.characterQueries.get(0)[0]);
        assertEquals("Artificer", fakes.characterQueries.get(0)[1]);
        assertEquals(0, detail.row().craftableCount);
        assertFalse(detail.explanation().root().blockedReasons().isEmpty());
    }

    // ------------------------------------------------------- candidate-set membership

    @Test
    void aRecipeOutsideTheVisibleCandidateSetIsReportedAsNotInTheCalculation() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET);
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(2, DiscChoice.all(), settings(true, true));

        assertEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION, detail.status());
        assertEquals(2, detail.recipeId());
        assertNull(detail.row());
        assertNull(detail.explanation());
        assertTrue(detail.itemNames().isEmpty());
        assertEquals(0, fakes.quoteLoads.get());
        assertEquals(0, fakes.inventoryLoads.get());
    }

    @Test
    void aVisibleRecipeTheCraftingGraphDoesNotContainProducesNoRowAndSoIsNotInTheCalculation()
            throws SQLException {
        Recipe notInGraph = new Recipe(9, 909, 1, 0, "Artificer", List.of());
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET, notInGraph);
        var service = fakes.service();

        // The table skips such a recipe (no planner result exists for it), so the detail operation
        // must report the same absence rather than inventing a row.
        assertEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION,
                service.resolveDetail(9, DiscChoice.all(), settings(true, true)).status());
    }

    // ------------------------------------------------------------- item-name enrichment

    @Test
    void itemNamesComeOnlyFromThisOperationsMetadataAndMissingMetadataStaysAbsent() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET);
        fakes.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fakes.sellable = Map.of(200, 5);
        fakes.items = Map.of(100, new ItemRepository.ItemInfo(100, "Glob of Ectoplasm", null, null));
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));

        assertEquals(Map.of(100, "Glob of Ectoplasm"), detail.itemNames());
        assertFalse(detail.itemNames().containsKey(200),
                "an item with no captured metadata must stay absent, not gain a fabricated name");
    }

    // ------------------------------------------------------------ request-local isolation

    @Test
    void successiveOperationsWithDifferentScopesAndSettingsDoNotContaminateOneAnother() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = GRAPH;
        fakes.characters = List.of(
                new CharacterRepository.DiscRow("Aria", "Artificer", 400, true),
                new CharacterRepository.DiscRow("Bran", "Chef", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        CraftingResolutionDetail first =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));
        service.resolveDetail(2, DiscChoice.disciplineOnly("Artificer"), settings(false, true));
        CraftingResolutionDetail repeated =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));

        assertEquals(first.explanation(), repeated.explanation());
        assertEquals(first.itemNames(), repeated.itemNames());
        assertEquals(first.row().craftableCount, repeated.row().craftableCount);
        assertEquals(first.row().totalProfitCopper, repeated.row().totalProfitCopper);
    }

    @Test
    void concurrentOperationsWithDifferentScopesAndSettingsStayIsolated() throws Exception {
        var fakes = new Fakes();
        fakes.visible = GRAPH;
        fakes.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        SingleCraftExplanation expectedA =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false)).explanation();
        SingleCraftExplanation expectedB =
                service.resolveDetail(2, DiscChoice.all(), settings(false, true)).explanation();
        assertNotEquals(expectedA, expectedB, "the two operations must genuinely differ");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> work = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                work.add(() -> expectedA.equals(
                        service.resolveDetail(1, DiscChoice.all(), settings(true, false)).explanation()));
                work.add(() -> expectedB.equals(
                        service.resolveDetail(2, DiscChoice.all(), settings(false, true)).explanation()));
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
        fakes.visible = List.of(TARGET);
        fakes.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        service.reload(DiscChoice.all(), settings(true, false));
        CraftResult tableResult = service.getRawResultByRecipeId(1);
        assertNotNull(tableResult);

        fakes.inventoryFailure = new SQLException("simulated inventory failure");
        assertThrows(SQLException.class,
                () -> service.resolveDetail(1, DiscChoice.all(), settings(true, false)));

        assertEquals(tableResult.craftableCount, service.getRawResultByRecipeId(1).craftableCount,
                "a failed detail operation must not disturb the table's lookup state");

        fakes.inventoryFailure = null;
        assertEquals(CraftingResolutionDetail.Status.AVAILABLE,
                service.resolveDetail(1, DiscChoice.all(), settings(true, false)).status());
    }

    @Test
    void aDetailOperationNeitherReadsNorWritesTheTableLookupState() throws SQLException {
        var fakes = new Fakes();
        fakes.visible = List.of(TARGET);
        fakes.characters = List.of(new CharacterRepository.DiscRow("Aria", "Artificer", 400, true));
        fakes.sellable = Map.of(200, 5);
        var service = fakes.service();

        CraftingResolutionDetail detail =
                service.resolveDetail(1, DiscChoice.all(), settings(true, false));

        assertEquals(CraftingResolutionDetail.Status.AVAILABLE, detail.status());
        assertNull(detail.row().tree, "no legacy JavaFX resolution tree is built for a detail row");
        assertNull(service.getRawResultByRecipeId(1),
                "resolveDetail must not populate the reload-scoped result cache");
        assertNull(service.getResultByRecipeId(1));
    }

    // ---------------------------------------------------------------------------- helpers

    private static CraftTraceNode onlyChild(CraftTraceNode node) {
        assertEquals(1, node.children().size(), "expected exactly one child of item " + node.itemId());
        return node.children().get(0);
    }

    // ---------- Fake/in-memory adapters (TARGET_ARCHITECTURE.md §25) ----------

    private static class Fakes {
        List<Recipe> visible = List.of();
        List<CharacterRepository.DiscRow> characters = List.of();
        Map<Integer, PriceQuote> quotes = QUOTES;
        Map<Integer, PriceQuote> laterQuotes;
        Map<Integer, ItemRepository.ItemInfo> items = Map.of();
        Map<Integer, Integer> sellable = Map.of();
        Map<Integer, Integer> laterSellable;
        Map<Integer, Integer> accountBound = Map.of();
        Map<String, Map<Integer, Integer>> characterBound = Map.of();
        SQLException inventoryFailure;

        final AtomicInteger recipeLoads = new AtomicInteger();
        final AtomicInteger graphLoads = new AtomicInteger();
        final AtomicInteger characterLoads = new AtomicInteger();
        final AtomicInteger inventoryLoads = new AtomicInteger();
        final AtomicInteger quoteLoads = new AtomicInteger();
        final AtomicInteger itemLoads = new AtomicInteger();

        final List<String> disciplineQueries = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String[]> characterQueries = java.util.Collections.synchronizedList(new ArrayList<>());

        CraftingProfitService service() {
            return new CraftingProfitService(
                    new FakeRecipeRepository(this), new FakeInventoryRepository(this),
                    new FakeTpPriceRepository(this), new FakeItemRepository(this),
                    new FakeCharacterRepository(this), new FakeCraftingGraphCache(this),
                    new CraftingPlanner());
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
        public List<Recipe> loadRecipes(String discipline) {
            fakes.recipeLoads.incrementAndGet();
            fakes.disciplineQueries.add(discipline);
            return fakes.visible;
        }

        @Override
        public List<Recipe> loadRecipesForCharacter(String charName, String discipline) {
            fakes.recipeLoads.incrementAndGet();
            fakes.characterQueries.add(new String[]{charName, discipline});
            return fakes.visible;
        }
    }

    private static class FakeInventoryRepository extends InventoryRepository {
        private final Fakes fakes;

        FakeInventoryRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames)
                throws SQLException {
            if (fakes.inventoryFailure != null) throw fakes.inventoryFailure;
            int call = fakes.inventoryLoads.incrementAndGet();
            Map<Integer, Integer> sellable = (call > 1 && fakes.laterSellable != null)
                    ? fakes.laterSellable
                    : fakes.sellable;
            return new CoordinatedInventory(sellable, fakes.accountBound, fakes.characterBound);
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

    private static class FakeCharacterRepository extends CharacterRepository {
        private final Fakes fakes;

        FakeCharacterRepository(Fakes fakes) {
            this.fakes = fakes;
        }

        @Override
        public List<DiscRow> loadAllCharacterCrafting() {
            fakes.characterLoads.incrementAndGet();
            return fakes.characters;
        }
    }
}
