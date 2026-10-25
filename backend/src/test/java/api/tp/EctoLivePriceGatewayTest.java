package api.tp;

import craft.PriceQuote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EctoLivePriceGatewayTest {
    private TpHttpTestServer server;
    private EctoLivePriceGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new TpHttpTestServer();
        gateway = new EctoLivePriceGateway(HttpClient.newHttpClient(), server.base());
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        server.close();
    }

    @Test
    void parsesQuotesFor200() throws Exception {
        server.reply(200, "[{\"id\":19721,\"buys\":{\"unit_price\":100},\"sells\":{\"unit_price\":150}},"
                + "{\"id\":19700,\"buys\":{\"unit_price\":7},\"sells\":{\"unit_price\":9}}]");
        Map<Integer, PriceQuote> q = gateway.fetchQuotes(19721, 19700);
        assertEquals(2, q.size());
        assertEquals(100, q.get(19721).buyUnit);
        assertEquals(150, q.get(19721).sellUnit);
        assertEquals(7, q.get(19700).buyUnit);
        assertEquals(9, q.get(19700).sellUnit);
        assertEquals(List.of("/v2/commerce/prices?ids=19721,19700"), server.requests());
    }

    @Test
    void partial206IsAcceptedAndOmittedIdsAreAbsent() throws Exception {
        server.reply(206, "[{\"id\":1,\"buys\":{\"unit_price\":2},\"sells\":{\"unit_price\":3}}]");
        Map<Integer, PriceQuote> q = gateway.fetchQuotes(1, 2);
        assertEquals(Map.of(1, q.get(1)).keySet(), q.keySet());
        assertEquals(2, q.get(1).buyUnit);
    }

    @Test
    void entriesWithMissingOrNullSidesAreSkipped() throws Exception {
        server.reply(200, "[{\"id\":1,\"buys\":null,\"sells\":{\"unit_price\":3}},"
                + "{\"id\":2,\"buys\":{\"unit_price\":2},\"sells\":null},"
                + "{\"id\":3,\"sells\":{\"unit_price\":3}},"
                + "{\"id\":4,\"buys\":{\"unit_price\":2}},"
                + "{\"id\":5,\"buys\":{\"unit_price\":4},\"sells\":{\"unit_price\":6}}]");
        Map<Integer, PriceQuote> q = gateway.fetchQuotes(1, 2, 3, 4, 5);
        assertEquals(java.util.Set.of(5), q.keySet());
    }

    @Test
    void emptyArrayGivesEmptyMap() throws Exception {
        server.reply(200, "[]");
        assertTrue(gateway.fetchQuotes(1).isEmpty());
    }

    @Test
    void nonSuccessStatusesFailWithStatus() {
        for (int status : new int[]{404, 429, 500}) {
            server.reply(status, "x");
            var e = assertThrows(RuntimeException.class, () -> gateway.fetchQuotes(1));
            assertEquals("TP price fetch failed: HTTP " + status, e.getMessage());
        }
        assertEquals(3, server.requests().size()); // no retry on 429 in this gateway
    }

    @Test
    void nonArrayJsonFails() {
        server.reply(200, "{\"id\":1}");
        var e = assertThrows(RuntimeException.class, () -> gateway.fetchQuotes(1));
        assertEquals("Unexpected TP JSON", e.getMessage());
    }

    @Test
    void malformedJsonFailsWithParseException() {
        server.reply(200, "[{bad");
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> gateway.fetchQuotes(1));
    }

    @Test
    void entryWithoutIdFailsAsMalformedPayload() {
        server.reply(200, "[{\"buys\":{\"unit_price\":1},\"sells\":{\"unit_price\":2}}]");
        assertThrows(NullPointerException.class, () -> gateway.fetchQuotes(1));
    }

    @Test
    void transportFailurePropagatesAsIOException() {
        server.close();
        assertThrows(IOException.class, () -> gateway.fetchQuotes(1));
    }

    @Test
    void interruptionPropagatesAsInterruptedException() {
        server.reply(200, "[]");
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> gateway.fetchQuotes(1));
    }

    @Test
    void defaultConstructorIsUsable() {
        assertNotNull(new EctoLivePriceGateway());
    }
}
