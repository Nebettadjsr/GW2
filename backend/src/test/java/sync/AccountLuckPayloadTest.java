package sync;

import api.Gw2ApiClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountLuckPayloadTest {
    @Test
    void usesStableAccountIdAndRejectsMissingIdentity() {
        assertEquals("01AB-ACCOUNT-ID", AccountSync.parseAccountId(
                Gw2ApiClient.readJson("{\"id\":\"01AB-ACCOUNT-ID\",\"name\":\"Changed Name\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> AccountSync.parseAccountId(Gw2ApiClient.readJson("{\"name\":\"Only Name\"}")));
    }

    @Test
    void emptyPayloadMeansZeroAndOneLuckRecordIsAccepted() {
        assertEquals(0, AccountSync.parseConsumedLuck(Gw2ApiClient.readJson("[]")));
        assertEquals(4_295_449L, AccountSync.parseConsumedLuck(
                Gw2ApiClient.readJson("[{\"id\":\"luck\",\"value\":4295449}]")));
    }

    @Test
    void malformedOrNegativeValuesAreRejectedBeforePersistence() {
        for (String json : new String[]{"{}", "[{},{}]", "[{\"id\":\"other\",\"value\":1}]",
                "[{\"id\":\"luck\",\"value\":-1}]", "[{\"id\":\"luck\",\"value\":1.5}]"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AccountSync.parseConsumedLuck(Gw2ApiClient.readJson(json)), json);
        }
    }
}
