package application.icons;

import infra.icons.FilesystemIconStore;
import infra.icons.IconAcquisition;
import infra.icons.IconFetchResult;
import infra.icons.IconImageFetcher;
import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import model.BuildResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.BuildMetadataRepository;
import web.BuildResourceIconApiController;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP route of the typed resource image endpoint on the real delivery, store and acquisition seams. */
class ResourceIconRouteTest {
    private static final String SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String KEY = IconSourcePolicy.accept(SOURCE).orElseThrow().sourceKey();

    @TempDir Path root;

    private final AtomicInteger upstream = new AtomicInteger();

    private MockMvc mvc() {
        var repo = new BuildMetadataRepository() {
            @Override public Optional<String> findIconSource(BuildResourceType type, int id) {
                return Optional.ofNullable(Map.of("skills/7", SOURCE).get(type.segment() + "/" + id));
            }
            @Override public Optional<String> findDetailImageSource(BuildResourceType type, int id, String image) {
                return (type == BuildResourceType.SPECIALIZATIONS && id == 7 && image.equals("background"))
                        || (type == BuildResourceType.TRAITS && id == 7 && image.equals("fact-0"))
                        ? Optional.of(SOURCE) : Optional.empty();
            }
        };
        IconImageFetcher fetcher = (IconSource source) -> {
            upstream.incrementAndGet();
            return new IconFetchResult.Fetched(ImageFixtures.png());
        };
        var store = new FilesystemIconStore(root);
        var delivery = new IconDelivery(new repo.ItemIconMetadataRepository(), store, new IconAcquisition(store, fetcher), repo);
        return MockMvcBuilders.standaloneSetup(new BuildResourceIconApiController(delivery)).build();
    }

    @Test
    void backgroundAndFactImagesUseTheValidatedSharedCacheAndRejectUnknownRoles() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/api/resources/skills/7/background/" + KEY + ".png")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/traits/7/fact--1/" + KEY + ".png")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/specializations/7/arbitrary/" + KEY + ".png")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/traits/8/fact-0/" + KEY + ".png")).andExpect(status().isNotFound());
        assertEquals(0, upstream.get());
        mvc.perform(get("/api/resources/specializations/7/background/" + KEY + ".png")).andExpect(status().isOk());
        mvc.perform(get("/api/resources/traits/7/fact-0/" + KEY + ".png")).andExpect(status().isOk());
        assertEquals(1, upstream.get(), "the same public image is acquired once across roles");
    }

    @Test
    void missAcquiresOnceThenHitServesWithEtagAnd304AndNamespaceIsolation() throws Exception {
        MockMvc mvc = mvc();
        String url = "/api/resources/skills/7/icon/" + KEY + ".png";
        // cold cache, same numeric id in another namespace: not found, no upstream access
        mvc.perform(get("/api/resources/pets/7/icon/" + KEY + ".png")).andExpect(status().isNotFound());
        assertEquals(0, upstream.get());
        var first = mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png")).andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag);
        assertEquals(1, upstream.get());
        mvc.perform(get(url)).andExpect(status().isOk());
        mvc.perform(get(url).header(HttpHeaders.IF_NONE_MATCH, etag)).andExpect(status().isNotModified());
        assertEquals(1, upstream.get(), "hits and 304s do not acquire");

        int before = upstream.get();
        // unsupported type and malformed id: rejected before any lookup
        mvc.perform(get("/api/resources/legends/7/icon/" + KEY + ".png")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/skills/x/icon/" + KEY + ".png")).andExpect(status().isBadRequest());
        assertEquals(before, upstream.get());
    }
}
