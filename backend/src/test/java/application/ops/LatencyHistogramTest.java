package application.ops;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LatencyHistogramTest {
    @Test
    void boundariesAreInclusiveAndOverflowIsFinalBin() {
        assertEquals(0, LatencyHistogram.binFor(0));
        assertEquals(0, LatencyHistogram.binFor(25_000));
        assertEquals(1, LatencyHistogram.binFor(25_001));
        assertEquals(7, LatencyHistogram.binFor(5_000_000));
        assertEquals(8, LatencyHistogram.binFor(5_000_001));
        assertEquals(9, LatencyHistogram.BINS);
    }

    @Test
    void overflowSerializesAsFiniteBoundWithFlag() throws Exception {
        long[] counts = new long[9];
        counts[8] = 3;
        String json = JsonMapper.builder().build().writeValueAsString(LatencyHistogram.toBins(counts));
        assertTrue(json.contains("{\"upperBoundMs\":5000,\"overflow\":true,\"count\":3}"), json);
        assertFalse(json.contains("Infinity") || json.contains("Inf"));
        assertFalse(json.contains("\"upperBoundMs\":0"));
    }

    @Test
    void percentileOverflowReportsFiveSecondsWithGreaterThanSemantics() {
        long[] counts = new long[9];
        counts[0] = 90;
        counts[8] = 10;
        var p50 = LatencyHistogram.percentile(counts, 0.5).orElseThrow();
        assertEquals(new LatencyHistogram.Percentile(25, false), p50);
        var p99 = LatencyHistogram.percentile(counts, 0.99).orElseThrow();
        assertEquals(5000, p99.upperBoundMs());
        assertTrue(p99.overflow());
        assertEquals(">5 s", p99.display());
        assertTrue(LatencyHistogram.percentile(new long[9], 0.5).isEmpty());
    }

    @Test
    void mergedPercentileIsNotTheAverageOfMinutePercentiles() {
        long[] a = new long[9];
        long[] b = new long[9];
        a[0] = 1;       // p95 of a alone = 25ms
        b[3] = 99;      // p95 of b alone = 250ms
        long[] merged = new long[9];
        LatencyHistogram.add(merged, a);
        LatencyHistogram.add(merged, b);
        assertEquals(250, LatencyHistogram.percentile(merged, 0.95).orElseThrow().upperBoundMs());
        assertEquals(250, LatencyHistogram.percentile(merged, 0.5).orElseThrow().upperBoundMs());
        assertEquals(Long.MAX_VALUE, LatencyHistogram.saturatedAdd(Long.MAX_VALUE, 5));
    }

    @Test
    void roundTripBins() {
        long[] counts = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        List<LatencyHistogram.Bin> bins = LatencyHistogram.toBins(counts);
        assertArrayEquals(counts, LatencyHistogram.toCounts(bins));
    }
}
