package infra.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persistent cache's own guarantees (TARGET_ARCHITECTURE.md §12.1, STORY-API-009): what counts as
 * an entry, how a write becomes visible, what a second writer may not do, and that a restart reuses
 * what is on disk.
 *
 * <p>Every test runs against a disposable cache root ({@link TempDir}), so nothing here touches the
 * configured {@code ICON_CACHE_DIR}, the network or the database.
 */
class FilesystemIconStoreTest {

    private static final String KEY = "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472";
    private static final String OTHER_KEY = "9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d";

    @TempDir
    Path cacheRoot;

    // ---------- publication and hits ----------

    @Test
    void publishesUnderTheKeyedAssetsPathAndReadsItBack() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = TestImages.png();

        StoredIcon published = store.publish(KEY, "png", bytes);

        assertEquals(cacheRoot.resolve("assets-v1").resolve(KEY + ".png"),
                published.file(), "the entry must live at <root>/assets-v1/{key}.{ext}");
        assertArrayEquals(bytes, published.bytes());
        assertArrayEquals(bytes, store.find(KEY, "png").orElseThrow().bytes());
    }

    @Test
    void anUnpublishedKeyIsAMiss() throws Exception {
        assertTrue(new FilesystemIconStore(cacheRoot).find(KEY, "png").isEmpty());
    }

    @Test
    void aDifferentKeyOrExtensionIsItsOwnEntry() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        store.publish(KEY, "png", TestImages.png());

        assertTrue(store.find(OTHER_KEY, "png").isEmpty(), "another source must not resolve to this image");
        assertTrue(store.find(KEY, "jpg").isEmpty(), "the extension is part of the entry's identity");
    }

    @Test
    void aSecondStoreOverTheSameRootFindsWhatTheFirstPublished() throws Exception {
        byte[] bytes = TestImages.png();
        new FilesystemIconStore(cacheRoot).publish(KEY, "png", bytes);

        // Stands in for a backend restart: a new process, the same configured root, no upstream access.
        assertArrayEquals(bytes, new FilesystemIconStore(cacheRoot).find(KEY, "png").orElseThrow().bytes());
    }

    @Test
    void nothingExpiresAnEntryOnItsOwn() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        store.publish(KEY, "png", TestImages.png());

        Path entry = cacheRoot.resolve("assets-v1").resolve(KEY + ".png");
        Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(0));

        assertTrue(store.find(KEY, "png").isPresent(), "age must not make a valid entry a miss");
    }

    // ---------- what is not an entry ----------

    @Test
    void anEmptyPartialOrCorruptFileIsAMissRatherThanAHalfServedHit() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        Path assets = Files.createDirectories(cacheRoot.resolve("assets-v1"));

        Files.write(assets.resolve(KEY + ".png"), new byte[0]);
        assertTrue(store.find(KEY, "png").isEmpty(), "an empty file is not an entry");

        Files.write(assets.resolve(KEY + ".png"), TestImages.htmlErrorBody());
        assertTrue(store.find(KEY, "png").isEmpty(), "a non-image file is not an entry");

        Files.write(assets.resolve(OTHER_KEY + ".png"), TestImages.jpg());
        assertTrue(store.find(OTHER_KEY, "png").isEmpty(),
                "bytes that disagree with the entry's extension are not an entry");
    }

    @Test
    void aTemporaryFileIsNeverAnEntry() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        Path assets = Files.createDirectories(cacheRoot.resolve("assets-v1"));
        Files.write(assets.resolve(KEY + ".png.123.tmp"), TestImages.png());

        assertTrue(store.find(KEY, "png").isEmpty(),
                "an interrupted write leaves a temporary file, which a reader must never see as the entry");
    }

    @Test
    void publishLeavesNoTemporaryFileBehind() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        store.publish(KEY, "png", TestImages.png());

        try (var entries = Files.list(cacheRoot.resolve("assets-v1"))) {
            assertEquals(List.of(KEY + ".png"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void refusesToPublishEmptyBytes() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);

        assertThrows(IllegalArgumentException.class, () -> store.publish(KEY, "png", new byte[0]));
    }

    // ---------- confinement ----------

    @Test
    void refusesAnythingThatIsNotAValidatedKeyAndExtension() {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);

        assertThrows(IllegalArgumentException.class, () -> store.find("../../etc/passwd", "png"));
        assertThrows(IllegalArgumentException.class, () -> store.find(KEY + "/..", "png"));
        assertThrows(IllegalArgumentException.class, () -> store.find(KEY, "php"));
        assertThrows(IllegalArgumentException.class, () -> store.find(KEY, "png/../.."));
        assertThrows(IllegalArgumentException.class, () -> store.publish("not-a-key", "png", TestImages.png()));
    }

    @Test
    void aSymbolicLinkAtAnEntryPathIsNotAnEntry() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        Path assets = Files.createDirectories(cacheRoot.resolve("assets-v1"));
        Path outside = cacheRoot.resolve("outside.png");
        Files.write(outside, TestImages.png());

        try {
            Files.createSymbolicLink(assets.resolve(KEY + ".png"), outside);
        } catch (IOException | UnsupportedOperationException symlinksNotPermitted) {
            // Creating a symbolic link needs a privilege this machine may not grant; the confinement
            // rule is still asserted by the key/extension checks above.
            return;
        }

        assertTrue(store.find(KEY, "png").isEmpty(),
                "a linked file must not be served as an entry, whatever it points at");
    }

    // ---------- concurrent and repeated publication ----------

    @Test
    void aSecondPublisherKeepsTheCommittedEntryRatherThanOverwritingIt() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] first = TestImages.png();
        store.publish(KEY, "png", first);

        StoredIcon second = store.publish(KEY, "png", TestImages.otherPng());

        assertArrayEquals(first, second.bytes(), "the committed entry must win");
        assertArrayEquals(first, store.find(KEY, "png").orElseThrow().bytes());
    }

    @Test
    void concurrentPublishersAllSeeOneCommittedEntry() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        byte[] bytes = TestImages.png();

        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<byte[]>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < writers; i++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return store.publish(KEY, "png", bytes).bytes();
                }));
            }
            start.countDown();

            for (Future<byte[]> result : results) {
                assertArrayEquals(bytes, result.get(10, TimeUnit.SECONDS));
            }
        }

        try (var entries = Files.list(cacheRoot.resolve("assets-v1"))) {
            assertEquals(1, entries.count(), "concurrent publication must leave exactly one file");
        }
    }

    // ---------- storage failure ----------

    @Test
    void anUnusableStorageRootIsAStorageFailureRatherThanASilentMiss() throws Exception {
        // A regular file where the assets directory has to be: createDirectories cannot succeed.
        Files.write(cacheRoot.resolve("assets-v1"), new byte[]{1});
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);

        IconStorageException failure = assertThrows(IconStorageException.class,
                () -> store.publish(KEY, "png", TestImages.png()));
        assertTrue(failure.getCause() instanceof IOException || failure.getCause() instanceof FileSystemException);

        assertFalse(store.find(KEY, "png").isPresent(), "a failed publication must not look like a hit");
    }

    @Test
    void anOccupiedEntryPathThatIsNotAUsableImageFailsRatherThanServingIt() throws Exception {
        FilesystemIconStore store = new FilesystemIconStore(cacheRoot);
        Path assets = Files.createDirectories(cacheRoot.resolve("assets-v1"));
        Files.write(assets.resolve(KEY + ".png"), TestImages.htmlErrorBody());

        // Publication cannot replace the occupying file (no REPLACE_EXISTING) and the file is not an
        // entry either, so this surfaces as a bounded failure; removing it by hand permits repair.
        assertThrows(IconStorageException.class, () -> store.publish(KEY, "png", TestImages.png()));

        Files.delete(assets.resolve(KEY + ".png"));
        Optional<StoredIcon> repaired = Optional.of(store.publish(KEY, "png", TestImages.png()));
        assertArrayEquals(TestImages.png(), repaired.orElseThrow().bytes());
    }
}
