package web.task;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal in-process background-task facility for the HTTP sync/refresh triggers (STORY-API-003,
 * {@code TARGET_ARCHITECTURE.md} §23: the first implementation stays "as simple as practical", and
 * no queue or distributed job platform is required).
 *
 * <p>Holds no Spring, HTTP or domain dependency: a caller supplies an operation key and a body, gets
 * an identifier back immediately, and polls {@link #find(String)}. Any later sync/refresh route can
 * reuse it by passing its own operation key.
 *
 * <p><b>Documented behavior</b> — the part callers and {@code CURRENT_ARCHITECTURE.md} rely on:
 *
 * <ul>
 *   <li><b>Admission:</b> at most one unfinished task per operation key. Submitting a second one
 *       while the first is {@code PENDING} or {@code RUNNING} throws
 *       {@link TaskAlreadyRunningException} carrying the existing identifier; nothing is queued
 *       behind it. Different operation keys are independent and may run at the same time.</li>
 *   <li><b>Execution:</b> one virtual thread per task, which suits the I/O-bound GW2 API and
 *       database work the bodies do. Concurrency is bounded by the admission rule above, not by a
 *       pool size.</li>
 *   <li><b>Lifetime:</b> records live in this process's memory only. The most recent
 *       {@value #RETAINED_TASKS} are retained; on submission, older <em>terminal</em> records are
 *       evicted, and an unfinished record is never evicted. An evicted identifier is
 *       indistinguishable from one that never existed.</li>
 *   <li><b>Restart:</b> nothing is persisted. A restart loses every record, including that of a task
 *       in flight, and {@link #close()} interrupts a running body. Work a body already committed to
 *       the database stays committed — this facility performs no rollback and must not be described
 *       as if it did.</li>
 * </ul>
 *
 * <p>Thread-safe: every read and write of the task table and of a task's mutable fields happens
 * under this instance's monitor. Task bodies run outside that lock.
 */
public class BackgroundTaskService implements AutoCloseable {

    /** Work to run on a background thread; a thrown exception makes the task {@link TaskState#FAILED}. */
    public interface TaskBody {
        void run() throws Exception;
    }

    private static final Logger LOG = Logger.getLogger(BackgroundTaskService.class.getName());

    /** Upper bound on retained task records; see the lifetime note above. */
    private static final int RETAINED_TASKS = 100;

    private final Executor executor;
    private final ExecutorService ownedExecutor;

    public BackgroundTaskService() {
        this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.executor = ownedExecutor;
    }

    /** Test seam: runs task bodies on the supplied executor, so a deferred one keeps tasks PENDING. */
    public BackgroundTaskService(Executor executor) {
        this.ownedExecutor = null;
        this.executor = executor;
    }

    /** A task snapshot with its trusted administrative classification, read under one lock. */
    public record Classified(TaskSnapshot task, boolean administrative) {}

    /** Submission-ordered so eviction can start at the oldest record. Guarded by {@code this}. */
    private final Map<String, MutableTask> tasks = new LinkedHashMap<>();

    /**
     * Admits a task and starts it on a background thread.
     *
     * @param operation operation key, also reported as the task's operation
     * @param body      the work to run
     * @return the new task's identifier
     * @throws TaskAlreadyRunningException if {@code operation} still has an unfinished task
     */
    public String submit(String operation, TaskBody body) {
        return submit(operation, operation, body);
    }

    /**
     * Admits a task whose exclusion is keyed separately from its reported operation, so one
     * operation kind can run once per owner (STORY-SYNC-006: one unfinished ACCOUNT_SYNC per
     * account). The exclusion key is never reported; only {@code operation} is.
     */
    public String submit(String operation, String exclusionKey, TaskBody body) {
        MutableTask task;
        synchronized (this) {
            unfinishedTaskId(exclusionKey).ifPresent(existing -> {
                throw new TaskAlreadyRunningException(operation, existing);
            });
            evictOldestTerminalRecords();
            task = new MutableTask(UUID.randomUUID().toString(), operation, exclusionKey, Instant.now());
            tasks.put(task.id, task);
        }
        executor.execute(() -> run(task, body));
        return task.id;
    }

    /**
     * Admits a task classified as owner-administrative by the trusted server-side caller (the
     * external global action and the internal scheduler). Same admission rule as
     * {@link #submit(String, TaskBody)}; the classification is never derived from request data.
     */
    public String submitAdministrative(String operation, TaskBody body) {
        return submitAdministrative(operation, operation, body);
    }

    /**
     * Administrative admission whose exclusion is keyed separately from the reported operation, so
     * the full item and recipe catalog tasks share the {@code GLOBAL_SYNC} exclusion while reporting
     * their own stable operation names. The exclusion is released only after the body has exited.
     */
    public String submitAdministrative(String operation, String exclusionKey, TaskBody body) {
        MutableTask task;
        synchronized (this) {
            unfinishedTaskId(exclusionKey).ifPresent(existing -> {
                throw new TaskAlreadyRunningException(operation, existing);
            });
            evictOldestTerminalRecords();
            task = new MutableTask(UUID.randomUUID().toString(), operation, exclusionKey, Instant.now());
            task.administrative = true;
            tasks.put(task.id, task);
        }
        executor.execute(() -> run(task, body));
        return task.id;
    }

    /** True when the identifier names a retained task admitted through {@link #submitAdministrative}. */
    public synchronized boolean isAdministrative(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task != null && task.administrative;
    }

    /** Starts work unless an unfinished task for this operation already exists, returning its ID. */
    public String submitOrExisting(String operation, TaskBody body) {
        synchronized (this) {
            Optional<String> existing = unfinishedTaskId(operation);
            if (existing.isPresent()) return existing.get();
        }
        try { return submit(operation, body); }
        catch (TaskAlreadyRunningException raced) { return raced.existingTaskId(); }
    }

    /** @return the task's current state, or empty if this process knows no such identifier */
    public synchronized Optional<TaskSnapshot> find(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task == null ? Optional.empty() : Optional.of(task.snapshot());
    }

    /** Atomic lookup: the snapshot and its classification can never disagree after eviction. */
    public synchronized Optional<Classified> findClassified(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task == null ? Optional.empty() : Optional.of(new Classified(task.snapshot(), task.administrative));
    }

    /** Interrupts anything still running; already-committed work is unaffected. */
    @Override
    public void close() {
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    private void run(MutableTask task, TaskBody body) {
        markRunning(task);
        try {
            body.run();
            finish(task, TaskState.SUCCEEDED, null);
        } catch (Exception e) {
            // The caller sees only a code and a fixed message, so the detail has to be kept here.
            LOG.log(Level.SEVERE, "Background task " + task.id + " (" + task.operation + ") failed", e);
            finish(task, TaskState.FAILED, e);
        }
    }

    private synchronized void markRunning(MutableTask task) {
        task.state = TaskState.RUNNING;
        task.startedAt = Instant.now();
    }

    private synchronized void finish(MutableTask task, TaskState state, Exception failure) {
        task.state = state;
        task.failure = failure;
        task.finishedAt = Instant.now();
    }

    private Optional<String> unfinishedTaskId(String operation) {
        return tasks.values().stream()
                .filter(task -> task.exclusionKey.equals(operation) && !task.state.isTerminal())
                .map(task -> task.id)
                .findFirst();
    }

    private void evictOldestTerminalRecords() {
        Iterator<MutableTask> oldestFirst = tasks.values().iterator();
        while (tasks.size() >= RETAINED_TASKS && oldestFirst.hasNext()) {
            if (oldestFirst.next().state.isTerminal()) {
                oldestFirst.remove();
            }
        }
    }

    /**
     * Mutable because a task's state is what changes as it runs. Every non-final field is read and
     * written only under the enclosing service's monitor; {@link #snapshot()} is the only way out.
     */
    private static final class MutableTask {

        private final String id;
        private final String operation;
        private final String exclusionKey;
        private final Instant submittedAt;

        private TaskState state = TaskState.PENDING;
        private Instant startedAt;
        private Instant finishedAt;
        private Exception failure;
        private boolean administrative;

        MutableTask(String id, String operation, String exclusionKey, Instant submittedAt) {
            this.id = id;
            this.operation = operation;
            this.exclusionKey = exclusionKey;
            this.submittedAt = submittedAt;
        }

        TaskSnapshot snapshot() {
            return new TaskSnapshot(id, operation, state, submittedAt, startedAt, finishedAt, failure);
        }
    }
}
