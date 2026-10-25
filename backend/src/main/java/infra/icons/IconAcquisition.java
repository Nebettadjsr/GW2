package infra.icons;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Turns a cache miss into a committed cache entry, once (TARGET_ARCHITECTURE.md §12.1). Every writer -
 * the image endpoint's misses and the desktop icon sync - goes through this class, so there is one
 * key policy, one download-bounding policy and one publication protocol rather than two stores.
 *
 * <p>What it guarantees for a key:
 * <ul>
 *   <li>Concurrent misses are coalesced inside this process. A waiter that gets the key's slot
 *       rechecks the disk first, so the second caller of a pair does no upstream request.</li>
 *   <li>Downloads, waiting callers and waiting time are bounded ({@link IconCacheBounds}); an
 *       exhausted bound is an unavailable answer, never an unbounded queue.</li>
 *   <li>Bytes are published before success is reported. A persistence failure is unavailable, not a
 *       successful uncached image.</li>
 *   <li>A failure is never persisted. It is suppressed in memory for
 *       {@link IconCacheBounds#FAILURE_SUPPRESSION}, which expires on its own and cannot hide a valid
 *       disk hit, because the disk is rechecked before the suppression is consulted.</li>
 * </ul>
 *
 * <p>No distributed lock is involved: coordination is per process, and correctness across processes
 * rests on the store's atomic, non-replacing publication instead.
 *
 * <p>Thread-safe.
 */
public final class IconAcquisition {

    private final IconStore store;
    private final IconImageFetcher fetcher;
    private final LongSupplier clockMillis;

    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> suppressedUntilMillis = new ConcurrentHashMap<>();
    private final Semaphore downloadSlots = new Semaphore(IconCacheBounds.MAX_CONCURRENT_DOWNLOADS);
    private final AtomicInteger waitingMisses = new AtomicInteger();

    public IconAcquisition(IconStore store, IconImageFetcher fetcher) {
        this(store, fetcher, System::currentTimeMillis);
    }

    /** Seam for tests that need to prove the failure suppression expires rather than sleeping for it. */
    public IconAcquisition(IconStore store, IconImageFetcher fetcher, LongSupplier clockMillis) {
        this.store = store;
        this.fetcher = fetcher;
        this.clockMillis = clockMillis;
    }

    /**
     * The committed entry for {@code source}, acquiring it from upstream if the cache does not have it.
     *
     * @return {@link IconAcquisitionResult.Available} only when the bytes are committed to the cache
     */
    public IconAcquisitionResult acquire(IconSource source) {
        if (waitingMisses.incrementAndGet() > IconCacheBounds.MAX_WAITING_MISSES) {
            waitingMisses.decrementAndGet();
            return new IconAcquisitionResult.Unavailable("too many icon acquisitions are already in flight");
        }

        try {
            return acquireUnderKeyLock(source);
        } finally {
            waitingMisses.decrementAndGet();
        }
    }

    private IconAcquisitionResult acquireUnderKeyLock(IconSource source) {
        ReentrantLock keyLock = keyLocks.computeIfAbsent(source.sourceKey(), key -> new ReentrantLock());

        boolean held;
        try {
            held = keyLock.tryLock(IconCacheBounds.WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new IconAcquisitionResult.Unavailable("waiting for the icon was interrupted");
        }

        if (!held) {
            return new IconAcquisitionResult.Unavailable("timed out waiting for another icon acquisition");
        }

        try {
            return acquireExclusively(source);
        } finally {
            keyLock.unlock();
            if (!keyLock.hasQueuedThreads()) {
                keyLocks.remove(source.sourceKey(), keyLock);
            }
        }
    }

    /** Runs with this key's slot held, so at most one caller per key can be here. */
    private IconAcquisitionResult acquireExclusively(IconSource source) {
        Optional<StoredIcon> committed;
        try {
            // Rechecked here on purpose: the caller that held the slot before this one may have just
            // published it, which is what makes coalescing work and what keeps suppression from
            // hiding a valid entry.
            committed = store.find(source.sourceKey(), source.extension());
        } catch (IconStorageException storageFailure) {
            return new IconAcquisitionResult.Unavailable("icon storage is unavailable");
        }

        if (committed.isPresent()) return new IconAcquisitionResult.Available(committed.get());

        if (isSuppressed(source.sourceKey())) {
            return new IconAcquisitionResult.Unavailable("a recent acquisition failure is still suppressed");
        }

        return fetchAndPublish(source);
    }

    private IconAcquisitionResult fetchAndPublish(IconSource source) {
        IconFetchResult fetched = fetchWithinDownloadBound(source);

        return switch (fetched) {
            case IconFetchResult.Fetched(byte[] bytes) -> publish(source, bytes);
            case IconFetchResult.UpstreamMissing ignored -> new IconAcquisitionResult.UpstreamMissing();
            case IconFetchResult.RejectedImage(String reason) -> suppress(source.sourceKey(), reason);
            case IconFetchResult.TemporaryFailure(String reason) -> suppress(source.sourceKey(), reason);
        };
    }

    private IconFetchResult fetchWithinDownloadBound(IconSource source) {
        boolean slot;
        try {
            slot = downloadSlots.tryAcquire(IconCacheBounds.WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new IconFetchResult.TemporaryFailure("waiting for a download slot was interrupted");
        }

        if (!slot) return new IconFetchResult.TemporaryFailure("icon download capacity is exhausted");

        try {
            return fetcher.fetch(source);
        } finally {
            downloadSlots.release();
        }
    }

    private IconAcquisitionResult publish(IconSource source, byte[] bytes) {
        try {
            return new IconAcquisitionResult.Available(
                    store.publish(source.sourceKey(), source.extension(), bytes));
        } catch (IconStorageException notPersisted) {
            // Deliberately not returned as a successful image: an icon that could not be stored must
            // surface as a bounded failure (§12.1).
            return new IconAcquisitionResult.Unavailable("the icon could not be persisted");
        }
    }

    private IconAcquisitionResult suppress(String sourceKey, String reason) {
        suppressedUntilMillis.put(sourceKey, clockMillis.getAsLong() + IconCacheBounds.FAILURE_SUPPRESSION.toMillis());
        return new IconAcquisitionResult.Unavailable(reason);
    }

    private boolean isSuppressed(String sourceKey) {
        Long until = suppressedUntilMillis.get(sourceKey);
        if (until == null) return false;

        if (until > clockMillis.getAsLong()) return true;

        suppressedUntilMillis.remove(sourceKey, until);
        return false;
    }
}
