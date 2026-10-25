package application;

import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import repo.DiscChoice;
import repo.Db;
import repo.EnvConfig;
import sync.AccountRefreshGateway;
import sync.ProfitQuoteRefreshRecorder;
import sync.tp.relevance.CraftingProfitItemCollector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-APP-014 acceptance criteria 4 and 6, against a real PostgreSQL: what the Trading Post
 * coordination actually refreshes, what coverage it leaves behind, and how a calculation moves from
 * provisional to final once that work lands.
 *
 * <p>{@code TradingPostRefreshCoordinatorTest} drives the same coordinator against a counting
 * gateway and asserts which runs happen. That is not enough for this criterion: a run that fetched
 * the wrong ids, or left part of the required set uncovered, would pass it. So here the selection is
 * the production {@link CraftingProfitItemCollector} over seeded relevance, the write is the
 * production {@code TpSync} upsert, and every assertion is about ids and coverage:
 *
 * <ul>
 *   <li>a single demand refreshes exactly the required ids that were stale or missing, and closes
 *       the gap completely;</li>
 *   <li>overlapping demands share one run, and the recheck pass covers the ids that became relevant
 *       only because account recipe knowledge changed while that run was in flight;</li>
 *   <li>the request that reported the gap answers provisionally, and the next one — over the same
 *       production readers — is final, with a different calculation identity;</li>
 *   <li>an interior quote change, which no coverage aggregate can show, makes resolution report
 *       changed inputs and carry its own recalculated row.</li>
 * </ul>
 *
 * <p>Deterministic throughout: the task facility is a fake the test runs by hand, and the one race
 * (a demand and an account change arriving mid-run) is driven by a latch inside the refresh rather
 * than by timing. The calculation itself is stubbed — the domain's mathematics is covered by its own
 * suites and is unchanged here — while every freshness input is read from the database through the
 * production readers and sessions.
 *
 * <p>Runs in a disposable, uniquely named schema that {@link Db#TEST_SCHEMA_PROPERTY} points every
 * {@code Db.open()} at for the duration of the test, so the developer's own database is never read
 * or written, and makes no Guild Wars 2 API call.
 */
class TradingPostCoverageCoordinationTest {

    private static final Recipe RECIPE = new Recipe(1, 10, 1, 0, "Artificer", List.of());
    private static final CraftResult ROW = new CraftResult(
            10, "Artificer", 3, Map.of(), Map.of(), 1_000, 50, 4_000, 600, 1_800, null);

    private final String schema = "test_tp_coordination_" + UUID.randomUUID().toString().replace("-", "");

    private final ProfitQuoteRefreshRecorder quotes = new ProfitQuoteRefreshRecorder();
    private final RecordingAccountGateway accountGateway = new RecordingAccountGateway();
    private final FakeBackgroundTasks tasks = new FakeBackgroundTasks();
    private final TradingPostRefreshCoordinator priceRefresh =
            new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(quotes), tasks);
    private final AccountRefreshCoordinator accountRefresh =
            new AccountRefreshCoordinator(new AccountRefreshService(accountGateway), tasks);

    private final CraftingProfitItemCollector collector = new CraftingProfitItemCollector();
    private CraftResult cannedRow = ROW;

    private Connection admin;

    @BeforeEach
    void seedRelevance() throws Exception {
        admin = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        admin.setSchema(schema);
        AccountSyncStateRepository.ensure(admin);

        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE account_recipes (recipe_id int PRIMARY KEY)");
            statement.execute("CREATE TABLE character_recipes (character_id int, recipe_id int)");
            statement.execute("CREATE TABLE recipes (recipe_id int PRIMARY KEY, output_item_id int)");
            statement.execute("CREATE TABLE recipe_ingredients (recipe_id int, item_id int)");
            statement.execute("CREATE TABLE tp_tradeable_items (item_id int PRIMARY KEY)");
            statement.execute("""
                CREATE TABLE tp_prices (
                    item_id         int PRIMARY KEY,
                    buy_quantity    bigint,
                    buy_unit_price  int,
                    sell_quantity   bigint,
                    sell_unit_price int,
                    fetched_at      timestamptz
                )
                """);

            // Recipe 1 is unlocked on the account and recipe 2 is learned by a character, so items
            // 10, 11, 20 and 21 are required. Recipe 3 is known to nobody yet: items 40 and 41 only
            // become relevant when the account learns it.
            statement.execute("INSERT INTO account_recipes VALUES (1)");
            statement.execute("INSERT INTO character_recipes VALUES (7, 2)");
            statement.execute("INSERT INTO recipes VALUES (1, 10), (2, 20), (3, 40)");
            statement.execute("INSERT INTO recipe_ingredients VALUES (1, 11), (2, 21), (3, 41)");
            statement.execute("INSERT INTO tp_tradeable_items VALUES (10), (11), (20), (21), (40), (41)");

            // 10 is inside the ten-minute policy; 11 is older than it; 20 has a row with no fetch
            // instant; 21 has no row at all. So {11, 20, 21} is the gap.
            statement.execute("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, "
                    + "sell_quantity, sell_unit_price, fetched_at) "
                    + "VALUES (10, 400, 120, 500, 150, now() - interval '2 minutes')");
            statement.execute("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, "
                    + "sell_quantity, sell_unit_price, fetched_at) "
                    + "VALUES (11, 900, 7, 1000, 9, now() - interval '3 hours')");
            statement.execute("INSERT INTO tp_prices (item_id, fetched_at) VALUES (20, NULL)");
        }

        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (admin == null) return;
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void oneDemandRefreshesExactlyTheUncoveredRequiredIdsAndClosesTheGap() throws Exception {
        quotes.answersNoMarketDataFor(20);
        assertEquals(Set.of(11, 20, 21), collector.collect(admin), "the gap before any work");

        TradingPostRefreshCoordinator.Admission admission = priceRefresh.requireProfitCoverage();
        tasks.run(admission.taskId());

        assertEquals(List.of(Set.of(11, 20, 21)), quotes.passes(),
                "one pass, fetching exactly the required ids that were stale or missing");

        CraftingProfitItemCollector.Coverage coverage = collector.coverage(admin);
        assertEquals(4, coverage.requiredItems());
        assertEquals(4, coverage.freshItems());
        assertEquals(0, coverage.uncoveredItems(), "nothing required may be left uncovered");
        assertTrue(coverage.covered());
        assertEquals(Set.of(), collector.collect(admin), "and nothing is left to fetch");

        // Item 20's upstream answer was "no market data". It is covered because a successful fetch
        // established that, and its stored quote stays explicitly empty rather than becoming free.
        assertNull(priceOf(20), "an unanswered market must never be written as a zero price");
        assertEquals(51, priceOf(11), "item 11's refetched quote replaced its stale numbers");
        assertEquals(150, priceOf(10), "and an item inside the policy keeps the quote it had");
    }

    @Test
    void overlappingDemandsShareOneRunAndTheRecheckCoversNewlyRelevantIds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        quotes.gate(() -> {
            if (!firstPass.compareAndSet(true, false)) return;
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "the first pass was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        TradingPostRefreshCoordinator.Admission first = priceRefresh.requireProfitCoverage();
        Thread run = new Thread(() -> tasks.run(first.taskId()), "tp-refresh");
        run.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the first pass never started");

        // Four more calculations report a gap while that run is in flight.
        List<TradingPostRefreshCoordinator.Admission> overlapping =
                Collections.synchronizedList(new ArrayList<>());
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread caller = new Thread(() -> overlapping.add(priceRefresh.requireProfitCoverage()));
            caller.start();
            callers.add(caller);
        }
        for (Thread caller : callers) caller.join();

        for (TradingPostRefreshCoordinator.Admission admission : overlapping) {
            assertEquals(TradingPostRefreshCoordinator.REUSE_COALESCED_INTO_ACTIVE_RUN,
                    admission.reuse());
            assertEquals(first.taskId(), admission.taskId());
        }
        assertEquals(1, tasks.admittedFor(TradingPostRefreshCoordinator.PROFIT_OPERATION),
                "overlapping demands must not start competing fetches");

        // And the account learns recipe 3 meanwhile, which makes items 40 and 41 required. The pass
        // in flight selected its ids before that happened and cannot cover them.
        try (Statement statement = admin.createStatement()) {
            statement.execute("INSERT INTO account_recipes VALUES (3)");
        }

        release.countDown();
        run.join(15_000);

        assertEquals(List.of(Set.of(11, 20, 21), Set.of(40, 41)), quotes.passes(),
                "the recheck pass must select exactly the ids that became relevant during the run");

        CraftingProfitItemCollector.Coverage coverage = collector.coverage(admin);
        assertEquals(6, coverage.requiredItems(), "the changed account inputs widened the set");
        assertEquals(0, coverage.uncoveredItems());
        assertTrue(coverage.covered(), "and the coordination left no part of it uncovered");
        assertEquals(Set.of(), collector.collect(admin));
        assertEquals(1, tasks.admittedFor(TradingPostRefreshCoordinator.PROFIT_OPERATION),
                "still one run, not one per demand");
        assertEquals(0, quotes.discoveryRefreshes(), "Discovery's item set is not touched");
    }

    @Test
    void aProvisionalRequestBecomesFinalOnceTheCoverageItDemandedIsRefreshed() throws Exception {
        markAllAccountSources(Instant.now());
        CachedCraftingProfitService service = service();

        CachedCraftingProfitService.ProfitOutcome provisional =
                service.profit(DiscChoice.all(), settings());

        assertTrue(provisional.hasResult(), "an uncovered quote set is still calculated from");
        assertTrue(provisional.freshness().provisional());
        assertFalse(provisional.freshness().tradingPost().covered());
        assertEquals(4, provisional.freshness().tradingPost().requiredItems());
        assertEquals(1, provisional.freshness().tradingPost().staleItems(), "item 11");
        assertEquals(2, provisional.freshness().tradingPost().missingItems(), "items 20 and 21");
        CalculationFreshness.OutstandingRefresh work = provisional.freshness().outstandingWork().stream()
                .filter(item -> item.kind() == CalculationFreshness.RefreshKind.TRADING_POST)
                .findFirst().orElseThrow();

        tasks.run(work.taskId());
        assertEquals(List.of(Set.of(11, 20, 21)), quotes.passes());

        CachedCraftingProfitService.ProfitOutcome fin = service.profit(DiscChoice.all(), settings());

        assertTrue(fin.hasResult());
        assertFalse(fin.freshness().provisional(),
                "the quotes the calculation demanded are now covered, so the result is final");
        assertEquals(List.of(), fin.freshness().outstandingWork(),
                "and the resubmitted request admits no further work");
        assertTrue(fin.freshness().tradingPost().covered());
        assertNotEquals(provisional.freshness().calculationId(), fin.freshness().calculationId(),
                "the second calculation read refreshed quotes, so it is a different generation");
        assertEquals(List.of(), accountGateway.calls, "fresh account markers admit no account work");
    }

    @Test
    void anInteriorQuoteChangeMakesResolutionReportChangedInputsAndCarryItsOwnRow() throws Exception {
        markAllAccountSources(Instant.now());
        CachedCraftingProfitService service = service();

        // Start from a confirmed-current generation, so nothing but the quote itself changes.
        TradingPostRefreshCoordinator.Admission admission = priceRefresh.requireProfitCoverage();
        tasks.run(admission.taskId());
        CachedCraftingProfitService.ProfitOutcome table = service.profit(DiscChoice.all(), settings());
        assertFalse(table.freshness().provisional());
        String rowGeneration = table.freshness().calculationId();
        CalculationFreshness.PriceCoverage before = table.freshness().tradingPost();

        // One required item's market moved and its quote was rewritten under the same fetch instant.
        try (Statement statement = admin.createStatement()) {
            statement.execute("UPDATE tp_prices SET sell_unit_price = sell_unit_price + 17 "
                    + "WHERE item_id = 11");
        }
        cannedRow = new CraftResult(
                10, "Artificer", 3, Map.of(), Map.of(), 1_000, 50, 4_200, 600, 1_900, null);

        CachedCraftingProfitService.ResolutionOutcome detail =
                service.resolution(1, DiscChoice.all(), settings(), rowGeneration);

        CalculationFreshness.PriceCoverage after = detail.freshness().tradingPost();
        assertEquals(before.requiredItems(), after.requiredItems());
        assertEquals(before.freshItems(), after.freshItems());
        assertEquals(before.staleItems(), after.staleItems());
        assertEquals(before.missingItems(), after.missingItems());
        assertEquals(before.oldestFetchedAt(), after.oldestFetchedAt());
        assertEquals(before.newestFetchedAt(), after.newestFetchedAt());
        assertTrue(after.covered());

        assertEquals(CachedCraftingProfitService.CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS,
                detail.consistency(),
                "no coverage aggregate changed, but the quote the calculation reads did");
        assertNotEquals(rowGeneration, detail.freshness().calculationId());
        assertEquals(cannedRow, detail.detail().row(),
                "the response must carry the row recalculated from the quotes it actually read");
    }

    // ---------- fixtures ----------

    /**
     * The production use case over the production readers and a production snapshot session: only
     * the calculation itself is canned, so every freshness decision below comes from the database.
     */
    private CachedCraftingProfitService service() {
        return new CachedCraftingProfitService(
                ProfitInputSession::open,
                session -> new CraftingProfitService(null, null, null, null, null, null, null, session) {
                    @Override
                    public ProfitData reload(DiscChoice choice, CraftingSettings settings) {
                        return new ProfitData(List.of(RECIPE), List.of(RECIPE),
                                Map.of(RECIPE.recipeId, cannedRow), Map.of(), Map.of());
                    }

                    @Override
                    public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                                  CraftingSettings settings) {
                        return new CraftingResolutionDetail(recipeId,
                                CraftingResolutionDetail.Status.AVAILABLE, RECIPE, cannedRow, null,
                                Map.of(), Map.of());
                    }
                },
                new CalculationInputVersions.DatabaseAccountSourceVersionReader(),
                new CalculationInputVersions.DatabasePriceCoverageReader(),
                accountRefresh,
                priceRefresh,
                tasks,
                Clock.systemUTC(),
                CachedCraftingProfitService.DEFAULT_ACCOUNT_MAX_AGE);
    }

    private void markAllAccountSources(Instant at) throws Exception {
        for (String source : CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES) {
            AccountSyncStateRepository.mark(admin, source, at);
        }
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 10_000, false, false, false);
    }

    /** The stored sell unit price of one item, null when the quote holds no market data. */
    private Integer priceOf(int itemId) throws Exception {
        try (Statement statement = admin.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT sell_unit_price FROM tp_prices WHERE item_id = " + itemId)) {
            assertTrue(rs.next(), "no stored quote for item " + itemId);
            int value = rs.getInt(1);
            return rs.wasNull() ? null : value;
        }
    }

    /** Records account fetches; this suite expects none, because every marker it writes is fresh. */
    private static final class RecordingAccountGateway extends AccountRefreshGateway {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override public void syncAccountBank() { calls.add(AccountSyncStateRepository.BANK); }
        @Override public void syncAccountMaterials() { calls.add(AccountSyncStateRepository.MATERIALS); }
        @Override public void syncAccountRecipes() { calls.add(AccountSyncStateRepository.RECIPES); }
        @Override public void syncAccountLuck() { calls.add(AccountSyncStateRepository.LUCK); }
        @Override public void syncCharacterCraftingAndRecipes() {
            calls.add(AccountSyncStateRepository.CHARACTERS);
        }
    }
}
