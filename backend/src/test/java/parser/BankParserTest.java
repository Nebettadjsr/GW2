package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import model.BankSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BankParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    private static void assertEmpty(BankSlot s, int slot) {
        assertEquals(new BankSlot(slot, null, null, null, null, null, null, null), s);
    }

    @Test
    void javaNullEntryIsEmptySlotKeepingIndex() {
        assertEmpty(BankParser.parseSlot(7, null), 7);
    }

    @Test
    void jsonNullEntryIsEmptySlotKeepingIndex() {
        assertEmpty(BankParser.parseSlot(0, NullNode.getInstance()), 0);
    }

    @Test
    void populatedSlotRetainsAllScalarFields() throws Exception {
        BankSlot s = BankParser.parseSlot(12, json(
                "{\"id\":24615,\"count\":3,\"binding\":\"Character\","
                        + "\"bound_to\":\"Some Character\",\"charges\":5}"));

        assertEquals(12, s.slot());
        assertEquals(24615, s.itemId());
        assertEquals(3, s.count());
        assertEquals("Character", s.binding());
        assertEquals("Some Character", s.boundTo());
        assertEquals(5, s.charges());
        assertNull(s.statsId());
        assertNull(s.statsAttrsJson());
    }

    @Test
    void nestedStatsIdAndAttributesAreParsed() throws Exception {
        BankSlot s = BankParser.parseSlot(3, json(
                "{\"id\":80248,\"count\":1,\"stats\":{\"id\":1234,"
                        + "\"attributes\":{\"Power\":120,\"Precision\":85,\"CritDamage\":85}}}"));

        assertEquals(3, s.slot());
        assertEquals(80248, s.itemId());
        assertEquals(1234, s.statsId());
        assertEquals(json("{\"CritDamage\":85,\"Power\":120,\"Precision\":85}"),
                json(s.statsAttrsJson()));
    }

    @Test
    void absentStatsLeavesStatsFieldsNull() throws Exception {
        BankSlot s = BankParser.parseSlot(4, json("{\"id\":19721,\"count\":250}"));

        assertEquals(new BankSlot(4, 19721, 250, null, null, null, null, null), s);
    }

    @Test
    void nullStatsLeavesStatsFieldsNull() throws Exception {
        BankSlot s = BankParser.parseSlot(5, json("{\"id\":19721,\"count\":2,\"stats\":null}"));

        assertEquals(new BankSlot(5, 19721, 2, null, null, null, null, null), s);
    }

    @Test
    void statsWithOnlyIdHasNoAttributes() throws Exception {
        BankSlot s = BankParser.parseSlot(6, json("{\"id\":1,\"count\":1,\"stats\":{\"id\":99}}"));

        assertEquals(99, s.statsId());
        assertNull(s.statsAttrsJson());
    }

    @Test
    void statsWithOnlyAttributesHasNoId() throws Exception {
        BankSlot s = BankParser.parseSlot(8, json(
                "{\"id\":1,\"count\":1,\"stats\":{\"attributes\":{\"Power\":10}}}"));

        assertNull(s.statsId());
        assertEquals(json("{\"Power\":10}"), json(s.statsAttrsJson()));
    }

    @Test
    void nullStatsIdAndNullAttributesAreTreatedAsAbsent() throws Exception {
        BankSlot s = BankParser.parseSlot(9, json(
                "{\"id\":1,\"count\":1,\"stats\":{\"id\":null,\"attributes\":null}}"));

        assertNull(s.statsId());
        assertNull(s.statsAttrsJson());
    }

    @Test
    void explicitNullOptionalFieldsAreNull() throws Exception {
        BankSlot s = BankParser.parseSlot(10, json(
                "{\"id\":2,\"count\":1,\"binding\":null,\"bound_to\":null,\"charges\":null}"));

        assertEquals(new BankSlot(10, 2, 1, null, null, null, null, null), s);
    }

    @Test
    void emptyObjectKeepsIndexAndHasNoFields() throws Exception {
        assertEmpty(BankParser.parseSlot(11, json("{}")), 11);
    }

    @Test
    void accountBoundItemHasBindingWithoutBoundCharacter() throws Exception {
        BankSlot s = BankParser.parseSlot(1, json("{\"id\":3,\"count\":9,\"binding\":\"Account\"}"));

        assertEquals("Account", s.binding());
        assertNull(s.boundTo());
        assertNull(s.charges());
    }
}
