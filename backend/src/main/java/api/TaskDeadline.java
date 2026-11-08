package api;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Cooperative whole-task bound for the owner full-catalog operations (STORY-STATUS-004).
 *
 * <p>Fixed, documented values: {@value #CONNECT_SECONDS}s connect and {@value #REQUEST_SECONDS}s
 * request timeouts on the public HTTP edge, and a {@value #TASK_MINUTES}-minute whole-task bound.
 * The bound is <b>cooperative</b>: one monotonic deadline that the task body checks before and after
 * each HTTP request or retry sleep, before opening each database batch, before and after each
 * commit, and before graph/publication phases. It is not a racing wrapper or a watchdog. A blocked
 * call is bounded by the finite HTTP timeouts and by {@code SET LOCAL statement_timeout} for the
 * remaining budget, so the body always returns or throws on its own; the task facility records a
 * terminal state only after that, and the exclusion key stays held until then.
 */
public final class TaskDeadline {

    public static final int CONNECT_SECONDS = 10;
    public static final int REQUEST_SECONDS = 30;
    public static final int TASK_MINUTES = 30;

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(CONNECT_SECONDS);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(REQUEST_SECONDS);
    public static final Duration TASK_BOUND = Duration.ofMinutes(TASK_MINUTES);

    /** No bound: ordinary (non full-catalog) callers keep their existing behavior. */
    public static final TaskDeadline NONE = new TaskDeadline(null, 0);

    /** Thrown when the whole-task bound is exhausted. */
    public static final class ExceededException extends RuntimeException {
        public ExceededException() {
            super("full-catalog task exceeded its " + TASK_MINUTES + "-minute bound");
        }
    }

    private final LongSupplier nanoClock;
    private final long deadlineNanos;

    private TaskDeadline(LongSupplier nanoClock, long deadlineNanos) {
        this.nanoClock = nanoClock;
        this.deadlineNanos = deadlineNanos;
    }

    /** Starts the fixed 30-minute bound now. */
    public static TaskDeadline start() {
        return after(TASK_BOUND, System::nanoTime);
    }

    /** Test seam: a bound of {@code budget} measured by the supplied monotonic clock. */
    public static TaskDeadline after(Duration budget, LongSupplier nanoClock) {
        return new TaskDeadline(nanoClock, nanoClock.getAsLong() + budget.toNanos());
    }

    public boolean bounded() {
        return nanoClock != null;
    }

    /** Remaining budget in milliseconds, never negative; {@link Long#MAX_VALUE} when unbounded. */
    public long remainingMillis() {
        if (!bounded()) return Long.MAX_VALUE;
        return Math.max(0L, (deadlineNanos - nanoClock.getAsLong()) / 1_000_000L);
    }

    /** Throws if the bound is exhausted or the worker was interrupted (e.g. shutdown). */
    public void check() throws InterruptedException {
        if (!bounded()) return;
        if (Thread.interrupted()) throw new InterruptedException("full-catalog task interrupted");
        if (deadlineNanos - nanoClock.getAsLong() <= 0) throw new ExceededException();
    }

    /**
     * Limits every statement of the current transaction to the remaining budget. Must be called with
     * auto-commit off; {@code SET LOCAL} ends with the transaction, so pooled connections stay clean.
     */
    public void limitTransaction(Connection con) throws SQLException {
        if (!bounded()) return;
        long ms = Math.max(1L, remainingMillis());
        try (Statement st = con.createStatement()) {
            st.execute("SET LOCAL statement_timeout = " + ms);
        }
    }
}
