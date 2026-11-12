package perf;

import infra.icons.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Default fast proof that the experiment's icon edge serves deterministic bytes without any network or disk store. */
class OfflineIconsTest {
    private static final String URL = "https://render.guildwars2.com/file/0123ABCD0123ABCD0123ABCD0123ABCD01234567/456789.png";

    @Test
    void realAcquisitionOverTheOfflineEdgeServesDeterministicBytesOnceAndNeverTouchesNetworkOrConfiguredStore() {
        OfflineIcons.OFFLINE_ACQUISITIONS.clear();
        OfflineIcons.MemoryStore store = new OfflineIcons.MemoryStore();
        IconAcquisition acquisition = new IconAcquisition(store, new OfflineIcons.OfflineFetcher());
        IconSource source = IconSourcePolicy.accept(URL).orElseThrow();
        for (int i = 0; i < 3; i++) {
            var r = acquisition.acquire(source);
            var available = assertInstanceOf(IconAcquisitionResult.Available.class, r);
            assertArrayEquals(OfflineIcons.PNG, available.icon().bytes());
            assertNull(available.icon().file(), "no filesystem path: nothing is written to the configured icon store");
        }
        assertEquals(1, OfflineIcons.OFFLINE_ACQUISITIONS.size(), "second and third requests are cache hits");
        assertEquals(source.sourceKey(), OfflineIcons.OFFLINE_ACQUISITIONS.get(0), "only the source key is recorded, never fetched from a URL");
        assertTrue(OfflineIcons.FIDELITY.contains("NOT representative"));
    }

    @Test
    void offlineConfigurationDoesNotConstructTheLiveFetcher() throws Exception {
        for (var m : OfflineIcons.class.getDeclaredClasses()) assertNotEquals(HttpIconImageFetcher.class, m);
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/java/perf/OfflineIcons.java"));
        assertFalse(src.contains("new HttpIconImageFetcher"), "the live fetcher is never constructed by the offline configuration");
        assertFalse(src.contains("ICON_CACHE_DIR"), "the configured user icon store is never used");
    }
}
