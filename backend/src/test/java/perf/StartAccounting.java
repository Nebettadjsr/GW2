package perf;

import java.util.Map;

/**
 * Generator-side accounting of one stage. Journey starts and background polls are tracked separately
 * from each other and from the actual HTTP calls they produced.
 * {@code offered} = scheduled by the open-loop plan; {@code submitted} = handed to a worker;
 * {@code started} = a worker actually began (queue delay measured inside the worker);
 * {@code pending} = submitted but not finished when the stage was frozen; {@code missed} = offered but
 * never delivered (admission stopped, rejected by the bounded pool, or window ended).
 */
public record StartAccounting(Counts journeys, Counts polls, int httpCalls, Map<Integer, Integer> httpStatuses,
                              int browserProbeCalls, long maxQueueDelayMillis, int browserApiCalls, int browserPolls) {
    public static final StartAccounting EMPTY = new StartAccounting(Counts.ZERO, Counts.ZERO, 0, Map.of(), 0, 0, 0, 0);

    public StartAccounting {
        httpStatuses = Map.copyOf(httpStatuses);
    }

    public record Counts(int offered, int submitted, int started, int completed, int failed, int pending, int missed, int late, int inflightPeak) {
        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
