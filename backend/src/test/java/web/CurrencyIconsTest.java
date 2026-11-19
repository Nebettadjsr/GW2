package web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #372: a currency image is only ever a same-origin application URL, never an upstream address. */
class CurrencyIconsTest {

    private final IntFunction<Optional<String>> original = CurrencyIcons.sources;

    @AfterEach
    void restore() {
        CurrencyIcons.sources = original;
        CurrencyIcons.reset();
    }

    @Test
    void retainedSourceBecomesAnApplicationRelativeCurrencyRoute() {
        CurrencyIcons.reset();
        CurrencyIcons.sources = id -> id == 61
                ? Optional.of("https://render.guildwars2.com/file/ABCDEF0123456789ABCDEF0123456789ABCDEF01/1234.png")
                : Optional.empty();
        String url = CurrencyIcons.urlFor(61);
        assertTrue(url.startsWith("/api/resources/currencies/61/icon/"), url);
        assertTrue(url.endsWith(".png"), url);
        assertNull(CurrencyIcons.urlFor(62));
    }

    @Test
    void anUnacceptableSourceYieldsNoUrl() {
        CurrencyIcons.reset();
        CurrencyIcons.sources = id -> Optional.of("http://evil.example/x.png");
        assertNull(CurrencyIcons.urlFor(61));
        assertEquals(null, CurrencyIcons.urlFor(0));
    }
}
