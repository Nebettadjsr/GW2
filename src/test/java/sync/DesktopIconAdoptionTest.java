package sync;

import infra.icons.FilesystemIconStore;
import infra.icons.IconAcquisition;
import infra.icons.IconFetchResult;
import infra.icons.IconImageFetcher;
import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desktop side of the shared cache (TARGET_ARCHITECTURE.md §12.1, STORY-API-009 acceptance
 * criterion 6): that the explicit icon download publishes through the same keyed adapter the web
 * endpoint reads, what it points {@code items.icon_path} at, and how legacy files are treated.
 *
 * <p>Disposable cache root and a controlled fetcher, so nothing here downloads an image, opens a
 * database connection or needs the JavaFX desktop.
 */
class DesktopIconAdoptionTest {

    private static final String SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final IconSource ACCEPTED = IconSourcePolicy.accept(SOURCE).orElseThrow();
    private static final int ITEM_ID = 19721;

    @TempDir
    Path cacheRoot;

    private static final class CountingFetcher implements IconImageFetcher {
        private final IconFetchResult result;
        final AtomicInteger calls = new AtomicInteger();

        CountingFetcher(IconFetchResult result) {
            this.result = result;
        }

        @Override
        public IconFetchResult fetch(IconSource source) {
            calls.incrementAndGet();
            return result;
        }
    }

    private DesktopIconAdoption adoptionWith(CountingFetcher fetcher, FilesystemIconStore store) {
        return new DesktopIconAdoption(cacheRoot, store, new IconAcquisition(store, fetcher));
    }

    // ---------- shared publication ----------

    @Test
    void publishesTheCanonicalImageIntoTheKeyedCacheAndNamesThatFileForJavaFx() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] canonical = png(Color.RED);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(canonical));

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store).adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.PUBLISHED_FROM_UPSTREAM, adoption.outcome());
        assertEquals(cacheRoot.resolve("assets-v1").resolve(ACCEPTED.sourceKey() + ".png"), adoption.file(),
                "the desktop path must name the shared keyed entry, not a private per-item file");
        assertArrayEquals(canonical, Files.readAllBytes(adoption.file()));
        assertArrayEquals(canonical, store.find(ACCEPTED.sourceKey(), "png").orElseThrow().bytes(),
                "the web endpoint must see the same entry the desktop sync just wrote");
    }

    @Test
    void anEntryTheWebEndpointAlreadyPublishedIsReusedWithoutDownloading() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] canonical = png(Color.RED);
        store.publish(ACCEPTED.sourceKey(), "png", canonical);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(png(Color.BLUE)));

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store).adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.ALREADY_PUBLISHED, adoption.outcome());
        assertEquals(0, fetcher.calls.get(), "neither side may maintain its own download store");
        assertArrayEquals(canonical, Files.readAllBytes(adoption.file()));
    }

    @Test
    void aCommittedEntrySurvivesAFailedPathUpdateAndIsReusedOnTheNextRun() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] canonical = png(Color.RED);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(canonical));
        DesktopIconAdoption adoption = adoptionWith(fetcher, store);

        Path published = adoption.adopt(ITEM_ID, SOURCE).file();

        // The caller owns the icon_path update and it happens only after publication, so a failure there
        // leaves this asset committed. The next run must find it rather than download it again.
        DesktopIconAdoption.Adoption second = adoption.adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.ALREADY_PUBLISHED, second.outcome());
        assertEquals(published, second.file());
        assertEquals(1, fetcher.calls.get());
        assertArrayEquals(canonical, Files.readAllBytes(published));
    }

    // ---------- legacy files ----------

    @Test
    void aLegacyFileProvenByByteEqualityIsReportedAsAdoptedAndLeftInPlace() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] canonical = png(Color.RED);
        Path legacy = writeLegacyFile(canonical);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(canonical));

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store).adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.ADOPTED_LEGACY, adoption.outcome());
        assertArrayEquals(canonical, Files.readAllBytes(adoption.file()));
        assertTrue(Files.exists(legacy), "an adopted legacy file is not removed by this story");
    }

    @Test
    void anUnprovenLegacyFileNeverBecomesTheKeyedEntryAndStaysUsable() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] legacyBytes = png(Color.BLUE);
        byte[] canonical = png(Color.RED);
        Path legacy = writeLegacyFile(legacyBytes);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(canonical));

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store).adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.PUBLISHED_FROM_UPSTREAM, adoption.outcome(),
                "the database URL and the filename alone prove nothing about these bytes");
        assertArrayEquals(canonical, Files.readAllBytes(adoption.file()));
        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy), "the legacy file stays as JavaFX left it");
    }

    @Test
    void anUpstreamFailureLeavesTheLegacyFileAloneAndNamesNoNewPath() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] legacyBytes = png(Color.BLUE);
        Path legacy = writeLegacyFile(legacyBytes);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.TemporaryFailure("upstream is down"));

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store).adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.UNAVAILABLE, adoption.outcome());
        assertNull(adoption.file(), "no icon_path may be recorded for something that was not published");
        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy));
    }

    // ---------- sources that are not fetched ----------

    @Test
    void anUnacceptableSourceIsSkippedWithoutAnyUpstreamRequest() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(png(Color.RED)));

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store)
                .adopt(ITEM_ID, "https://cdn.example.com/file/ABCD/1.png");

        assertEquals(DesktopIconAdoption.Outcome.REJECTED_SOURCE, adoption.outcome());
        assertNull(adoption.file());
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void anImageMissingUpstreamIsReportedWithoutAPath() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.UpstreamMissing());

        DesktopIconAdoption.Adoption adoption = adoptionWith(fetcher, store).adopt(ITEM_ID, SOURCE);

        assertEquals(DesktopIconAdoption.Outcome.UPSTREAM_MISSING, adoption.outcome());
        assertNull(adoption.file());
    }

    private Path writeLegacyFile(byte[] bytes) throws Exception {
        Path items = Files.createDirectories(cacheRoot.resolve(DesktopIconAdoption.LEGACY_ITEMS_DIRECTORY));
        Path legacy = items.resolve(ITEM_ID + ".png");
        Files.write(legacy, bytes);
        return legacy;
    }

    private static byte[] png(Color color) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, 8, 8);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
