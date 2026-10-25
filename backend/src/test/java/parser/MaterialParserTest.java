package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.MaterialStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaterialParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void parsesMaterialEntryWithBinding() throws Exception {
        MaterialStack m = MaterialParser.parse(json(
                "{\"id\":19721,\"category\":5,\"binding\":\"Account\",\"count\":250}"));

        assertEquals(new MaterialStack(19721, 5, 250, "Account"), m);
    }

    @Test
    void entryWithoutBindingHasNullBinding() throws Exception {
        MaterialStack m = MaterialParser.parse(json("{\"id\":19721,\"category\":5,\"count\":3}"));

        assertNotNull(m);
        assertNull(m.binding());
        assertEquals(3, m.count());
    }

    @Test
    void explicitNullBindingIsNotTheTextNull() throws Exception {
        MaterialStack m = MaterialParser.parse(json("{\"id\":1,\"category\":2,\"count\":3,\"binding\":null}"));

        assertNull(m.binding());
    }

    @Test
    void missingCategoryAndCountDefaultToZero() throws Exception {
        MaterialStack m = MaterialParser.parse(json("{\"id\":42}"));

        assertEquals(new MaterialStack(42, 0, 0, null), m);
    }

    @Test
    void absentZeroOrNegativeIdYieldsNoStack() throws Exception {
        assertNull(MaterialParser.parse(null));
        assertNull(MaterialParser.parse(MAPPER.nullNode()));
        assertNull(MaterialParser.parse(json("{\"category\":5,\"count\":10}")));
        assertNull(MaterialParser.parse(json("{\"id\":0,\"count\":10}")));
        assertNull(MaterialParser.parse(json("{\"id\":-3,\"count\":10}")));
        assertNull(MaterialParser.parse(json("{\"id\":null,\"count\":10}")));
    }
}
