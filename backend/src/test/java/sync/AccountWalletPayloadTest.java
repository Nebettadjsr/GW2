package sync;

import api.Gw2ApiClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountWalletPayloadTest {
    @Test
    void integerBalancesIncludingZeroAreAccepted() {
        assertEquals(Map.of(61, 210L, 4, 0L), AccountSync.parseWallet(Gw2ApiClient.readJson(
                "[{\"id\":61,\"value\":210},{\"id\":4,\"value\":0}]")));
    }

    @Test
    void fractionalNegativeDuplicateOrMalformedEntriesRejectWholePayload() {
        for (String json : new String[]{
                "[{\"id\":61,\"value\":1.5}]", "[{\"id\":61,\"value\":-0.5}]",
                "[{\"id\":4,\"value\":3},{\"id\":61,\"value\":1.5}]", "[{\"id\":61,\"value\":-1}]",
                "[{\"id\":61,\"value\":1},{\"id\":61,\"value\":2}]", "[{\"id\":61}]", "{}"}) {
            assertThrows(RuntimeException.class,
                    () -> AccountSync.parseWallet(Gw2ApiClient.readJson(json)), json);
        }
    }
}
