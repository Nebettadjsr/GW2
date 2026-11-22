package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import util.TpPrice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Direct, database-free tests of {@link TpSync#strictQuote(JsonNode)}. */
class TpSyncStrictQuoteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SIDE = "{\"quantity\":5,\"unit_price\":100}";

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void assertMalformed(JsonNode quote) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TpSync.strictQuote(quote));
        assertEquals("tp quote is malformed", e.getMessage());
    }

    private static void assertMalformed(String text) {
        assertMalformed(json(text));
    }

    @Test
    void completeQuoteWithBothSidesIsAccepted() {
        TpPrice q = TpSync.strictQuote(json("{\"id\":7,\"buys\":{\"quantity\":5,\"unit_price\":100},"
                + "\"sells\":{\"quantity\":9,\"unit_price\":150}}"));
        assertEquals(new TpPrice(7, 5L, 100, 9L, 150), q);
    }

    @Test
    void zeroQuantityAndZeroPriceAreAccepted() {
        TpPrice q = TpSync.strictQuote(json("{\"id\":7,\"buys\":{\"quantity\":0,\"unit_price\":0},"
                + "\"sells\":{\"quantity\":0,\"unit_price\":0}}"));
        assertEquals(new TpPrice(7, 0L, 0, 0L, 0), q);
    }

    @Test
    void bothSidesNullIsStoredAsNoMarketData() {
        TpPrice q = TpSync.strictQuote(json("{\"id\":7,\"buys\":null,\"sells\":null}"));
        assertEquals(TpPrice.noData(7), q);
        assertFalse(q.hasMarketData());
    }

    @Test
    void nullInputIsRejected() {
        assertMalformed((JsonNode) null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "5", "true", "null"})
    void nonObjectIsRejected(String text) {
        assertMalformed(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"buys\":null,\"sells\":null}",
            "{\"id\":\"7\",\"buys\":null,\"sells\":null}",
            "{\"id\":7.5,\"buys\":null,\"sells\":null}",
            "{\"id\":null,\"buys\":null,\"sells\":null}",
            "{\"id\":9999999999,\"buys\":null,\"sells\":null}"
    })
    void missingOrNonIntegerIdIsRejected(String text) {
        assertMalformed(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void zeroOrNegativeIdIsRejected(String id) {
        assertMalformed("{\"id\":" + id + ",\"buys\":null,\"sells\":null}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":7}",
            "{\"id\":7,\"sells\":null}",
            "{\"id\":7,\"buys\":null}",
            "{\"id\":7,\"sells\":" + SIDE + "}",
            "{\"id\":7,\"buys\":" + SIDE + "}"
    })
    void absentBuysOrSellsFieldIsRejected(String text) {
        assertMalformed(text);
    }

    @Test
    void nullSideWhileTheOtherIsPresentIsRejected() {
        assertMalformed("{\"id\":7,\"buys\":null,\"sells\":" + SIDE + "}");
        assertMalformed("{\"id\":7,\"buys\":" + SIDE + ",\"sells\":null}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"unit_price\":100}",
            "{\"quantity\":5}",
            "{}",
            "{\"quantity\":-1,\"unit_price\":100}",
            "{\"quantity\":5,\"unit_price\":-1}",
            "{\"quantity\":\"5\",\"unit_price\":100}",
            "{\"quantity\":5,\"unit_price\":\"100\"}",
            "{\"quantity\":5.5,\"unit_price\":100}",
            "{\"quantity\":5,\"unit_price\":100.5}",
            "{\"quantity\":null,\"unit_price\":100}",
            "{\"quantity\":5,\"unit_price\":null}",
            "{\"quantity\":5,\"unit_price\":9999999999}",
            "[]",
            "\"text\"",
            "5"
    })
    void malformedSideIsRejectedOnEitherSide(String side) {
        assertMalformed("{\"id\":7,\"buys\":" + side + ",\"sells\":" + SIDE + "}");
        assertMalformed("{\"id\":7,\"buys\":" + SIDE + ",\"sells\":" + side + "}");
    }
}
