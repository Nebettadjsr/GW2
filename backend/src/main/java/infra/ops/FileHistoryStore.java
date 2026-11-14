package infra.ops;

import application.ops.HistoryStore;
import application.ops.HourBucket;
import application.ops.MinuteBucket;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/** The two bounded history files: 360 minute and 720 hour buckets, at most 32 MiB in total (with temps). */
public final class FileHistoryStore implements HistoryStore {
    public static final int MINUTE_BUCKETS = 360;
    public static final int HOUR_BUCKETS = 720;
    /** Two files plus one replacement temp file of the larger must stay within 32 MiB: 3 x 10 MiB. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final long TOTAL_CEILING_BYTES = 32L * 1024 * 1024;

    private final BoundedHistoryFile<MinuteBucket> minutes;
    private final BoundedHistoryFile<HourBucket> hours;

    public FileHistoryStore(Path directory, ObjectMapper mapper) {
        this(directory, mapper, MINUTE_BUCKETS, HOUR_BUCKETS, MAX_FILE_BYTES);
    }

    public FileHistoryStore(Path directory, ObjectMapper mapper, int minuteBuckets, int hourBuckets,
                            long maxFileBytes) {
        this.minutes = new BoundedHistoryFile<>(directory.resolve("minute-history.jsonl"), MinuteBucket.class,
                mapper, minuteBuckets, maxFileBytes);
        this.hours = new BoundedHistoryFile<>(directory.resolve("hour-history.jsonl"), HourBucket.class,
                mapper, hourBuckets, maxFileBytes);
    }

    @Override
    public synchronized void appendMinute(MinuteBucket bucket) { minutes.append(bucket); }

    @Override
    public synchronized void appendHour(HourBucket bucket) { hours.append(bucket); }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(!minutes.unavailable() && !hours.unavailable(),
                minutes.retentionTruncated() || hours.retentionTruncated(), minutes.buckets(), hours.buckets());
    }
}
