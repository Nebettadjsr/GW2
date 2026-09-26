package application.icons;

import infra.icons.FilesystemIconStore;
import infra.icons.IconAcquisition;
import infra.icons.IconFetchResult;
import infra.icons.IconImageFetcher;
import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import infra.icons.IconStorageException;
import infra.icons.IconStore;
import infra.icons.StoredIcon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery boundary's decisions (TARGET_ARCHITECTURE.md §12.1, STORY-API-009): the order of route
 * validation, disk, metadata and upstream, and which outcome each situation produces.
 *
 * <p>Disposable cache root, a stub metadata read and a controlled fetcher, so every test states exactly
 * what was available - including the cases where the upstream host or the metadata store is not.
 */
class IconDeliveryTest {

    private static final String SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String KEY = "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472";
    private static final String OTHER_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/9999.png";

    private static final int ITEM_ID = 19721;

    @TempDir
    Path cacheRoot;

    /** Stands in for the retained-metadata read; counts calls so "no metadata read on a hit" is provable. */
    private static final class StubMetadata extends repo.ItemIconMetadataRepository {
        private final String source;
        private final SQLException failure;
        final AtomicInteger calls = new AtomicInteger();

        StubMetadata(String source) {
            this(source, null);
        }

        StubMetadata(String source, SQLException failure) {
            this.source = source;
            this.failure = failure;
        }

        @Override
        public Optional<String> findIconSource(int itemId) throws SQLException {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return Optional.ofNullable(source);
        }
    }

    private static final class StubFetcher implements IconImageFetcher {
        private final IconFetchResult result;
        final AtomicInteger calls = new AtomicInteger();

        StubFetcher(IconFetchResult result) {
            this.result = result;
        }

        @Override
        public IconFetchResult fetch(IconSource source) {
            calls.incrementAndGet();
            return result;
        }
    }

    private static byte[] png() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static String fileName(String key, String extension) {
        return key + "." + extension;
    }

    // ---------- hits ----------

    @Test
    void servesAValidDiskHitWithoutReadingMetadataOrTouchingTheUpstreamHost() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = png();
        store.publish(KEY, "png", bytes);

