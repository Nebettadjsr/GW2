package application;

import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import repo.AccountSourceStateRepository;
import repo.DiscChoice;
import sync.AccountRefreshGateway;
import sync.TradingPostPriceRefreshGateway;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STORY-APP-015: scoped calculations report the captured account/generations and admit no legacy work. */
class CachedCraftingProfitServiceScopedTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant FRESH_AT = NOW.minus(Duration.ofMinutes(3));
    private static final Instant EXPIRED_AT = NOW.minus(Duration.ofHours(4));
    private static final Recipe RECIPE = new Recipe(7, 100, 2, 275, "Artificer", List.of());
    private static final CraftResult RESULT = new CraftResult(
            100, "Artificer", 4, Map.of(), Map.of(), 1_234, 56, 7_890, 700, 2_800, null);

    private final AtomicInteger legacyCalls = new AtomicInteger();
    private final FakeBackgroundTasks tasks = new FakeBackgroundTasks();
    private final AccountRefreshGateway countingAccountGateway = new AccountRefreshGateway() {
        @Override public void syncAccountBank() { legacyCalls.incrementAndGet(); }
        @Override public void syncAccountMaterials() { legacyCalls.incrementAndGet(); }
        @Override public void syncAccountRecipes() { legacyCalls.incrementAndGet(); }
        @Override public void syncAccountLuck() { legacyCalls.incrementAndGet(); }
        @Override public void syncCharacterCraftingAndRecipes() { legacyCalls.incrementAndGet(); }
    };
    private final TradingPostPriceRefreshGateway countingPriceGateway = new TradingPostPriceRefreshGateway() {
        @Override public void syncTpPricesForProfit() { legacyCalls.incrementAndGet(); }
    };

    private Instant completedAt = EXPIRED_AT;
    private boolean covered = false;
    private final List<AccountScope> opened = new ArrayList<>();

    @Test
    void scopedEnvelopeNamesAccountAndGenerationsAndAdmitsNoLegacyRefresh() throws Exception {
        AccountScope scope = AccountScope.of(UUID.randomUUID());
        CachedCraftingProfitService.ProfitOutcome outcome =
                service().profit(scope, DiscChoice.all(), settings());

        CalculationFreshness freshness = outcome.freshness();
        assertEquals(List.of(scope), opened);
        assertEquals(scope.id(), freshness.accountId());
        for (String source : CalculationInputVersions.REQUIRED_ACCOUNT_SOURCES) {
            assertEquals(5L, freshness.sourceGenerations().get(source), source);
        }
        assertTrue(freshness.provisional());
        assertTrue(freshness.outstandingWork().isEmpty());
        assertEquals(2, freshness.failures().size());
        assertTrue(freshness.failures().stream().allMatch(f -> f.code().equals("SCOPED_REFRESH_NOT_ADMITTED")));
        assertEquals(0, legacyCalls.get(), "no unscoped refresh work may run from a scoped path");
        assertEquals(0, tasks.admitted.size(), "no task may be admitted from a scoped path");
    }

    @Test
    void freshCoveredScopedInputsAreFinalWithNoFailures() throws Exception {
        completedAt = FRESH_AT;
        covered = true;
        CalculationFreshness freshness =
                service().profit(AccountScope.of(UUID.randomUUID()), DiscChoice.all(), settings()).freshness();
        assertFalse(freshness.provisional());
        assertTrue(freshness.failures().isEmpty());
    }

    private CachedCraftingProfitService service() {
        return new CachedCraftingProfitService(
                new ProfitInputSession.Factory() {
                    @Override public ProfitInputSession open() { return ProfitInputSession.detached(); }
                    @Override public ProfitInputSession open(AccountScope scope) throws SQLException {
                        opened.add(scope);
                        return ProfitInputSession.detached();
                    }
                },
                session -> new CraftingProfitService(null, null, null, null, null, null, null, session) {
                    @Override public ProfitData reload(DiscChoice choice, CraftingSettings settings) {
                        return new ProfitData(List.of(RECIPE), List.of(RECIPE),
                                Map.of(RECIPE.recipeId, RESULT), Map.of(), Map.of());
                    }
                },
                (con, sources) -> Map.of(),
                (con, scope, sources) -> {
                    Map<String, AccountSourceStateRepository.SourceState> states = new java.util.LinkedHashMap<>();
                    for (String s : sources) states.put(s, new AccountSourceStateRepository.SourceState(s, 5L, completedAt));
                    return states;
                },
                con -> new CalculationFreshness.PriceCoverage(
                        10, covered ? 10 : 0, covered ? 0 : 10, 0, FRESH_AT, FRESH_AT, covered, "q"),
                new AccountRefreshCoordinator(new AccountRefreshService(countingAccountGateway), tasks),
                new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(countingPriceGateway), tasks),
                tasks,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 10_000, false, false, false);
    }
}
