package application;

import org.junit.jupiter.api.Test;
import sync.AccountRefreshGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Application-layer orchestration tests (TARGET_ARCHITECTURE.md §25, STORY-APP-004) for
 * {@link AccountRefreshService}: a fake {@link AccountRefreshGateway} replaces the live
 * GW2 API/database calls, so these run without HTTP or PostgreSQL. Proves the exact
 * pre-extraction call order (bank, materials, recipes, then character crafting/recipes) and that a
 * failure from any step propagates immediately and short-circuits the remaining steps - the same
 * behavior the removed {@code Gw2App} inline sequence had.
 *
 * <p>STORY-APP-008 added the narrower {@code refreshMaterialsAndRecipes()} sequence taken verbatim
 * from {@code CraftingProfitView}'s auto-refresh timer; its tests prove it keeps that view's own
 * materials-then-recipes order and, in particular, still does <em>not</em> sync the bank or
 * characters.
 */
class AccountRefreshServiceTest {

    @Test
    void refreshAllInvokesEachStepInTheExistingOrder() throws Exception {
        var gateway = new RecordingGateway();
        var service = new AccountRefreshService(gateway);

        service.refreshAll();

        assertEquals(
                List.of("bank", "materials", "recipes", "characters"),
                gateway.calls);
    }

    @Test
    void bankFailureShortCircuitsMaterialsRecipesAndCharacters() {
        var gateway = new RecordingGateway();
        gateway.bankFailure = new IOException("simulated bank fetch failure");
        var service = new AccountRefreshService(gateway);

        Exception thrown = assertThrows(IOException.class, service::refreshAll);

        assertSame(gateway.bankFailure, thrown);
        assertEquals(List.of(), gateway.calls);
    }

    @Test
    void materialsFailureShortCircuitsRecipesAndCharactersButBankAlreadyRan() {
        var gateway = new RecordingGateway();
        gateway.materialsFailure = new RuntimeException("simulated materials fetch failure");
        var service = new AccountRefreshService(gateway);

        Exception thrown = assertThrows(RuntimeException.class, service::refreshAll);

        assertSame(gateway.materialsFailure, thrown);
        assertEquals(List.of("bank"), gateway.calls);
    }

    @Test
    void characterFailurePropagatesAfterEveryAccountStepRan() {
        var gateway = new RecordingGateway();
        gateway.charactersFailure = new RuntimeException("simulated character sync failure");
        var service = new AccountRefreshService(gateway);

        Exception thrown = assertThrows(RuntimeException.class, service::refreshAll);

        assertSame(gateway.charactersFailure, thrown);
        assertEquals(List.of("bank", "materials", "recipes"), gateway.calls);
    }

    @Test
    void refreshMaterialsAndRecipesSyncsOnlyThoseTwoStepsInOrder() throws Exception {
        var gateway = new RecordingGateway();
        var service = new AccountRefreshService(gateway);

        service.refreshMaterialsAndRecipes();

        assertEquals(List.of("materials", "recipes"), gateway.calls);
    }

    @Test
    void refreshMaterialsAndRecipesShortCircuitsRecipesWhenMaterialsFail() {
        var gateway = new RecordingGateway();
        gateway.materialsFailure = new RuntimeException("simulated materials fetch failure");
        var service = new AccountRefreshService(gateway);

        Exception thrown = assertThrows(RuntimeException.class, service::refreshMaterialsAndRecipes);

        assertSame(gateway.materialsFailure, thrown);
        assertEquals(List.of(), gateway.calls);
    }

    @Test
    void refreshMaterialsAndRecipesPropagatesRecipesFailureAfterMaterialsRan() {
        var gateway = new RecordingGateway();
        gateway.recipesFailure = new IOException("simulated recipes fetch failure");
        var service = new AccountRefreshService(gateway);

        Exception thrown = assertThrows(IOException.class, service::refreshMaterialsAndRecipes);

        assertSame(gateway.recipesFailure, thrown);
        assertEquals(List.of("materials"), gateway.calls);
    }

    private static class RecordingGateway extends AccountRefreshGateway {
        final List<String> calls = new ArrayList<>();
        Exception bankFailure;
        Exception materialsFailure;
        Exception recipesFailure;
        Exception charactersFailure;

        @Override
        public void syncAccountBank() throws Exception {
            if (bankFailure != null) throw bankFailure;
            calls.add("bank");
        }

        @Override
        public void syncAccountMaterials() throws Exception {
            if (materialsFailure != null) throw materialsFailure;
            calls.add("materials");
        }

        @Override
        public void syncAccountRecipes() throws Exception {
            if (recipesFailure != null) throw recipesFailure;
            calls.add("recipes");
        }

        @Override
        public void syncCharacterCraftingAndRecipes() throws Exception {
            if (charactersFailure != null) throw charactersFailure;
            calls.add("characters");
        }
    }
}
