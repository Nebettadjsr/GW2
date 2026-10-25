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

    private static void rejected(String extra) throws Exception {
        JsonNode n = detail("Aria", extra);
        assertThrows(IllegalStateException.class,
                () -> CharacterSync.requireConsistentDetail("Aria", CharacterParser.parseInfo(n), n));
    }

    @Test
    void malformedNestedEntriesAreRejectedInsteadOfSkipped() throws Exception {
        rejected(",\"crafting\":[{\"discipline\":\"Cook\",\"rating\":400},{\"rating\":3}],\"recipes\":[],\"bags\":[],\"equipment\":[]");
        rejected(",\"crafting\":[],\"recipes\":[5,\"x\"],\"bags\":[],\"equipment\":[]");
        rejected(",\"crafting\":[],\"recipes\":[],\"bags\":[{\"inventory\":[{\"id\":1},{\"count\":2}]}],\"equipment\":[]");
        rejected(",\"crafting\":[],\"recipes\":[],\"bags\":[{\"size\":20}],\"equipment\":[]");
        rejected(",\"crafting\":[],\"recipes\":[],\"bags\":[],\"equipment\":[{\"id\":1,\"slot\":\"Helm\"},{\"id\":2}]");
    }

    @Test
    void validEmptyArraysAndEmptyInventorySlotsAreAccepted() throws Exception {
        JsonNode n = detail("Aria", ",\"crafting\":[{\"discipline\":\"Cook\",\"rating\":400,\"active\":true}],\"recipes\":[5],"
                + "\"bags\":[null,{\"inventory\":[null,{\"id\":1,\"count\":3}]}],\"equipment\":[{\"id\":2,\"slot\":\"Helm\"}]");
        assertDoesNotThrow(() -> CharacterSync.requireConsistentDetail("Aria", CharacterParser.parseInfo(n), n));
    }
}
