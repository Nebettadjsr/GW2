package sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #372: currency display metadata is parsed strictly; unusable records never become a currency row. */
class CurrencySyncTest {

    @Test
    void parsesNamedCurrenciesAndIgnoresRecordsWithoutAPositiveIdOrName() throws Exception {
        var root = new ObjectMapper().readTree("""
                [{"id":61,"name":"Research Note","icon":"https://render.guildwars2.com/file/x/1.png"},
                 {"id":3,"name":"No Icon"},
                 {"id":0,"name":"Zero"},{"id":-4,"name":"Negative"},{"name":"No id"},
                 {"id":"7","name":"Text id"},{"id":8,"name":""},{"id":9}]""");
        assertEquals(List.of(
                new CurrencySync.Currency(61, "Research Note", "https://render.guildwars2.com/file/x/1.png"),
                new CurrencySync.Currency(3, "No Icon", null)), CurrencySync.parse(root));
        assertEquals(List.of(), CurrencySync.parse(new ObjectMapper().readTree("{\"text\":\"error\"}")));
    }
}
