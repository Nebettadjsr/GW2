package application.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed latency histogram (STORY-OPS-001 section 5): eight finite boundaries and one explicit
 * overflow bin. Bin counts are non-cumulative; percentiles come from merged counts, never from averaging.
 */
public final class LatencyHistogram {
    public static final long[] BOUNDS_MS = {25, 50, 100, 250, 500, 1_000, 2_000, 5_000};
    public static final int BINS = BOUNDS_MS.length + 1;

    private LatencyHistogram() {}

    /** One serialized bin: a finite upper bound, with {@code overflow} marking the final {@code >5 s} bin. */
    public record Bin(long upperBoundMs, boolean overflow, long count) {}

    /** A selected percentile bin; {@code overflow} means the display semantics {@code >5 s}. */
    public record Percentile(long upperBoundMs, boolean overflow) {
        public String display() { return overflow ? ">5 s" : "<=" + upperBoundMs + " ms"; }
    }

    public static int binFor(long elapsedMicros) {
        for (int i = 0; i < BOUNDS_MS.length; i++) {
            if (elapsedMicros <= BOUNDS_MS[i] * 1_000L) return i;
        }
        return BOUNDS_MS.length;
    }

    public static List<Bin> toBins(long[] counts) {
        List<Bin> bins = new ArrayList<>(BINS);
        for (int i = 0; i < BINS; i++) {
            boolean overflow = i == BOUNDS_MS.length;
            bins.add(new Bin(overflow ? BOUNDS_MS[BOUNDS_MS.length - 1] : BOUNDS_MS[i], overflow, counts[i]));
        }
        return List.copyOf(bins);
    }

    public static long[] toCounts(List<Bin> bins) {
        long[] counts = new long[BINS];
        if (bins == null || bins.size() != BINS) return counts;
        for (int i = 0; i < BINS; i++) counts[i] = Math.max(0, bins.get(i).count());
        return counts;
    }

    public static void add(long[] into, long[] more) {
        for (int i = 0; i < BINS; i++) into[i] = saturatedAdd(into[i], more[i]);
    }

    /** First bin whose cumulative rank reaches {@code fraction}; empty histograms have no percentile. */
    public static java.util.Optional<Percentile> percentile(long[] counts, double fraction) {
        long total = 0;
        for (long c : counts) total = saturatedAdd(total, c);
        if (total == 0) return java.util.Optional.empty();
        long rank = Math.max(1, (long) Math.ceil(fraction * total));
        long cumulative = 0;
        for (int i = 0; i < BINS; i++) {
            cumulative = saturatedAdd(cumulative, counts[i]);
            if (cumulative >= rank) {
                return java.util.Optional.of(i == BOUNDS_MS.length
                        ? new Percentile(BOUNDS_MS[BOUNDS_MS.length - 1], true) : new Percentile(BOUNDS_MS[i], false));
            }
        }
        return java.util.Optional.empty();
    }

    public static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
