package application.ops;

import java.util.List;

/** Port of the sparse activity history; implementations never throw to the caller. */
public interface HistoryStore {
    /** Persists one closed minute; a minute without included request activity is never stored. */
    void appendMinute(MinuteBucket bucket);

    Snapshot snapshot();

    /**
     * @param available false when the history could not be read or written (never "empty")
     * @param hours always empty since STORY-OPS-003: no hourly store exists; kept for summary compatibility
     */
    record Snapshot(boolean available, boolean retentionTruncated, List<MinuteBucket> minutes,
                    List<HourBucket> hours) {}
}
