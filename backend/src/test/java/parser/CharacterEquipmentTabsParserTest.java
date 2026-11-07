package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterEquipmentTabsParserTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception { return M.readTree(s); }

    @Test
    void keepsNonContiguousTabsOptionalFieldsAndOrderedReferencesWithoutFabrication() throws Exception {
        var tabs = CharacterEquipmentTabsParser.parse(json("""
                [{"tab":3,"name":"Raid","is_active":false,"equipment":[]},
                 {"tab":1,"is_active":true,"equipment":[
                   {"id":100,"slot":"HelmAquatic","location":"Equipped","upgrades":[7,5],"infusions":[9,8],
                    "binding":"Account","bound_to":"x","stats":{"id":161,"attributes":{"Power":10,"Precision":4}}},
                   {"id":200}]}]"""));
        assertEquals(2, tabs.size());
        assertEquals(3, tabs.get(0).number());
        assertTrue(tabs.get(0).equipment().isEmpty());
        var tab = tabs.get(1);
        assertNull(tab.name());
        var full = tab.equipment().get(0);
        assertEquals(java.util.List.of(7, 5), full.upgrades());
        assertEquals(java.util.List.of(9, 8), full.infusions());
        assertEquals(161, full.statId());
        assertEquals(10, full.statAttributes().get("Power"));
        var bare = tab.equipment().get(1);
        assertNull(bare.slot());
        assertNull(bare.statId());
        assertNull(bare.statAttributes());
        assertNull(bare.binding());
        assertTrue(bare.upgrades().isEmpty());
    }

    @Test
    void emptyTabsIsValid() throws Exception {
        assertTrue(CharacterEquipmentTabsParser.parse(json("[]")).isEmpty());
    }

    @Test
    void rejectsMalformedAndContradictoryResponses() {
        for (String bad : new String[] {
                "{}",
                "[{\"tab\":0,\"is_active\":true,\"equipment\":[]}]",
                "[{\"tab\":\"1\",\"is_active\":true,\"equipment\":[]}]",
                "[{\"tab\":1,\"equipment\":[]}]",
                "[{\"tab\":1,\"is_active\":\"yes\",\"equipment\":[]}]",
                "[{\"tab\":1,\"is_active\":true}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":0}]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":1,\"upgrades\":[0]}]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":1,\"infusions\":[\"a\"]}]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":1,\"stats\":{\"attributes\":{}}}]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":1,\"stats\":{\"id\":2,\"attributes\":{\"Power\":\"x\"}}}]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[]},{\"tab\":1,\"is_active\":false,\"equipment\":[]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[]},{\"tab\":2,\"is_active\":true,\"equipment\":[]}]",
                "[{\"tab\":1,\"is_active\":true,\"equipment\":[{\"id\":1,\"slot\":\"Helm\"},{\"id\":2,\"slot\":\"Helm\"}]}]"}) {
            assertThrows(IllegalStateException.class, () -> CharacterEquipmentTabsParser.parse(json(bad)), bad);
        }
    }
}
