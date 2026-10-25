package infra.icons;

/**
 * What {@link IconAcquisition} established for one key. {@link Available} is the only case that
 * carries an entry, and the entry is always already committed to disk - acquisition never hands back
 * bytes it failed to persist (TARGET_ARCHITECTURE.md §12.1).
 */
public sealed interface IconAcquisitionResult {

    /** The key is committed in the cache, whether it was already there or was just published. */
    record Available(StoredIcon icon) implements IconAcquisitionResult {}

    /** The upstream host reports no such image; there is nothing to serve and nothing to retry. */
    record UpstreamMissing() implements IconAcquisitionResult {}

    /** Temporarily unavailable: upstream failure, rejected image, exhausted capacity or disk failure. */
    record Unavailable(String reason) implements IconAcquisitionResult {}
}
