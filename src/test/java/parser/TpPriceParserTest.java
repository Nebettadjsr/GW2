package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import util.TpPrice;

import static org.junit.jupiter.api.Assertions.*;

class TpPriceParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void parsesQuoteWithBothSides() throws Exception {
        TpPrice p = TpPriceParser.parse(json("""
                {"id":19721,"buys":{"quantity":1200,"unit_price":55},"sells":{"quantity":300,"unit_price":60}}
                """));

        assertEquals(19721, p.itemId());
        assertEquals(1200L, p.buyQty());
        assertEquals(55, p.buyUnit());
        assertEquals(300L, p.sellQty());
        assertEquals(60, p.sellUnit());
        assertTrue(p.hasMarketData());
    }

    @Test
    void nullSidesMeanNoDataRatherThanFreeItem() throws Exception {
        TpPrice p = TpPriceParser.parse(json("{\"id\":123,\"buys\":null,\"sells\":null}"));

        assertEquals(123, p.itemId());
        assertFalse(p.hasMarketData());
        assertNull(p.buyUnit());
        assertNull(p.sellUnit());
        assertNull(p.buyQty());
        assertNull(p.sellQty());
    }

    @Test
    void onlyOneNullSideIsTreatedAsNoData() throws Exception {
        TpPrice noSells = TpPriceParser.parse(json(
                "{\"id\":5,\"buys\":{\"quantity\":1,\"unit_price\":10},\"sells\":null}"));
        TpPrice noBuys = TpPriceParser.parse(json(
                "{\"id\":6,\"buys\":null,\"sells\":{\"quantity\":1,\"unit_price\":10}}"));

        assertFalse(noSells.hasMarketData());
        assertNull(noSells.buyUnit());
        assertFalse(noBuys.hasMarketData());
        assertNull(noBuys.sellUnit());
    }

    @Test
    void missingSideFieldsMeanNoData() throws Exception {
        TpPrice p = TpPriceParser.parse(json("{\"id\":7}"));

        assertEquals(7, p.itemId());
        assertFalse(p.hasMarketData());
    }

    @Test
    void absentOrNullIdYieldsNoRecord() throws Exception {
        assertNull(TpPriceParser.parse(null));
        assertNull(TpPriceParser.parse(MAPPER.nullNode()));
        assertNull(TpPriceParser.parse(json("{\"buys\":{\"quantity\":1,\"unit_price\":2},\"sells\":{\"quantity\":1,\"unit_price\":3}}")));
        assertNull(TpPriceParser.parse(json("{\"id\":null,\"buys\":null,\"sells\":null}")));
    }

    @Test
    void parsesEachElementOfBatchResponseIndependently() throws Exception {
        JsonNode batch = json("""
                [{"id":1,"buys":{"quantity":10,"unit_price":100},"sells":{"quantity":20,"unit_price":120}},
                 {"id":2,"buys":null,"sells":null}]
                """);

        TpPrice first = TpPriceParser.parse(batch.get(0));
        TpPrice second = TpPriceParser.parse(batch.get(1));

        assertTrue(first.hasMarketData());
        assertEquals(120, first.sellUnit());
        assertEquals(2, second.itemId());
        assertFalse(second.hasMarketData());
    }
}
