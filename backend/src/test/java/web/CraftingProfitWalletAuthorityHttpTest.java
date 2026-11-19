package web;

import application.CachedCraftingProfitService;
import application.CalculationFreshness;
import application.CraftingProfitService;
import application.ProfitInputSession;
import sync.TradingPostPriceRefreshGateway;
import application.TradingPostPriceRefreshService;
import application.TradingPostRefreshCoordinator;
import application.access.AccountContext;
import application.access.AccountGrant;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.AccountSourceStateRepository;
import repo.AccountSyncStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wallet read authority on the real HTTP route with the real use case: an ordinary session-authorized
 * request that presents <em>no raw GW2 key</em> reads the cached wallet exactly when the validated
 * {@link AccountContext} covers the wallet grant and endpoint, and a restricted context for the same
 * account never does. The key itself is never needed for the read and nothing is refreshed without it.
 */
class CraftingProfitWalletAuthorityHttpTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Set<AccountGrant> BASE = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
            AccountGrant.CHARACTERS, AccountGrant.UNLOCKS);

    private final List<Boolean> walletAuthorizedAtCalculation = new ArrayList<>();
    private final List<Set<String>> sourcesRead = new ArrayList<>();
    private final List<String> calculationIds = new ArrayList<>();
    private boolean walletPublished = true;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new CraftingProfitApiController(service(), new web.access.AccountAccessHttp("")))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private CachedCraftingProfitService service() {
        return new CachedCraftingProfitService(
                scope -> ProfitInputSession.detached(scope),
                session -> new CraftingProfitService(null, null, null, null, null, null, null, session) {
                    @Override public ProfitData reload(repo.DiscChoice choice, craft.CraftingSettings settings) {
                        walletAuthorizedAtCalculation.add(session.walletAuthorized());
                        return new ProfitData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
                    }
                },
                (con, scope, sources) -> {
                    sourcesRead.add(Set.copyOf(sources));
                    Map<String, AccountSourceStateRepository.SourceState> states = new LinkedHashMap<>();
                    for (String s : sources) {
                        if (AccountSyncStateRepository.WALLET.equals(s) && !walletPublished) continue;
                        states.put(s, new AccountSourceStateRepository.SourceState(s, 3L, NOW.minusSeconds(60)));
                    }
                    return states;
                },
                (con, scope) -> new CalculationFreshness.PriceCoverage(1, 1, 0, 0, NOW, NOW, true, "q"),
                new TradingPostRefreshCoordinator(new TradingPostPriceRefreshService(new TradingPostPriceRefreshGateway() {
                    @Override public void syncTpPricesForProfit() { }
                    @Override public void syncTpPricesForProfit(AccountScope scope) { }
                }), null),
                null, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
    }

    private CachedCraftingProfitService.KeyedAdmission keyedWithProof(AccountContext session, AccountContext proved) {
        var keyed = new CachedCraftingProfitService.KeyedAdmission(session, "sid", "key-not-used");
        keyed.proof().set(new application.access.AccountAccessService.KeyedProof(proved));
        return keyed;
    }

    @Test
    void aPresentedNarrowKeyNeverBorrowsTheWideSessionsWalletAuthority() throws Exception {
        AccountContext wide = context(withWallet(), Set.of());
        AccountContext narrow = new AccountContext(wide.accountId(), BASE, Set.of(), "ctx", NOW.plusSeconds(3600));
        var settings = new craft.CraftingSettings(true, false, 0, true, false, true);

        var outcome = service().profit(AccountScope.of(wide.accountId()), repo.DiscChoice.all(), settings,
                keyedWithProof(wide, narrow), wide);

        assertEquals(List.of(false), walletAuthorizedAtCalculation);
        assertFalse(sourcesRead.get(0).contains(AccountSyncStateRepository.WALLET), "no wallet marker or read");
        assertTrue(outcome.hasResult(), "the item-only result stays usable");
    }

    @Test
    void aPresentedKeyWhoseProvedScopeCoversTheWalletKeepsTheWalletOfAWalletAuthorizedSession() throws Exception {
        AccountContext wide = context(withWallet(), Set.of());
        var settings = new craft.CraftingSettings(true, false, 0, true, false, true);

        service().profit(AccountScope.of(wide.accountId()), repo.DiscChoice.all(), settings,
                keyedWithProof(wide, wide), wide);

        assertEquals(List.of(true), walletAuthorizedAtCalculation);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder keyless(AccountContext ctx) {
        return post("/api/crafting/profit").requestAttr(web.access.RequestAccountContext.ATTRIBUTE, ctx)
                .contentType("application/json").content("{}");
    }

    private static AccountContext context(Set<AccountGrant> grants, Set<String> endpoints) {
        return new AccountContext(UUID.randomUUID().toString(), grants, endpoints, "ctx", NOW.plusSeconds(3600));
    }

    private static Set<AccountGrant> withWallet() {
        Set<AccountGrant> grants = new java.util.HashSet<>(BASE);
        grants.add(AccountGrant.WALLET);
        return grants;
    }

    @Test
    void keylessReadWithAWalletAuthorizedContextUsesTheCachedWallet() throws Exception {
        mvc().perform(keyless(context(withWallet(), Set.of()))).andExpect(status().isOk());

        assertEquals(List.of(true), walletAuthorizedAtCalculation);
        assertTrue(sourcesRead.get(0).contains(AccountSyncStateRepository.WALLET));
    }

    @Test
    void keylessReadWithoutTheWalletGrantNeverSeesTheWalletOfThatSameAccount() throws Exception {
        mvc().perform(keyless(context(BASE, Set.of()))).andExpect(status().isOk());

        assertEquals(List.of(false), walletAuthorizedAtCalculation);
        assertFalse(sourcesRead.get(0).contains(AccountSyncStateRepository.WALLET),
                "the cached wallet marker is not even queried without authority");
    }

    @Test
    void anEndpointRestrictedKeyThatExcludesTheWalletCannotUseIt() throws Exception {
        mvc().perform(keyless(context(withWallet(), Set.of("/v2/account/bank")))).andExpect(status().isOk());

        assertEquals(List.of(false), walletAuthorizedAtCalculation);
    }

    @Test
    void anAbsentWalletPublicationStaysInspectableInsteadOfPending() throws Exception {
        walletPublished = false;

        mvc().perform(keyless(context(withWallet(), Set.of()))).andExpect(status().isOk());

        assertEquals(List.of(true), walletAuthorizedAtCalculation, "the calculation still ran with item-only rows");
    }
}
