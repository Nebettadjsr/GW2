package application.ops;

import java.util.List;

/** One route group inside one bucket: counters and a fixed non-cumulative histogram; no per-request data. */
public record RouteGroupAggregate(RouteGroup group, long requests, long success, long clientError,
                                  long serverError, long failed, long elapsedMicrosSum, long elapsedMicrosMax,
                                  List<LatencyHistogram.Bin> histogram) {
    /** Merges aggregates of one group by summing raw counts and bins (overflow-safe) and taking the max. */
    public static RouteGroupAggregate merge(List<RouteGroupAggregate> parts, RouteGroup group) {
        long requests = 0, success = 0, client = 0, server = 0, failed = 0, sum = 0, max = 0;
        long[] bins = new long[LatencyHistogram.BINS];
        for (RouteGroupAggregate p : parts) {
            requests = LatencyHistogram.saturatedAdd(requests, p.requests);
            success = LatencyHistogram.saturatedAdd(success, p.success);
            client = LatencyHistogram.saturatedAdd(client, p.clientError);
            server = LatencyHistogram.saturatedAdd(server, p.serverError);
            failed = LatencyHistogram.saturatedAdd(failed, p.failed);
            sum = LatencyHistogram.saturatedAdd(sum, p.elapsedMicrosSum);
            max = Math.max(max, p.elapsedMicrosMax);
            LatencyHistogram.add(bins, LatencyHistogram.toCounts(p.histogram));
        }
        return new RouteGroupAggregate(group, requests, success, client, server, failed, sum, max,
                LatencyHistogram.toBins(bins));
    }
}
