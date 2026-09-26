package infra.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a miss becomes exactly one committed entry (TARGET_ARCHITECTURE.md §12.1, STORY-API-009):
 * coalescing, the disk recheck, publication before success, the bounded failure suppression and what
 * must never be persisted.
 *
 * <p>Disposable cache root and a controlled fetcher throughout: no network, no database, no GW2 API.
 */
class IconAcquisitionTest {

    private static final IconSource SOURCE = IconSourcePolicy
            .accept("https://render.guildwars2.com/file/ABCDEF0123456789/1234.png").orElseThrow();

    @TempDir
    Path cacheRoot;

    /** A fetcher that answers from a script and counts its calls, so "no upstream request" is provable. */
    private static final class CountingFetcher implements IconImageFetcher {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile IconFetchResult next;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        CountingFetcher(IconFetchResult next) {
            this.next = next;
        }

        @Override
        public IconFetchResult fetch(IconSource source) {
            calls.incrementAndGet();
            if (entered != null) entered.countDown();
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return next;
        }
    }

    // ---------- hits and publication ----------

    @Test
    void publishesTheFetchedImageAndReportsItAsAvailable() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = TestImages.png();
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(bytes));

        IconAcquisitionResult result = new IconAcquisition(store, fetcher).acquire(SOURCE);

        assertArrayEquals(bytes, assertInstanceOf(IconAcquisitionResult.Available.class, result).icon().bytes());
        assertArrayEquals(bytes, store.find(SOURCE.sourceKey(), "png").orElseThrow().bytes(),
                "success must mean the bytes are already on disk, not merely in hand");
    }

    @Test
    void anAlreadyCommittedEntryIsReturnedWithoutAnyUpstreamRequest() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = TestImages.png();
        store.publish(SOURCE.sourceKey(), "png", bytes);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(TestImages.otherPng()));

        IconAcquisitionResult result = new IconAcquisition(store, fetcher).acquire(SOURCE);

        assertArrayEquals(bytes, assertInstanceOf(IconAcquisitionResult.Available.class, result).icon().bytes());
        assertEquals(0, fetcher.calls.get(), "a committed entry must never trigger a download");
    }

    @Test
    void concurrentMissesOfOneKeyProduceASingleDownload() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = TestImages.png();
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(bytes));
        fetcher.entered = new CountDownLatch(1);
        fetcher.release = new CountDownLatch(1);

        IconAcquisition acquisition = new IconAcquisition(store, fetcher);
        List<Future<IconAcquisitionResult>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            results.add(pool.submit(() -> acquisition.acquire(SOURCE)));
            assertTrue(fetcher.entered.await(5, TimeUnit.SECONDS), "the first caller must reach the fetcher");

            // Started while the first download is still in flight: it has to wait for the key's slot and
            // then find the published entry, rather than start a second download.
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> acquisition.acquire(SOURCE)));
            }
            fetcher.release.countDown();

            for (Future<IconAcquisitionResult> result : results) {
                IconAcquisitionResult acquired = result.get(10, TimeUnit.SECONDS);
                assertArrayEquals(bytes,
                        assertInstanceOf(IconAcquisitionResult.Available.class, acquired).icon().bytes());
            }
        }

        assertEquals(1, fetcher.calls.get(), "same-key misses must be coalesced into one upstream request");
    }

    // ---------- failures ----------

    @Test
    void anUpstreamMissingImageIsReportedAndNothingIsPersisted() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.UpstreamMissing());

        assertInstanceOf(IconAcquisitionResult.UpstreamMissing.class,
                new IconAcquisition(store, fetcher).acquire(SOURCE));

        assertTrue(store.find(SOURCE.sourceKey(), "png").isEmpty(), "a 404 must not be persisted");
        assertNoFilesUnder(cacheRoot);
    }

    @Test
    void aRejectedImageIsUnavailableAndNeverPersisted() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.RejectedImage("html body"));

        assertInstanceOf(IconAcquisitionResult.Unavailable.class,
                new IconAcquisition(store, fetcher).acquire(SOURCE));

        assertTrue(store.find(SOURCE.sourceKey(), "png").isEmpty());
        assertNoFilesUnder(cacheRoot);
    }

    @Test
    void aStorageFailureIsUnavailableRatherThanASuccessfulUncachedImage() throws Exception {
        // A regular file where the assets directory must be, so publication cannot succeed.
        Files.write(cacheRoot.resolve("assets-v1"), new byte[]{1});
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.Fetched(TestImages.png()));

        assertInstanceOf(IconAcquisitionResult.Unavailable.class,
                new IconAcquisition(store, fetcher).acquire(SOURCE));
    }

    @Test
    void aFailedKeyIsSuppressedBrieflyAndThenRetriedWhenTheSuppressionExpires() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.TemporaryFailure("upstream down"));
        AtomicLong now = new AtomicLong(1_000);

        IconAcquisition acquisition = new IconAcquisition(store, fetcher, now::get);

        assertInstanceOf(IconAcquisitionResult.Unavailable.class, acquisition.acquire(SOURCE));
        assertEquals(1, fetcher.calls.get());

        assertInstanceOf(IconAcquisitionResult.Unavailable.class, acquisition.acquire(SOURCE));
        assertEquals(1, fetcher.calls.get(), "a suppressed key must not hit the upstream host again");

        now.addAndGet(IconCacheBounds.FAILURE_SUPPRESSION.toMillis() + 1);
        fetcher.next = new IconFetchResult.Fetched(TestImages.png());

        assertInstanceOf(IconAcquisitionResult.Available.class, acquisition.acquire(SOURCE),
                "the suppression must expire on its own and allow recovery");
        assertEquals(2, fetcher.calls.get());
    }

    @Test
    void suppressionNeverHidesAValidDiskHit() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        CountingFetcher fetcher = new CountingFetcher(new IconFetchResult.TemporaryFailure("upstream down"));
        AtomicLong now = new AtomicLong(1_000);
        IconAcquisition acquisition = new IconAcquisition(store, fetcher, now::get);

        assertInstanceOf(IconAcquisitionResult.Unavailable.class, acquisition.acquire(SOURCE));

        // The entry appears while the key is still suppressed - a manual repair, or the desktop sync.
        byte[] bytes = TestImages.png();
        store.publish(SOURCE.sourceKey(), "png", bytes);

        IconAcquisitionResult result = acquisition.acquire(SOURCE);

        assertArrayEquals(bytes, assertInstanceOf(IconAcquisitionResult.Available.class, result).icon().bytes());
        assertEquals(1, fetcher.calls.get(), "the disk is rechecked before the suppression is consulted");
    }

    private static void assertNoFilesUnder(Path root) throws Exception {
        Path assets = root.resolve("assets-v1");
        if (!Files.isDirectory(assets)) return;

        try (var entries = Files.list(assets)) {
            Optional<Path> any = entries.findAny();
            assertTrue(any.isEmpty(), "no file may be left at an image key after a failure: " + any);
        }
    }
}
