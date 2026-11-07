package application;

import application.access.AccountGrant;
import application.access.AccountOperation;
import model.AccountScope;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import sync.AccountRefreshGateway;
import sync.AccountSync;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentTabsGrantRegressionTest {
    @Test
    void existingOperationsDoNotGainBuilds() {
        assertEquals(EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES),
                EnumSet.copyOf(AccountOperation.CHARACTERS_READ.requiredGrants()));
        for (AccountOperation op : AccountOperation.values()) {
            if (op.name().startsWith("CHARACTER_EQUIPMENT_TABS") || op.name().startsWith("CHARACTER_BUILD_TABS")) continue;
            assertFalse(op.requiredGrants().contains(AccountGrant.BUILDS), op.name());
        }
    }

    @Test
    void buildTabsOperationsMirrorEquipmentGrantsAndEndpoints() {
        assertEquals(Set.of(AccountGrant.ACCOUNT, AccountGrant.BUILDS, AccountGrant.CHARACTERS),
                AccountOperation.CHARACTER_BUILD_TABS_ENDPOINT.requiredGrants());
        assertEquals(Set.of("/v2/characters/:id/buildtabs"),
                AccountOperation.CHARACTER_BUILD_TABS_ENDPOINT.upstreamEndpoints());
        assertEquals(Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES,
                AccountGrant.BUILDS), AccountOperation.CHARACTER_BUILD_TABS_READ.requiredGrants());
        assertEquals(AccountOperation.CHARACTER_BUILD_TABS_READ,
                ScopedAccountRefreshCoordinator.operationFor(AccountSyncStateRepository.CHARACTER_BUILD_TABS));
    }

    @Test
    void endpointAttributesNoInventoriesAndWorkflowRequiresAllFour() {
        assertEquals(Set.of(AccountGrant.ACCOUNT, AccountGrant.BUILDS, AccountGrant.CHARACTERS),
                AccountOperation.CHARACTER_EQUIPMENT_TABS_ENDPOINT.requiredGrants());
        assertEquals(Set.of("/v2/characters/:id/equipmenttabs"),
                AccountOperation.CHARACTER_EQUIPMENT_TABS_ENDPOINT.upstreamEndpoints());
        assertEquals(Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS, AccountGrant.INVENTORIES,
                AccountGrant.BUILDS), AccountOperation.CHARACTER_EQUIPMENT_TABS_READ.requiredGrants());
        assertEquals(AccountOperation.CHARACTER_EQUIPMENT_TABS_READ,
                ScopedAccountRefreshCoordinator.operationFor(AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS));
    }

    @Test
    void fullAndOrdinaryRefreshNeverFetchEquipmentTabsAndDemandedTabsRunAlone() throws Exception {
        List<String> calls = new ArrayList<>();
        var gateway = new AccountRefreshGateway() {
            public void syncAccountBank(AccountScope s, AccountSync.AuthFetcher f) { calls.add("bank"); }
            public void syncAccountMaterials(AccountScope s, AccountSync.AuthFetcher f) { calls.add("materials"); }
            public void syncAccountRecipes(AccountScope s, AccountSync.AuthFetcher f) { calls.add("recipes"); }
            public void syncAccountLuck(AccountScope s, AccountSync.AuthFetcher f) { calls.add("luck"); }
            public void syncCharacterCraftingAndRecipes(AccountScope s, AccountSync.AuthFetcher f) { calls.add("characters"); }
            public void syncCharacterEquipmentTabs(AccountScope s, AccountSync.AuthFetcher f) { calls.add("tabs"); }
        };
        var service = new AccountRefreshService(gateway);
        var scope = AccountScope.of("10000000-0000-0000-0000-000000000001");
        service.refreshAll(scope, url -> null);
        assertFalse(calls.contains("tabs"));
        calls.clear();
        service.refreshSources(scope, Set.of(AccountSyncStateRepository.CHARACTERS), url -> null);
        assertEquals(List.of("characters"), calls);
        calls.clear();
        service.refreshSources(scope, Set.of(AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS), url -> null);
        assertEquals(List.of("tabs"), calls);
    }

    @Test
    void tabsFailureDoesNotBlockSeparatelyRequestedCharacters() throws Exception {
        List<String> calls = new ArrayList<>();
        var gateway = new AccountRefreshGateway() {
            public void syncCharacterCraftingAndRecipes(AccountScope s, AccountSync.AuthFetcher f) { calls.add("characters"); }
            public void syncCharacterEquipmentTabs(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
                throw new IllegalStateException("tabs down");
            }
        };
        var scope = AccountScope.of("10000000-0000-0000-0000-000000000001");
        assertThrows(IllegalStateException.class, () -> new AccountRefreshService(gateway).refreshSources(scope,
                Set.of(AccountSyncStateRepository.CHARACTERS, AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS),
                url -> null));
        assertEquals(List.of("characters"), calls);
    }
}
