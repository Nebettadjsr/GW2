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

    @Test
    void malformedBankSlotAfterValidSlotRejectsWholePayloadButNullSlotsAreEmpties() throws Exception {
        var m = new com.fasterxml.jackson.databind.ObjectMapper();
        var ok = AccountSync.parseBankSlots(m.readTree("[{\"id\":1,\"count\":2},null]"));
        org.junit.jupiter.api.Assertions.assertEquals(2, ok.size());
        for (String bad : new String[] {"[{\"id\":1,\"count\":2},{\"count\":5}]", "[{\"id\":1},\"x\"]", "[{\"id\":\"a\"}]"}) {
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> AccountSync.parseBankSlots(m.readTree(bad)), bad);
        }
    }

    @Test
    void bankCountsAndIdsMustBePresentPositiveAndRepresentable() {
        for (String slot : new String[]{"{\"id\":1}", "{\"id\":1,\"count\":null}",
                "{\"id\":1,\"count\":0}", "{\"id\":-1,\"count\":2}",
                "{\"id\":1,\"count\":4294967297}", "{\"id\":4294967297,\"count\":1}"}) {
            assertThrows(RuntimeException.class, () -> AccountSync.parseBankSlots(Gw2ApiClient.readJson(
                    "[{\"id\":2,\"count\":3}," + slot + "]")), slot);
        }
    }
}
