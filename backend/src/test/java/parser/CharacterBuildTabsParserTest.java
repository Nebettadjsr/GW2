package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterBuildTabsParserTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception { return M.readTree(s); }

    private static final String SKILLS = "{\"heal\":null,\"utilities\":[5,null,7],\"elite\":9}";

    private static String tab(int n, boolean active, String extra) {
        return "{\"tab\":" + n + ",\"is_active\":" + active + ",\"build\":{\"name\":\"B" + n + "\",\"profession\":\"Revenant\","
                + "\"specializations\":[{\"id\":1,\"traits\":[10,null,12]},{\"id\":null,\"traits\":[null,null,null]},{\"id\":3,\"traits\":[null,null,null]}],"
                + "\"skills\":" + SKILLS + ",\"aquatic_skills\":" + SKILLS.replace("\"heal\":null", "\"heal\":4") + extra + "}}";
    }

    @Test
    void keepsPositionsNullsAndNumericOrderWithoutFabrication() throws Exception {
        var tabs = CharacterBuildTabsParser.parse(json("[" + tab(3, false, "") + ","
                + tab(1, true, ",\"legends\":[\"Legend1\",null],\"aquatic_legends\":[null,\"Legend4\"]") + "]"));
        assertEquals(List.of(1, 3), tabs.stream().map(CharacterBuildTabsParser.Tab::number).toList());
        assertTrue(tabs.get(0).active());
        var b = tabs.get(0).build();
        assertEquals("B1", b.name());
        assertEquals(3, b.specializations().size());
        assertEquals(Arrays.asList(10, null, 12), b.specializations().get(0).traits());
        assertNull(b.specializations().get(1).id(), "unselected position is kept, not dropped or zeroed");
        assertEquals(Arrays.asList(null, null, null), b.specializations().get(1).traits());
        assertNull(b.skills().heal());
        assertEquals(Arrays.asList(5, null, 7), b.skills().utilities());
        assertEquals(4, b.aquaticSkills().heal());
        assertEquals(Arrays.asList("Legend1", null), b.legends());
        assertEquals(Arrays.asList(null, "Legend4"), b.aquaticLegends());
        assertNull(b.pets(), "absent pets stay absent");
        assertNull(tabs.get(1).build().legends());
    }

    @Test
    void keepsRangerPetPositionsIncludingEmptyOnes() throws Exception {
        var tabs = CharacterBuildTabsParser.parse(json("[" + tab(1, true,
                ",\"pets\":{\"terrestrial\":[42,null],\"aquatic\":[null,null]}") + "]"));
        assertEquals(Arrays.asList(42, null), tabs.get(0).build().pets());
        assertEquals(Arrays.asList(null, null), tabs.get(0).build().aquaticPets());
    }

    @Test
    void acceptsDocumentedUnselectedSpecializationObjectAndExplicitNullSlots() throws Exception {
        var tabs = CharacterBuildTabsParser.parse(json("[" + tab(1, true, "") + "]"));
        var unselected = tabs.get(0).build().specializations().get(1);
        assertNull(unselected.id());
        assertEquals(Arrays.asList(null, null, null), unselected.traits());
        assertNull(tabs.get(0).build().skills().heal(), "explicit null heal is a documented empty slot");
    }

    @Test
    void emptyResponseIsValid() throws Exception {
        assertTrue(CharacterBuildTabsParser.parse(json("[]")).isEmpty());
    }

    @Test
    void rejectsMalformedAndContradictoryResponses() throws Exception {
        String ok = tab(1, true, "");
        String[] bad = {
                "{}", "[{\"tab\":0,\"is_active\":true,\"build\":{}}]",
                "[" + tab(1, true, "") + "," + tab(1, false, "") + "]",
                "[" + tab(1, false, "") + "]",
                "[" + tab(1, true, "") + "," + tab(2, true, "") + "]",
                "[{\"tab\":1,\"is_active\":true}]",
                ok.replace("[{\"id\":1,\"traits\":[10,null,12]},{\"id\":null,\"traits\":[null,null,null]},{\"id\":3,\"traits\":[null,null,null]}]", "[null,null]"),
                ok.replace("\"traits\":[10,null,12]", "\"traits\":[10,12]"),
                ok.replace("\"utilities\":[5,null,7]", "\"utilities\":[5,7]"),
                ok.replace("\"elite\":9", "\"elite\":0"),
                ok.replace("\"id\":1,", "\"id\":\"1\","),
                "[" + tab(1, true, ",\"legends\":[\"Legend1\"]") + "]",
                "[" + tab(1, true, ",\"legends\":[1,2]") + "]",
                "[" + tab(1, true, ",\"pets\":{\"terrestrial\":[0,1],\"aquatic\":[null,null]}") + "]",
                "[" + tab(1, true, ",\"pets\":{\"terrestrial\":[1],\"aquatic\":[null,null]}") + "]"};
        String spec = "{\"id\":null,\"traits\":[null,null,null]}";
        String[] more = {
                ok.replace(spec, "null"),                                   // bare null is not the documented shape
                ok.replace(spec, "{\"id\":null,\"traits\":[1,null,null]}"), // contradictory
                ok.replace(spec, "{\"traits\":[null,null,null]}"),          // id missing
                ok.replace(spec, "{\"id\":null}"),                          // traits missing
                ok.replace("\"heal\":null,", ""),                           // heal missing
                ok.replace("\"name\":\"B1\",", ""),                          // name missing
                ok.replace("\"profession\":\"Revenant\",", ""),              // profession missing
                "[" + tab(1, true, ",\"pets\":{\"terrestrial\":[1,null]}") + "]",   // aquatic pets missing
                "[" + tab(1, true, ",\"pets\":{\"aquatic\":[1,null]}") + "]"};      // terrestrial pets missing
        for (String b : more) {
            assertThrows(IllegalStateException.class, () -> CharacterBuildTabsParser.parse(json(b)), b);
        }
        for (String b : bad) {
            assertThrows(IllegalStateException.class, () -> CharacterBuildTabsParser.parse(json(b)), b);
        }
        assertNull(CharacterBuildTabsParser.parse(json("[" + ok.replace(",\"elite\":9", "") + "]"))
                .getFirst().build().skills().elite());
    }
}
