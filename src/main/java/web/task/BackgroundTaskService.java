package web.task;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

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
        MutableTask task;
        synchronized (this) {
            unfinishedTaskId(operation).ifPresent(existing -> {
                throw new TaskAlreadyRunningException(operation, existing);
            });
            evictOldestTerminalRecords();
            task = new MutableTask(UUID.randomUUID().toString(), operation, Instant.now());
            tasks.put(task.id, task);
        }
        executor.execute(() -> run(task, body));
        return task.id;
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

    /** Interrupts anything still running; already-committed work is unaffected. */
    @Override
    public void close() {
        executor.shutdownNow();
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
                .filter(task -> task.operation.equals(operation) && !task.state.isTerminal())
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
        private final Instant submittedAt;

        private TaskState state = TaskState.PENDING;
        private Instant startedAt;
        private Instant finishedAt;
        private Exception failure;

        MutableTask(String id, String operation, Instant submittedAt) {
            this.id = id;
            this.operation = operation;
            this.submittedAt = submittedAt;
        }

        TaskSnapshot snapshot() {
            return new TaskSnapshot(id, operation, state, submittedAt, startedAt, finishedAt, failure);
        }
    }
}
