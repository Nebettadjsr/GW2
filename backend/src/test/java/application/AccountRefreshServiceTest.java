package application;

import model.AccountScope;
import org.junit.jupiter.api.Test;
import sync.AccountRefreshGateway;
import sync.AccountSync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application-layer orchestration tests for {@link AccountRefreshService}'s account-scoped
 * sequence: a fake {@link AccountRefreshGateway} replaces the live GW2 API and database, so these run
 * without HTTP or PostgreSQL. They prove source selection, the fixed order, that the supplied
 * scope and credential reach every step, and that one failing source neither stops the others nor
 * is swallowed.
 */
class AccountRefreshServiceTest {

    private static final AccountScope SCOPE = AccountScope.of("10000000-0000-0000-0000-000000000001");
    private static final AccountSync.AuthFetcher FETCHER = url -> null;

    @Test
    void refreshAllRunsTheFiveStandardSourcesInOrderWithoutOptionalTabs() throws Exception {
        RecordingGateway gateway = new RecordingGateway();

        new AccountRefreshService(gateway).refreshAll(SCOPE, FETCHER);

        assertEquals(List.of("bank", "materials", "recipes", "luck", "characters"), gateway.calls);
        assertEquals(Set.of(SCOPE), gateway.scopes);
        assertEquals(Set.of(FETCHER), gateway.fetchers);
    }

    @Test
    void onlyRequestedSourcesRunAndOptionalTabsOnlyWhenDemanded() throws Exception {
        RecordingGateway gateway = new RecordingGateway();

        new AccountRefreshService(gateway).refreshSources(SCOPE,
                Set.of("LUCK", "MATERIALS", "CHARACTER_BUILD_TABS", "CHARACTER_EQUIPMENT_TABS"), FETCHER);

        assertEquals(List.of("materials", "luck", "equipmentTabs", "buildTabs"), gateway.calls);
    }

    @Test
    void failingSourceDoesNotStopLaterSourcesAndTheFirstFailureCarriesTheRest() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.failures.put("materials", new IOException("materials down"));
        gateway.failures.put("luck", new IllegalStateException("luck down"));
        Map<String, String> outcomes = new LinkedHashMap<>();

        Exception thrown = assertThrows(IOException.class, () -> new AccountRefreshService(gateway)
                .refreshSources(SCOPE, Set.of("BANK", "MATERIALS", "ACCOUNT_RECIPES", "LUCK", "CHARACTERS"), FETCHER,
                        new AccountRefreshService.SourceOutcomes() {
                            public void succeeded(String source) { outcomes.put(source, "ok"); }
                            public void failed(String source) { outcomes.put(source, "failed"); }
                        }));

        assertSame(gateway.failures.get("materials"), thrown);
        assertEquals(List.of(gateway.failures.get("luck")), List.of(thrown.getSuppressed()));
        assertEquals(List.of("bank", "recipes", "characters"), gateway.calls);
        assertEquals(Map.of("BANK", "ok", "MATERIALS", "failed", "ACCOUNT_RECIPES", "ok", "LUCK", "failed",
                "CHARACTERS", "ok"), outcomes);
    }

    @Test
    void emptyDemandCallsNothing() throws Exception {
        RecordingGateway gateway = new RecordingGateway();

        new AccountRefreshService(gateway).refreshSources(SCOPE, Set.of(), FETCHER);

        assertTrue(gateway.calls.isEmpty());
    }

    private static class RecordingGateway extends AccountRefreshGateway {
        final List<String> calls = new ArrayList<>();
        final Set<AccountScope> scopes = new java.util.HashSet<>();
        final Set<AccountSync.AuthFetcher> fetchers = new java.util.HashSet<>();
        final Map<String, Exception> failures = new LinkedHashMap<>();

        private void step(String name, AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
            scopes.add(scope);
            fetchers.add(fetcher);
            Exception failure = failures.get(name);
            if (failure != null) throw failure;
            calls.add(name);
        }

        @Override public void syncAccountBank(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("bank", s, f); }
        @Override public void syncAccountMaterials(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("materials", s, f); }
        @Override public void syncAccountRecipes(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("recipes", s, f); }
        @Override public void syncAccountLuck(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("luck", s, f); }
        @Override public void syncCharacterCraftingAndRecipes(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("characters", s, f); }
        @Override public void syncCharacterEquipmentTabs(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("equipmentTabs", s, f); }
        @Override public void syncCharacterBuildTabs(AccountScope s, AccountSync.AuthFetcher f) throws Exception { step("buildTabs", s, f); }
    }
}
