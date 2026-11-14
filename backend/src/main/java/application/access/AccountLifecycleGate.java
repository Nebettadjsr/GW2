package application.access;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The transient, process-local account lifecycle gate keyed by verified GUID (STORY-DELETE-001).
 *
 * <p>It is an extension of the existing account-scoped guards, not a durable tombstone or an identity
 * framework. Per GUID it distinguishes brief admitted account operations (the shared side, which
 * includes session installation after proof) from deletion in progress (the exclusive side). Pending,
 * active, queued or coalesced refresh work is not counted here: deletion asks the caller's
 * {@code unfinishedWork} supplier, and refresh admission asks {@link #requireOpen}.
 *
 * <p>Other GUIDs and global work never touch this gate. Entries are removed when idle, so the map is
 * bounded by the accounts with an operation in flight.
 */
public final class AccountLifecycleGate {

    /** Longest a new operation waits for a running deletion, or deletion for admitted operations. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

    /** The gate is closed for this account (deletion in progress) or the wait bound passed. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("the account is not accepting operations right now", null, false, false);
        }
    }

    /** A held shared admission; closing is idempotent. */
    public interface Admission extends AutoCloseable {
        @Override void close();
    }

    public enum DeletionStart { ENTERED, WORK_IN_PROGRESS, TIMED_OUT }

    /** A deletion holding the exclusive side; closing reopens the gate on every path. */
    public interface Exclusive extends AutoCloseable {
        @Override void close();
    }

    public record DeletionAttempt(DeletionStart start, Exclusive exclusive) {}

    private static final class State {
        int shared;
        boolean deleting;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Duration wait;

    public AccountLifecycleGate() {
        this(DEFAULT_WAIT);
    }

    public AccountLifecycleGate(Duration wait) {
        this.wait = wait;
    }

    /** Enters the shared side, waiting (bounded) while a deletion for this account runs. */
    public Admission enter(UUID accountId) {
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (this) {
            State state = states.computeIfAbsent(accountId, k -> new State());
            try {
                while (state.deleting) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) throw new Unavailable();
                    waitNanos(left);
                    state = states.computeIfAbsent(accountId, k -> new State());
                }
            } catch (RuntimeException e) {
                dropIfIdle(accountId);
                throw e;
            }
            state.shared++;
        }
        return new Admission() {
            private boolean closed;

            @Override public void close() {
                synchronized (AccountLifecycleGate.this) {
                    if (closed) return;
                    closed = true;
                    State s = states.get(accountId);
                    if (s != null) s.shared--;
                    dropIfIdle(accountId);
                    AccountLifecycleGate.this.notifyAll();
                }
            }
        };
    }

    /** Refresh admission: refused while deletion for this GUID is in progress. */
    public synchronized void requireOpen(UUID accountId) {
        State state = states.get(accountId);
        if (state != null && state.deleting) throw new Unavailable();
    }

    /**
     * Begins deletion. If unfinished refresh work exists nothing is changed ({@link
     * DeletionStart#WORK_IN_PROGRESS}). Otherwise new operations are shut out and the call waits for
     * already-admitted ones to leave; the work check is repeated after they have drained, since an
     * admitted request may have admitted a refresh.
     *
     * @param ownAdmissions shared admissions the caller itself holds (it must not wait on itself)
     */
    public DeletionAttempt beginDeletion(UUID accountId, BooleanSupplier unfinishedWork, int ownAdmissions) {
        if (unfinishedWork.getAsBoolean()) return new DeletionAttempt(DeletionStart.WORK_IN_PROGRESS, null);
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (this) {
            State state = states.computeIfAbsent(accountId, k -> new State());
            while (state.deleting) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return new DeletionAttempt(DeletionStart.TIMED_OUT, null);
                waitNanos(left);
                state = states.computeIfAbsent(accountId, k -> new State());
            }
            state.deleting = true;
            while (state.shared > ownAdmissions) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    reopen(accountId);
                    return new DeletionAttempt(DeletionStart.TIMED_OUT, null);
                }
                waitNanos(left);
            }
            if (unfinishedWork.getAsBoolean()) {
                reopen(accountId);
                return new DeletionAttempt(DeletionStart.WORK_IN_PROGRESS, null);
            }
        }
        return new DeletionAttempt(DeletionStart.ENTERED, new Exclusive() {
            private boolean closed;

            @Override public void close() {
                synchronized (AccountLifecycleGate.this) {
                    if (closed) return;
                    closed = true;
                    reopen(accountId);
                }
            }
        });
    }

    private void reopen(UUID accountId) {
        State s = states.get(accountId);
        if (s != null) s.deleting = false;
        dropIfIdle(accountId);
        notifyAll();
    }

    private void dropIfIdle(UUID accountId) {
        State s = states.get(accountId);
        if (s != null && s.shared == 0 && !s.deleting) states.remove(accountId);
    }

    private void waitNanos(long nanos) {
        try {
            long ms = Math.max(1, nanos / 1_000_000);
            wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        }
    }
}
