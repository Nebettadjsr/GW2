package application;

import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import craft.SingleCraftExplanation;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import repo.AccountSourceStateRepository;
import repo.DiscChoice;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private final TradingPostPriceRefreshGateway countingPriceGateway = new TradingPostPriceRefreshGateway() {
        @Override public void syncTpPricesForProfit() { legacyCalls.incrementAndGet(); }
        @Override public void syncTpPricesForProfit(AccountScope scope) { requiredScopes.add(scope); }
    };

    private Instant completedAt = EXPIRED_AT;
    private boolean covered = false;
    private long sourceGeneration = 5L;
    private final List<CraftingSettings> calculatedSettings = new ArrayList<>();
    private final List<AccountScope> opened = new ArrayList<>();
    private final List<AccountScope> requiredScopes = new java.util.concurrent.CopyOnWriteArrayList<>();

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
        assertEquals(1, freshness.outstandingWork().size());
        assertEquals(CalculationFreshness.RefreshKind.TRADING_POST, freshness.outstandingWork().get(0).kind(),
                "only the shared, key-free quote refresh is admitted");
        assertEquals(1, freshness.failures().size());
        assertTrue(freshness.failures().stream()
                .anyMatch(f -> f.code().equals(CachedCraftingProfitService.CREDENTIAL_REQUIRED)
                        && f.kind() == CalculationFreshness.RefreshKind.ACCOUNT),
                "a stale account source with no key is reported as credential-required");
        assertEquals(0, legacyCalls.get(), "no unscoped refresh work may run from a scoped path");
        tasks.run(tasks.admitted.get(0).id());
        assertEquals(1, tasks.admitted.size(), "no account task may be admitted without a key");
        assertEquals(List.of(scope), requiredScopes, "quote demand is the requesting account's required ids");
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

    @Test
    void finiteSettingsStayRequestLocalAcrossTableDetailAndAccountsWithTheSameSourceVersions() throws Exception {
        completedAt = FRESH_AT;
        covered = true;
        AccountScope a = AccountScope.of("11111111-2222-3333-4444-555555555555");
        AccountScope b = AccountScope.of("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CachedCraftingProfitService service = service();
        var table = service.profit(a, DiscChoice.all(), finiteSettings(250, 25));
        String generation = table.freshness().calculationId();
        var sameDetail = service.resolution(a, RECIPE.recipeId, DiscChoice.all(), finiteSettings(250, 25), generation);
        var changedDetail = service.resolution(a, RECIPE.recipeId, DiscChoice.all(), finiteSettings(200, 30), generation);
        var otherAccount = service.resolution(b, RECIPE.recipeId, DiscChoice.all(), finiteSettings(250, 25), generation);

        // The token names published input generations. Each operation must still use its own
        // effective quantity maps; a matching token must never reuse an earlier operation's settings.
        assertEquals(generation, sameDetail.freshness().calculationId());
        assertEquals(generation, changedDetail.freshness().calculationId());
        assertEquals(CachedCraftingProfitService.CONSISTENCY_FRESH_CALCULATION, sameDetail.consistency());
        assertNotEquals(generation, otherAccount.freshness().calculationId());
        assertEquals(CachedCraftingProfitService.CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS,
                otherAccount.consistency());
        assertEquals(List.of(a, a, a, b), opened);
        assertEquals(List.of(Map.of(200, 250), Map.of(200, 250), Map.of(200, 200), Map.of(200, 250)),
                calculatedSettings.stream().map(settings -> settings.protectedBankQuantities).toList());
        assertEquals(List.of(Map.of(200, 25), Map.of(200, 25), Map.of(200, 30), Map.of(200, 25)),
                calculatedSettings.stream().map(settings -> settings.protectedMaterialQuantities).toList());
        assertTrue(calculatedSettings.stream().allMatch(settings -> settings.protectedItemIds.isEmpty()));
        assertTrue(tasks.admitted.isEmpty(), "fresh quantity-only changes must not admit upstream work");
    }

    @Test
    void aChangedSourceGenerationRecalculatesDetailWithTheLatestFiniteSettings() throws Exception {
        completedAt = FRESH_AT;
        covered = true;
        AccountScope scope = AccountScope.of("11111111-2222-3333-4444-555555555555");
        CachedCraftingProfitService service = service();
        var table = service.profit(scope, DiscChoice.all(), finiteSettings(250, 25));
        sourceGeneration = 6L;

        var detail = service.resolution(scope, RECIPE.recipeId, DiscChoice.all(), finiteSettings(200, 30),
                table.freshness().calculationId());

        assertEquals(CachedCraftingProfitService.CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS, detail.consistency());
        assertNotEquals(table.freshness().calculationId(), detail.freshness().calculationId());
        assertTrue(detail.freshness().sourceGenerations().values().stream().allMatch(value -> value == 6L));
        assertEquals(Map.of(200, 200), calculatedSettings.get(1).protectedBankQuantities);
        assertEquals(Map.of(200, 30), calculatedSettings.get(1).protectedMaterialQuantities);
        assertEquals(Map.of(200, 250), calculatedSettings.getFirst().protectedBankQuantities);
        assertTrue(tasks.admitted.isEmpty());
    }

    @Test
    void allocationOnlyChangesStayRequestLocalAcrossTableDetailAccountsAndNewGenerations() throws Exception {
        completedAt = FRESH_AT;
        covered = true;
        AccountScope a = AccountScope.of("11111111-2222-3333-4444-555555555555");
        AccountScope b = AccountScope.of("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String template = """
                {"materialProtectionEnabled":true,"protectedItemIds":[],"protectedBankQuantities":{"200":250},
                 "protectedBankAllocations":[
                   {"slot":0,"itemId":200,"reservedUnits":%d,"observedCount":250,"binding":"Account","boundTo":null},
                   {"slot":9,"itemId":200,"reservedUnits":%d,"observedCount":250,"binding":"Character","boundTo":"Aria"}]}
                """;
        String firstJson = template.formatted(250, 0);
        String thirdJson = template.formatted(0, 250);
        CraftingSettings first = web.CraftingSettingsJsonFixture.fromSettingsJson(firstJson);
        CraftingSettings third = web.CraftingSettingsJsonFixture.fromSettingsJson(thirdJson);
        CachedCraftingProfitService service = service();

        var table = service.profit(a, DiscChoice.all(), first);
        String generation = table.freshness().calculationId();
        var same = service.resolution(a, RECIPE.recipeId, DiscChoice.all(), first, generation);
        var changed = service.resolution(a, RECIPE.recipeId, DiscChoice.all(), third, generation);
        var otherAccount = service.resolution(b, RECIPE.recipeId, DiscChoice.all(), third, generation);
        sourceGeneration = 6L;
        var refreshed = service.resolution(a, RECIPE.recipeId, DiscChoice.all(), first, generation);

        ObjectMapper json = new ObjectMapper();
        var firstAllocations = json.readTree(firstJson).get("protectedBankAllocations");
        var thirdAllocations = json.readTree(thirdJson).get("protectedBankAllocations");
        for (CraftingSettings settings : calculatedSettings) {
            assertNotNull(json.valueToTree(settings).get("protectedBankAllocations"));
        }
        assertEquals(List.of(firstAllocations, firstAllocations, thirdAllocations, thirdAllocations, firstAllocations),
                calculatedSettings.stream().map(settings -> json.valueToTree(settings).get("protectedBankAllocations")).toList());
        assertEquals(generation, same.freshness().calculationId());
        assertEquals(generation, changed.freshness().calculationId(), "generation identity does not cache earlier settings");
        assertNotEquals(generation, otherAccount.freshness().calculationId());
        assertNotEquals(generation, refreshed.freshness().calculationId());
        assertEquals(CachedCraftingProfitService.CONSISTENCY_RECALCULATED_FOR_CHANGED_INPUTS, refreshed.consistency());
        assertTrue(tasks.admitted.isEmpty(), "fresh allocation-only changes must not admit upstream work");
    }

    private CachedCraftingProfitService service() {
        return new CachedCraftingProfitService(
                new ProfitInputSession.Factory() {
                    @Override public ProfitInputSession open(AccountScope scope) throws SQLException {
                        opened.add(scope);
                        return ProfitInputSession.detached();
                    }
                },
                session -> new CraftingProfitService(null, null, null, null, null, null, null, session) {
                    @Override public ProfitData reload(DiscChoice choice, CraftingSettings settings) {
                        calculatedSettings.add(settings);
                        return new ProfitData(List.of(RECIPE), List.of(RECIPE),
                                Map.of(RECIPE.recipeId, RESULT), Map.of(), Map.of());
                    }
                    @Override public CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                                            CraftingSettings settings) {
                        calculatedSettings.add(settings);
                        return new CraftingResolutionDetail(RECIPE.recipeId,
                                CraftingResolutionDetail.Status.RESULT_UNAVAILABLE, RECIPE, RESULT,
                                SingleCraftExplanation.unavailable(RECIPE.recipeId, RECIPE.outputItemId,
                                        RECIPE.outputCount), Map.of(), Map.of());
                    }
                },
                (con, scope, sources) -> {
                    Map<String, AccountSourceStateRepository.SourceState> states = new java.util.LinkedHashMap<>();
                    for (String s : sources) states.put(s, new AccountSourceStateRepository.SourceState(s, sourceGeneration, completedAt));
                    return states;
                },
                con -> new CalculationFreshness.PriceCoverage(
                        10, covered ? 10 : 0, covered ? 0 : 10, 0, FRESH_AT, FRESH_AT, covered, "q"),
                new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(countingPriceGateway), tasks),
                tasks,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 10_000, false, false, false);
    }

    private static CraftingSettings finiteSettings(int bank, int materials) {
        return new CraftingSettings(true, false, 10_000, false, false, false,
                true, List.of(), List.of(), List.of(), Map.of(200, bank), Map.of(200, materials));
    }
}
