package parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemCraftingFlagsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void distinguishesExplicitFlagsFromUnknownFlags() throws Exception {
        assertEquals(true, ItemParser.parse(json.readTree("""
                {"id":20017,"flags":["AccountBound","NoMysticForge"]}
                """)).noMysticForge());
        assertEquals(false, ItemParser.parse(json.readTree("""
                {"id":19997,"flags":["AccountBound"]}
                """)).noMysticForge());
        assertEquals(false, ItemParser.parse(json.readTree("""
                {"id":19997,"flags":[]}
                """)).noMysticForge());
        assertNull(ItemParser.parse(json.readTree("{\"id\":1}")).noMysticForge());
        assertNull(ItemParser.parse(json.readTree("{\"id\":1,\"flags\":false}")).noMysticForge());
        assertNull(ItemParser.parse(json.readTree("{\"id\":1,\"flags\":[\"NoMysticForge\",7]}")).noMysticForge());
    }
}
