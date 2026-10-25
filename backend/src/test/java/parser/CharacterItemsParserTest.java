package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.CharacterItemRow;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CharacterItemsParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode loadFixture(String resourceName) throws Exception {
        try (InputStream in = CharacterItemsParserTest.class.getResourceAsStream(resourceName)) {
            return MAPPER.readTree(in);
        }
    }

    // character_inventory_fixture.json is trimmed from a real captured GET /v2/characters/:name
    // response (STORY-TEST-009); see that story's Result for provenance and anonymization details.

    @Test
    void parseBagsExtractsUnboundAccountBoundAndSoulboundItemsFromCapturedApiPayload() throws Exception {
        JsonNode character = loadFixture("/parser/character_inventory_fixture.json");

        List<CharacterItemRow> rows = CharacterItemsParser.parseBags(character.get("bags"));

        assertEquals(4, rows.size());

        CharacterItemRow soulbound = rows.get(0);
        assertEquals("BAG", soulbound.location());
        assertEquals(0, soulbound.bagIndex());
        assertEquals(0, soulbound.slotIndex());
        assertNull(soulbound.equipmentSlot());
        assertEquals(19575, soulbound.itemId());
        assertEquals(2, soulbound.count());
        assertEquals("Character", soulbound.binding());
        assertEquals("Fixture Character One", soulbound.boundTo());

        CharacterItemRow accountBound = rows.get(1);
        assertEquals(0, accountBound.bagIndex());
        assertEquals(1, accountBound.slotIndex());
        assertEquals(97254, accountBound.itemId());
        assertEquals(1, accountBound.count());
        assertEquals("Account", accountBound.binding());
        assertNull(accountBound.boundTo());

        CharacterItemRow unbound = rows.get(2);
        assertEquals(0, unbound.bagIndex());
        assertEquals(2, unbound.slotIndex());
        assertEquals(9285, unbound.itemId());
        assertNull(unbound.binding());
        assertNull(unbound.boundTo());

        // bag 0's slot 3 is a null slot (empty) and is skipped; bag 1 contributes its own
        // unbound item at bagIndex 1, proving both null-slot skipping and multi-bag indexing.
        CharacterItemRow secondBagItem = rows.get(3);
        assertEquals(1, secondBagItem.bagIndex());
        assertEquals(0, secondBagItem.slotIndex());
        assertEquals(21683, secondBagItem.itemId());
        assertNull(secondBagItem.binding());
        assertNull(secondBagItem.boundTo());
    }

    @Test
    void parseEquipmentExtractsSoulboundAndAccountBoundEntriesFromCapturedApiPayload() throws Exception {
        JsonNode character = loadFixture("/parser/character_inventory_fixture.json");

        List<CharacterItemRow> rows = CharacterItemsParser.parseEquipment(character.get("equipment"));

        assertEquals(2, rows.size());

        CharacterItemRow soulbound = rows.get(0);
        assertEquals("EQUIPMENT", soulbound.location());
        assertNull(soulbound.bagIndex());
        assertNull(soulbound.slotIndex());
        assertEquals("HelmAquatic", soulbound.equipmentSlot());
        assertEquals(63602, soulbound.itemId());
        assertNull(soulbound.count());
        assertEquals("Character", soulbound.binding());
        assertEquals("Fixture Character One", soulbound.boundTo());

        CharacterItemRow accountBound = rows.get(1);
        assertEquals("Backpack", accountBound.equipmentSlot());
        assertEquals(72446, accountBound.itemId());
        assertEquals("Account", accountBound.binding());
        assertNull(accountBound.boundTo());
    }

    @Test
    void parseBagsSkipsNullBagAndReturnsEmptyForMissingArray() {
        assertEquals(0, CharacterItemsParser.parseBags(null).size());
        assertEquals(0, CharacterItemsParser.parseEquipment(null).size());
    }
}
