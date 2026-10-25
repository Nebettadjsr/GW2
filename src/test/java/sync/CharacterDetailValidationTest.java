package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.CharacterInfo;
import org.junit.jupiter.api.Test;
import parser.CharacterParser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CharacterDetailValidationTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode detail(String name, String extra) throws Exception {
        return M.readTree("{\"name\":\"" + name + "\",\"race\":\"Human\",\"gender\":\"Male\","
                + "\"profession\":\"Guardian\",\"level\":80,\"created\":\"2020-01-01T00:00:00Z\"" + extra + "}");
    }

    private static final String ALL = ",\"crafting\":[],\"recipes\":[],\"bags\":[],\"equipment\":[]";

    @Test
    void completeMatchingDetailIsAccepted() throws Exception {
        JsonNode n = detail("Aria", ALL);
        assertDoesNotThrow(() -> CharacterSync.requireConsistentDetail("Aria", CharacterParser.parseInfo(n), n));
    }

    @Test
    void detailForAnotherCharacterIsRejected() throws Exception {
        JsonNode n = detail("Other", ALL);
        assertThrows(IllegalStateException.class,
                () -> CharacterSync.requireConsistentDetail("Aria", CharacterParser.parseInfo(n), n));
    }

    @Test
    void detailMissingASectionIsRejected() throws Exception {
        JsonNode n = detail("Aria", ",\"crafting\":[],\"recipes\":[],\"bags\":[]");
        assertThrows(IllegalStateException.class,
                () -> CharacterSync.requireConsistentDetail("Aria", CharacterParser.parseInfo(n), n));
    }
}
