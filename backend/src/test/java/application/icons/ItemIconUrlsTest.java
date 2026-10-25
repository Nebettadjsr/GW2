package application.icons;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application-relative icon URL both sides of the contract use (TARGET_ARCHITECTURE.md §12.1,
 * STORY-API-009): what a read emits for retained metadata, and what the endpoint accepts back.
 *
 * <p>The round trip is asserted explicitly: a URL this class formats must parse back into the same item,
 * key and extension, since a drift between the two would turn every emitted URL into a 400.
 */
class ItemIconUrlsTest {

    private static final String SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String KEY = "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472";
    private static final String URL = "/api/items/19721/icon/" + KEY + ".png";

    // ---------- formatting ----------

    @Test
    void emitsAnApplicationRelativeUrlCarryingTheSourceKeyAndExtension() {
        assertEquals(URL, ItemIconUrls.iconUrlFor(19721, SOURCE));
    }

    @Test
    void keepsAJpgSourcesOwnExtension() {
        String jpgUrl = ItemIconUrls.iconUrlFor(
                19721, "https://render.guildwars2.com/file/FEDCBA9876543210/4321.jpg");

        assertTrue(jpgUrl.endsWith(".jpg"), "the extension follows the source, not a fixed default: " + jpgUrl);
    }

    @Test
    void neverEmitsAnUpstreamUrlOrAFilesystemPath() {
        String url = ItemIconUrls.iconUrlFor(19721, SOURCE);

        assertTrue(url.startsWith("/api/items/"), url);
        assertTrue(!url.contains("render.guildwars2.com") && !url.contains("http"), url);
    }

    @Test
    void anItemWithNoUsableMetadataHasNoUrl() {
        assertNull(ItemIconUrls.iconUrlFor(19721, null));
        assertNull(ItemIconUrls.iconUrlFor(19721, ""));
        assertNull(ItemIconUrls.iconUrlFor(19721, "https://cdn.example.com/file/ABCD/1.png"),
                "a rejected source must produce null, never a passed-through URL");
    }

    @Test
    void anEntryWithNoItemHasNoUrlEvenWithMetadata() {
        assertNull(ItemIconUrls.iconUrlFor(null, SOURCE), "an empty bank slot has no item image");
    }

    // ---------- parsing ----------

    @Test
    void parsesBackWhatItFormatted() {
        Optional<ItemIconUrls.ItemIconRequest> parsed = ItemIconUrls.parse("19721", KEY + ".png");

        assertTrue(parsed.isPresent());
        assertEquals(19721, parsed.get().itemId());
        assertEquals(KEY, parsed.get().sourceKey());
        assertEquals("png", parsed.get().extension());
    }

    @Test
    void rejectsAMalformedRoute() {
        assertTrue(ItemIconUrls.parse("abc", KEY + ".png").isEmpty(), "a non-numeric item id");
        assertTrue(ItemIconUrls.parse("0", KEY + ".png").isEmpty(), "a zero item id");
        assertTrue(ItemIconUrls.parse("-7", KEY + ".png").isEmpty(), "a negative item id");
        assertTrue(ItemIconUrls.parse("19721", KEY).isEmpty(), "no extension");
        assertTrue(ItemIconUrls.parse("19721", KEY + ".gif").isEmpty(), "an unsupported extension");
        assertTrue(ItemIconUrls.parse("19721", KEY.toUpperCase() + ".png").isEmpty(), "an uppercase key");
        assertTrue(ItemIconUrls.parse("19721", KEY.substring(1) + ".png").isEmpty(), "a short key");
        assertTrue(ItemIconUrls.parse("19721", "../../secret.png").isEmpty(), "a traversal attempt");
        assertTrue(ItemIconUrls.parse(null, KEY + ".png").isEmpty());
        assertTrue(ItemIconUrls.parse("19721", null).isEmpty());
    }
}
