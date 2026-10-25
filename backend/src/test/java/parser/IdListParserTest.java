package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdListParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void parsesIdsInOrder() throws Exception {
        assertEquals(List.of(1, 2, 7319), IdListParser.parseIntArray(json("[1,2,7319]"), "recipes"));
    }

    @Test
    void emptyArrayYieldsEmptyList() throws Exception {
        assertTrue(IdListParser.parseIntArray(json("[]"), "recipes").isEmpty());
    }

    @Test
    void skipsNullsAndNonPositiveOrNonNumericEntries() throws Exception {
        List<Integer> ids = IdListParser.parseIntArray(json("[5,null,0,-4,\"abc\",{},9]"), "recipes");

        assertEquals(List.of(5, 9), ids);
    }

    @Test
    void nonArrayRootIsRejectedWithContext() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> IdListParser.parseIntArray(json("{\"id\":1}"), "character recipes"));
        assertTrue(ex.getMessage().contains("character recipes"));

        assertThrows(IllegalArgumentException.class, () -> IdListParser.parseIntArray(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> IdListParser.parseIntArray(MAPPER.nullNode(), "x"));
    }
}