        StubMetadata metadata = new StubMetadata(null);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.TemporaryFailure("upstream is down"));

        IconDeliveryResult result = new IconDelivery(metadata, store, new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));

        IconDeliveryResult.Delivered delivered = assertInstanceOf(IconDeliveryResult.Delivered.class, result);
        assertArrayEquals(bytes, delivered.bytes());
        assertEquals("image/png", delivered.contentType());
        assertEquals(0, metadata.calls.get(), "a hit must not depend on live metadata");
        assertEquals(0, fetcher.calls.get(), "a hit must not depend on the upstream host");
    }

    @Test
    void theEtagIsStrongAndFollowsTheStoredBytes() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        store.publish(KEY, "png", png());
        IconDelivery delivery = new IconDelivery(new StubMetadata(null), store, acquisitionThatMustNotRun(store));

        String first = etagOf(delivery.deliver(String.valueOf(ITEM_ID), fileName(KEY, "png")));
        String second = etagOf(delivery.deliver(String.valueOf(ITEM_ID), fileName(KEY, "png")));

        assertEquals(first, second, "the same bytes must always validate the same way");
        assertTrue(first.startsWith("\"") && first.endsWith("\""), "a strong ETag is quoted and unprefixed: " + first);

        BufferedImage other = new BufferedImage(6, 6, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream otherBytes = new ByteArrayOutputStream();
        ImageIO.write(other, "png", otherBytes);
        String otherKey = IconSourcePolicy.accept(OTHER_SOURCE).orElseThrow().sourceKey();
        store.publish(otherKey, "png", otherBytes.toByteArray());

        assertNotEquals(first, etagOf(delivery.deliver(String.valueOf(ITEM_ID), fileName(otherKey, "png"))));
    }

    // ---------- malformed routes ----------

    @Test
    void aMalformedRouteIsRejectedBeforeAnyMetadataOrFileAccess() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubMetadata metadata = new StubMetadata(SOURCE);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));
        IconDelivery delivery = new IconDelivery(metadata, store, new IconAcquisition(store, fetcher));

        assertInstanceOf(IconDeliveryResult.InvalidRequest.class, delivery.deliver("abc", fileName(KEY, "png")));
        assertInstanceOf(IconDeliveryResult.InvalidRequest.class, delivery.deliver("0", fileName(KEY, "png")));
        assertInstanceOf(IconDeliveryResult.InvalidRequest.class, delivery.deliver("19721", "notakey.png"));
        assertInstanceOf(IconDeliveryResult.InvalidRequest.class, delivery.deliver("19721", KEY + ".gif"));
        assertInstanceOf(IconDeliveryResult.InvalidRequest.class, delivery.deliver("19721", "../../secret.png"));

        assertEquals(0, metadata.calls.get(), "a malformed route must not reach the metadata store");
        assertEquals(0, fetcher.calls.get(), "a malformed route must not reach the upstream host");
    }

    // ---------- misses that must not fetch ----------

    @Test
    void anItemWithNoRetainedMetadataIsNotFoundWithoutUpstreamAccess() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));

        IconDeliveryResult result = new IconDelivery(new StubMetadata(null), store, new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));

        assertInstanceOf(IconDeliveryResult.NotFound.class, result);
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void aRejectedRetainedSourceIsNotFoundWithoutUpstreamAccess() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));

        IconDeliveryResult result = new IconDelivery(
                new StubMetadata("https://cdn.example.com/file/ABCD/1.png"), store,
                new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));

        assertInstanceOf(IconDeliveryResult.NotFound.class, result);
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void anObsoleteOrForeignKeyIsNotFoundWithoutUpstreamAccess() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));
        String obsoleteKey = IconSourcePolicy.accept(OTHER_SOURCE).orElseThrow().sourceKey();

        // The item's current source derives a different key, so the requested one is an old version.
        IconDeliveryResult result = new IconDelivery(new StubMetadata(SOURCE), store,
                new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(obsoleteKey, "png"));

        assertInstanceOf(IconDeliveryResult.NotFound.class, result);
        assertEquals(0, fetcher.calls.get(), "an unmatched key must never trigger a download");
    }

    @Test
    void aMismatchedExtensionForTheItemsSourceIsNotFound() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));

        IconDeliveryResult result = new IconDelivery(new StubMetadata(SOURCE), store,
                new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "jpg"));

        assertInstanceOf(IconDeliveryResult.NotFound.class, result);
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void anOldKeyKeepsServingItsOwnImageAndNeverNewVersionBytes() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] oldImage = png();
        store.publish(KEY, "png", oldImage);

        // The item's retained metadata has since moved to a new source, so its URL will carry a new key.
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(oldImage));
        IconDelivery delivery = new IconDelivery(new StubMetadata(OTHER_SOURCE), store,
                new IconAcquisition(store, fetcher));

        IconDeliveryResult old = delivery.deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));
        assertArrayEquals(oldImage, assertInstanceOf(IconDeliveryResult.Delivered.class, old).bytes(),
                "the cached old key still serves its original image");

        String newKey = IconSourcePolicy.accept(OTHER_SOURCE).orElseThrow().sourceKey();
        assertNotEquals(KEY, newKey, "changed metadata must produce a new key and so a new browser URL");
    }

    // ---------- misses that do fetch ----------

    @Test
    void aMatchedMissIsFetchedPublishedAndThenServedFromDisk() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = png();
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(bytes));
        IconDelivery delivery = new IconDelivery(new StubMetadata(SOURCE), store,
                new IconAcquisition(store, fetcher));

        IconDeliveryResult first = delivery.deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));
        assertArrayEquals(bytes, assertInstanceOf(IconDeliveryResult.Delivered.class, first).bytes());
        assertEquals(1, fetcher.calls.get());

        assertTrue(store.find(KEY, "png").isPresent(), "success must mean it is persisted");

        IconDeliveryResult second = delivery.deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));
        assertArrayEquals(bytes, assertInstanceOf(IconDeliveryResult.Delivered.class, second).bytes());
        assertEquals(1, fetcher.calls.get(), "the second request must be a disk hit");
    }

    @Test
    void anUpstreamMissingImageIsNotFound() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.UpstreamMissing());

        assertInstanceOf(IconDeliveryResult.NotFound.class,
                new IconDelivery(new StubMetadata(SOURCE), store, new IconAcquisition(store, fetcher))
                        .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png")));
    }

    @Test
    void aTemporaryUpstreamFailureIsUnavailableWithAFiniteRetryHorizon() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.TemporaryFailure("upstream is down"));

        IconDeliveryResult result = new IconDelivery(new StubMetadata(SOURCE), store,
                new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));

        IconDeliveryResult.Unavailable unavailable =
                assertInstanceOf(IconDeliveryResult.Unavailable.class, result);
        assertTrue(unavailable.retryAfterSeconds() > 0, "the retry horizon must be finite and positive");
    }

    @Test
    void anUnavailableMetadataStoreIsUnavailableRatherThanNotFound() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));

        IconDeliveryResult result = new IconDelivery(
                new StubMetadata(null, new SQLException("connection refused for user gw2 password hunter2")),
                store, new IconAcquisition(store, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));

        IconDeliveryResult.Unavailable unavailable =
                assertInstanceOf(IconDeliveryResult.Unavailable.class, result);
        assertTrue(!unavailable.reason().contains("hunter2") && !unavailable.reason().contains("jdbc"),
                "no credential or connection detail may travel with the outcome: " + unavailable.reason());
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void anUnreadableCacheIsUnavailableRatherThanAMiss() {
        IconStore failing = new IconStore() {
            @Override
            public Optional<StoredIcon> find(String sourceKey, String extension) throws IconStorageException {
                throw new IconStorageException("C:\\icons\\assets-v1 is not readable");
            }

            @Override
            public StoredIcon publish(String sourceKey, String extension, byte[] bytes) {
                throw new AssertionError("publication must not be attempted when the cache cannot be read");
            }
        };
        StubMetadata metadata = new StubMetadata(SOURCE);
        StubFetcher fetcher = new StubFetcher(new IconFetchResult.Fetched(png()));

        IconDeliveryResult result = new IconDelivery(metadata, failing, new IconAcquisition(failing, fetcher))
                .deliver(String.valueOf(ITEM_ID), fileName(KEY, "png"));

        IconDeliveryResult.Unavailable unavailable =
                assertInstanceOf(IconDeliveryResult.Unavailable.class, result);
        assertTrue(!unavailable.reason().contains("C:\\"),
                "no local path may travel with the outcome: " + unavailable.reason());
        assertEquals(0, metadata.calls.get());
        assertEquals(0, fetcher.calls.get());
    }

    private static IconAcquisition acquisitionThatMustNotRun(IconStore store) {
        return new IconAcquisition(store, source -> {
            throw new AssertionError("no upstream request was expected");
        });
    }

    private static String etagOf(IconDeliveryResult result) {
        return assertInstanceOf(IconDeliveryResult.Delivered.class, result).etag();
    }
}
