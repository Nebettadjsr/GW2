package web;

import application.icons.IconDelivery;
import application.icons.IconDeliveryResult;
import application.icons.ItemIconUrls;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the item-image endpoint (STORY-API-009, TARGET_ARCHITECTURE.md §12.1,
 * TEST_STRATEGY.md §11): the success and failure statuses, the exact caching headers, conditional
 * requests, and that the route is an asset endpoint rather than a file server.
 *
 * <p>The delivery boundary is replaced by a stub, so these exercise transport behavior only. What the
 * boundary itself decides - disk before metadata, metadata before upstream, publication before success -
 * is covered by {@code application.icons.IconDeliveryTest}.
 */
class ItemIconApiControllerTest {

    private static final String KEY = "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472";
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    private static final String ETAG = "\"c0ffee\"";

    private final List<String> deliveredRoutes = new ArrayList<>();

    private MockMvc mockMvcFor(IconDeliveryResult result) {
        return MockMvcBuilders.standaloneSetup(new ItemIconApiController(new StubIconDelivery(result))).build();
    }

    // ---------- success ----------

    @Test
    void servesTheBytesWithTheVerifiedTypeNosniffOneDayFreshnessAndAStrongEtag() throws Exception {
        MvcResult result = mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG))
                .perform(get("/api/items/19721/icon/" + KEY + ".png"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=86400, public"))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, IMAGE.length))
                .andReturn();

        assertArrayEquals(IMAGE, result.getResponse().getContentAsByteArray());
        assertNull(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertFalse(String.valueOf(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("immutable"),
                "freshness must stay finite so a repaired image can become visible after revalidation");
    }

    @Test
    void servesAJpegSourceUnderItsOwnType() throws Exception {
        mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/jpeg", ETAG))
                .perform(get("/api/items/19721/icon/" + KEY + ".jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"));
    }

    @Test
    void theRouteTheReadsEmitIsTheRouteThisEndpointServes() throws Exception {
        String emitted = ItemIconUrls.iconUrlFor(19721, "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png");

        mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG))
                .perform(get(emitted))
                .andExpect(status().isOk());

        assertEquals(List.of("19721/" + KEY + ".png"), deliveredRoutes,
                "the endpoint must receive exactly the item and file name the emitted URL carried");
    }

    // ---------- conditional requests ----------

    @Test
    void aMatchingIfNoneMatchIsA304ThatKeepsTheValidatorAndFreshness() throws Exception {
        MvcResult result = mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG))
                .perform(get("/api/items/19721/icon/" + KEY + ".png").header(HttpHeaders.IF_NONE_MATCH, ETAG))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=86400, public"))
                .andReturn();

        assertEquals(0, result.getResponse().getContentAsByteArray().length, "a 304 carries no body");
    }

    @Test
    void aMatchingEtagAmongSeveralOrAWildcardIsAlsoA304() throws Exception {
        mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG))
                .perform(get("/api/items/19721/icon/" + KEY + ".png")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"other\", " + ETAG))
                .andExpect(status().isNotModified());

        mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG))
                .perform(get("/api/items/19721/icon/" + KEY + ".png").header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified());
    }

    @Test
    void aStaleIfNoneMatchIsServedInFull() throws Exception {
        MvcResult result = mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG))
                .perform(get("/api/items/19721/icon/" + KEY + ".png")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"an-older-image\""))
                .andExpect(status().isOk())
                .andReturn();

        assertArrayEquals(IMAGE, result.getResponse().getContentAsByteArray());
    }

    // ---------- failures ----------

    @Test
    void aMalformedRouteIsABadRequestThatIsNotStored() throws Exception {
        mockMvcFor(new IconDeliveryResult.InvalidRequest("the icon route is malformed"))
                .perform(get("/api/items/abc/icon/" + KEY + ".png"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.error").value("ICON_REQUEST_INVALID"));
    }

    @Test
    void anUnknownImageIsANotFoundThatIsNotStored() throws Exception {
        mockMvcFor(new IconDeliveryResult.NotFound("no retained metadata"))
                .perform(get("/api/items/19721/icon/" + KEY + ".png"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.error").value("ICON_NOT_FOUND"));
    }

    @Test
    void aTemporaryFailureIsA503WithAFiniteRetryAfterAndIsNotStored() throws Exception {
        mockMvcFor(new IconDeliveryResult.Unavailable("upstream is down", 30))
                .perform(get("/api/items/19721/icon/" + KEY + ".png"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.error").value("ICON_UNAVAILABLE"));
    }

    @Test
    void noFailureResponseCarriesAPathAnUpstreamUrlOrALowerLayersMessage() throws Exception {
        String unavailable = mockMvcFor(new IconDeliveryResult.Unavailable(
                "C:\\icons\\assets-v1 is unwritable while fetching https://render.guildwars2.com/file/A/1.png", 30))
                .perform(get("/api/items/19721/icon/" + KEY + ".png"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(unavailable.contains("C:\\"), unavailable);
        assertFalse(unavailable.contains("render.guildwars2.com"), unavailable);
        assertFalse(unavailable.contains("assets-v1"), unavailable);

        String notFound = mockMvcFor(new IconDeliveryResult.NotFound(
                "no row in items for 19721 at C:\\icons"))
                .perform(get("/api/items/19721/icon/" + KEY + ".png"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(notFound.contains("C:\\"), notFound);
        assertFalse(notFound.contains("items for"), notFound);
    }

    // ---------- not a file server ----------

    @Test
    void thereIsNoListingOrGenericFilePathUnderTheRoute() throws Exception {
        MockMvc mockMvc = mockMvcFor(new IconDeliveryResult.Delivered(IMAGE, "image/png", ETAG));

        mockMvc.perform(get("/api/items/19721/icon/")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/items/19721/icon")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/items/19721/icon/" + KEY + ".png/extra")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/items/19721")).andExpect(status().isNotFound());

        assertTrue(deliveredRoutes.isEmpty(), "none of those paths may reach the delivery boundary");
    }

    // ---------- stub ----------

    /**
     * Stands in for the real boundary. Subclassing keeps the controller depending on the same concrete
     * type the application wires, and records what the route handed over.
     */
    private final class StubIconDelivery extends IconDelivery {
        private final IconDeliveryResult result;

        StubIconDelivery(IconDeliveryResult result) {
            super(null, null, null);
            this.result = result;
        }

        @Override
        public IconDeliveryResult deliver(String rawItemId, String fileName) {
            deliveredRoutes.add(rawItemId + "/" + fileName);
            return result;
        }
    }
}
