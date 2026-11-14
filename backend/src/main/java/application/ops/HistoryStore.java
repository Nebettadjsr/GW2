package application.ops;

import java.util.List;

/** Port of the bounded operational history; implementations never throw to the caller. */
public interface HistoryStore {
    void appendMinute(MinuteBucket bucket);

    void appendHour(HourBucket bucket);

    Snapshot snapshot();

    /** @param available false when the history could not be read or written (never "empty") */
    record Snapshot(boolean available, boolean retentionTruncated, List<MinuteBucket> minutes,
                    List<HourBucket> hours) {}
}
