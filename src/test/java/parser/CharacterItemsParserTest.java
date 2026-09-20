package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.CharacterItemRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CharacterItemsParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseBagsExtractsPopulatedSlotAndSkipsEmptySlot() throws Exception {
        JsonNode bags = MAPPER.readTree("""
            [
              {
                "id": 12345,
                "size": 20,
                "inventory": [
                  { "id": 19721, "count": 5, "binding": "Character", "bound_to": "Testchar" },
                  null
                ]
              }
            ]
            """);

        List<CharacterItemRow> rows = CharacterItemsParser.parseBags(bags);

        assertEquals(1, rows.size());
        CharacterItemRow row = rows.get(0);
        assertEquals("BAG", row.location());
        assertEquals(0, row.bagIndex());
        assertEquals(0, row.slotIndex());
        assertNull(row.equipmentSlot());
        assertEquals(19721, row.itemId());
        assertEquals(5, row.count());
        assertEquals("Character", row.binding());
        assertEquals("Testchar", row.boundTo());
    }

    @Test
    void parseEquipmentExtractsSlotEntry() throws Exception {
        JsonNode equipment = MAPPER.readTree("""
            [
              { "id": 48050, "slot": "Helm", "binding": "Character", "bound_to": "Testchar" }
            ]
            """);

        List<CharacterItemRow> rows = CharacterItemsParser.parseEquipment(equipment);

        assertEquals(1, rows.size());
        CharacterItemRow row = rows.get(0);
        assertEquals("EQUIPMENT", row.location());
        assertNull(row.bagIndex());
        assertNull(row.slotIndex());
        assertEquals("Helm", row.equipmentSlot());
        assertEquals(48050, row.itemId());
        assertNull(row.count());
        assertEquals("Character", row.binding());
        assertEquals("Testchar", row.boundTo());
    }

    @Test
    void parseBagsSkipsNullBagAndReturnsEmptyForMissingArray() {
        assertEquals(0, CharacterItemsParser.parseBags(null).size());
        assertEquals(0, CharacterItemsParser.parseEquipment(null).size());
    }
}
