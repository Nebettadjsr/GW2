package api.tp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TpPriceApiTest {
    private TpHttpTestServer server;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new TpHttpTestServer();
        TpPriceApi.base = server.base();
        TpPriceApi.sleeper = sleeps::add;
    }

    @AfterEach
    void tearDown() {
        TpPriceApi.base = "https://api.guildwars2.com";
        TpPriceApi.sleeper = Thread::sleep;
        server.close();
    }

    @Test
    void batch200ReturnsArrayAndIds() throws Exception {
        server.reply(200, "[{\"id\":19721},{\"id\":19700},{\"x\":1},{\"id\":null}]");
        var r = TpPriceApi.fetchBatch(List.of(19721, 19700, 5));
        assertEquals(200, r.statusCode());
        assertEquals(Set.of(19721, 19700), r.returnedIds());
        assertEquals(4, r.array().size());
        assertEquals(List.of("/v2/commerce/prices?ids=19721,19700,5"), server.requests());
    }

    @Test
    void batch206IsAcceptedWithPartialIds() throws Exception {
        server.reply(206, "[{\"id\":7}]");
        var r = TpPriceApi.fetchBatch(List.of(7, 8));
        assertEquals(206, r.statusCode());
        assertEquals(Set.of(7), r.returnedIds());
    }

    @Test
    void batch404SignalsRetrySingleWithoutParsingBody() throws Exception {
        server.reply(404, "not json at all");
        var r = TpPriceApi.fetchBatch(List.of(1, 2));
        assertEquals(404, r.statusCode());
        assertNull(r.array());
        assertTrue(r.returnedIds().isEmpty());
    }

    @Test
    void batchWithNoPositiveIdsMakesNoRequest() throws Exception {
        var r = TpPriceApi.fetchBatch(List.of(0, -3));
        assertEquals(200, r.statusCode());
        assertNull(r.array());
        assertTrue(r.returnedIds().isEmpty());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void batchOtherStatusFailsWithStatusAndUrl() {
        server.reply(503, "down");
        var e = assertThrows(RuntimeException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
        assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
        assertTrue(e.getMessage().contains("/v2/commerce/prices?ids=1"), e.getMessage());
    }

    @Test
    void batchNonArrayJsonFails() {
        server.reply(200, "{\"id\":1}");
        var e = assertThrows(RuntimeException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
        assertTrue(e.getMessage().contains("not array"), e.getMessage());
    }

    @Test
    void batchMalformedJsonFails() {
        server.reply(200, "[{oops");
        var e = assertThrows(RuntimeException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
        assertEquals("Invalid JSON body", e.getMessage());
    }

    @Test
    void batchEmptyBodyIsNotAnArray() {
        server.reply(200, "");
        assertThrows(RuntimeException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
    }

    @Test
    void single200ReturnsObject() throws Exception {
        server.reply(200, "{\"id\":19721,\"buys\":{\"unit_price\":10}}");
        JsonNode n = TpPriceApi.fetchSingle(19721);
        assertEquals(19721, n.get("id").asInt());
        assertEquals(List.of("/v2/commerce/prices/19721"), server.requests());
    }

    @Test
    void single404ReturnsNull() throws Exception {
        server.reply(404, "{\"text\":\"no such id\"}");
        assertNull(TpPriceApi.fetchSingle(1));
    }

    @Test
    void single206AndOtherStatusesFail() {
        server.reply(206, "{}");
        var e = assertThrows(RuntimeException.class, () -> TpPriceApi.fetchSingle(1));
        assertTrue(e.getMessage().contains("HTTP 206"), e.getMessage());
        server.reply(500, "boom");
        var e2 = assertThrows(RuntimeException.class, () -> TpPriceApi.fetchSingle(1));
        assertTrue(e2.getMessage().contains("HTTP 500"), e2.getMessage());
    }

    @Test
    void retriesOn429HonoringRetryAfterThenSucceeds() throws Exception {
        server.reply(429, "", "3").reply(429, "", " 2 ").reply(200, "[{\"id\":9}]");
        var r = TpPriceApi.fetchBatch(List.of(9));
        assertEquals(Set.of(9), r.returnedIds());
        assertEquals(List.of(3000L, 2000L), sleeps);
        assertEquals(3, server.requests().size());
    }

    @Test
    void fallsBackToExponentialBackoffWhenRetryAfterMissingInvalidOrNonPositive() throws Exception {
        server.reply(429, "").reply(429, "", "soon").reply(429, "", "0").reply(200, "{\"id\":1}");
        assertNotNull(TpPriceApi.fetchSingle(1));
        assertEquals(List.of(1000L, 2000L, 4000L), sleeps);
    }

    @Test
    void exhaustedRetriesMakeOneFinalAttemptAndReturnItsResult() throws Exception {
        for (int i = 0; i < 4; i++) server.reply(429, "", "1");
        server.reply(200, "{\"id\":1}");
        // 4 attempts get 429 (3 retries slept after attempts 0..3), the final attempt is the 5th request
        assertNotNull(TpPriceApi.fetchSingle(1));
        assertEquals(List.of(1000L, 1000L, 1000L, 1000L), sleeps);
        assertEquals(5, server.requests().size());
    }

    @Test
    void persistent429FailsAsStatusErrorAfterBoundedAttempts() {
        for (int i = 0; i < 6; i++) server.reply(429, "limited");
        var e = assertThrows(RuntimeException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
        assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
        assertEquals(5, server.requests().size());
        assertEquals(4, sleeps.size());
    }

    @Test
    void interruptedBackoffPropagatesInterruptedException() {
        server.reply(429, "");
        TpPriceApi.sleeper = ms -> { throw new InterruptedException("stop"); };
        assertThrows(InterruptedException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
        assertEquals(1, server.requests().size());
    }

    @Test
    void transportFailurePropagatesAsIOException() {
        server.close();
        assertThrows(IOException.class, () -> TpPriceApi.fetchBatch(List.of(1)));
        assertThrows(IOException.class, () -> TpPriceApi.fetchSingle(1));
        assertThrows(IOException.class, TpPriceApi::fetchAllTradeableItemIds);
    }

    @Test
    void idListAcceptsIntsAndObjectsAndIgnoresOthers() throws Exception {
        server.reply(200, "[1,2,{\"id\":3},{\"id\":null},\"4\",null,{\"x\":5}]");
        assertEquals(Set.of(1, 2, 3), TpPriceApi.fetchAllTradeableItemIds());
        assertEquals(List.of("/v2/commerce/prices"), server.requests());
    }

    @Test
    void idListRejectsNon200NonArrayAndMalformed() {
        server.reply(206, "[1]");
        var e = assertThrows(RuntimeException.class, TpPriceApi::fetchAllTradeableItemIds);
        assertTrue(e.getMessage().contains("HTTP 206"), e.getMessage());
        server.reply(200, "{\"a\":1}");
        var e2 = assertThrows(RuntimeException.class, TpPriceApi::fetchAllTradeableItemIds);
        assertTrue(e2.getMessage().contains("not array"), e2.getMessage());
        server.reply(200, "[1,");
        assertThrows(RuntimeException.class, TpPriceApi::fetchAllTradeableItemIds);
    }
}
