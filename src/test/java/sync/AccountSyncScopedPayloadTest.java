package sync;

import api.Gw2ApiClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A malformed entry must abort a scoped publication instead of being filtered into a false replacement. */
class AccountSyncScopedPayloadTest {
    @Test
    void wellFormedPayloadsParseCompletely() {
        assertEquals(2, AccountSync.parseAccountRecipeIds(Gw2ApiClient.readJson("[10,20]")).size());
        assertEquals(2, AccountSync.parseMaterialStacks(Gw2ApiClient.readJson(
                "[{\"id\":1,\"category\":5,\"count\":3},{\"id\":2,\"category\":5,\"count\":4}]")).size());
        assertTrue(AccountSync.parseMaterialStacks(Gw2ApiClient.readJson("[]")).isEmpty());
    }

    @Test
    void malformedRecipeIdRejectsWholePayload() {
        for (String json : new String[]{"[10,\"x\"]", "[10,0]", "[10,-4]", "{}", "null"}) {
            assertThrows(RuntimeException.class,
                    () -> AccountSync.parseAccountRecipeIds(Gw2ApiClient.readJson(json)), json);
        }
    }

    @Test
    void malformedMaterialEntryRejectsWholePayload() {
        for (String json : new String[]{
                "[{\"id\":1,\"category\":5,\"count\":3},{\"category\":5}]", "{}", "null"}) {
            assertThrows(RuntimeException.class,
                    () -> AccountSync.parseMaterialStacks(Gw2ApiClient.readJson(json)), json);
        }
    }
}
