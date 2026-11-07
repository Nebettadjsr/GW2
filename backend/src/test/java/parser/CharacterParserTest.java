package parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.CharacterInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterParserTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode doc(String age, String deaths) throws Exception {
        StringBuilder sb = new StringBuilder("{\"name\":\"Hero\",\"profession\":\"Engineer\",\"race\":\"Norn\",\"gender\":\"Male\",\"level\":80,\"created\":\"2020-01-01T00:00:00Z\"");
        if (age != null) sb.append(",\"age\":").append(age);
        if (deaths != null) sb.append(",\"deaths\":").append(deaths);
        return M.readTree(sb.append('}').toString());
    }

    @Test
    void retainsOrdinaryCounters() throws Exception {
        CharacterInfo i = CharacterParser.parseInfo(doc("12631700", "9193"));
        assertEquals(12631700L, i.ageSeconds());
        assertEquals(9193L, i.deaths());
    }

    @Test
    void zeroIsKnownZero() throws Exception {
        CharacterInfo i = CharacterParser.parseInfo(doc("0", "0"));
        assertEquals(0L, i.ageSeconds());
        assertEquals(0L, i.deaths());
    }

    @Test
    void keepsValuesBeyondInt32AndLongMax() throws Exception {
        CharacterInfo i = CharacterParser.parseInfo(doc("4294967296", String.valueOf(Long.MAX_VALUE)));
        assertEquals(4294967296L, i.ageSeconds());
        assertEquals(Long.MAX_VALUE, i.deaths());
    }

    @Test
    void rejectsMalformedCounters() throws Exception {
        for (String bad : new String[] {null, "null", "-1", "1.5", "2.0", "\"12\"", "9223372036854775808", "true"}) {
            assertThrows(IllegalStateException.class, () -> CharacterParser.parseInfo(doc(bad, "1")), "age " + bad);
            assertThrows(IllegalStateException.class, () -> CharacterParser.parseInfo(doc("1", bad)), "deaths " + bad);
        }
    }

    @Test
    void legacyConstructorLeavesCountersUnknown() {
        CharacterInfo i = new CharacterInfo("A", "B", "C", "D", 1, null);
        assertNull(i.ageSeconds());
        assertNull(i.deaths());
    }
}
