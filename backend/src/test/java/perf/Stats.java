package perf;

import java.util.Arrays;
import java.util.List;

/** Small, independently checkable aggregation helpers for the measurement report. */
public final class Stats {
    private Stats() {}

    /** Nearest-rank percentile: the smallest sample with at least {@code p} percent of samples at or below it. */
    public static double percentile(List<Double> samples, double p) {
        if (samples.isEmpty()) throw new IllegalArgumentException("no samples");
        if (p <= 0 || p > 100) throw new IllegalArgumentException("percentile must be in (0, 100]");
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    public static double p95(List<Double> samples) {
        return percentile(samples, 95);
    }

    /** Requests (or operations) per second over the elapsed wall time. */
    public static double throughputPerSecond(long count, long elapsedNanos) {
        if (elapsedNanos <= 0) throw new IllegalArgumentException("elapsed time must be positive");
        return count / (elapsedNanos / 1_000_000_000.0);
    }

    /**
     * Share of the machine's CPU capacity the process used: CPU time divided by wall time times
     * available processors, as a percentage (100 = every processor fully busy).
     */
    public static double cpuUtilizationPercent(long cpuNanos, long wallNanos, int processors) {
        if (wallNanos <= 0 || processors <= 0) throw new IllegalArgumentException("wall time and processors must be positive");
        return 100.0 * cpuNanos / ((double) wallNanos * processors);
    }

    public static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
