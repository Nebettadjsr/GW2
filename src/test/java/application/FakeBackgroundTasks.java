package application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic stand-in for the in-process task facility, shared by the refresh-coordination tests
 * (docs/TEST_STRATEGY.md §8: coordination is verified by controlled sequencing, not by sleeps).
 *
 * <p>It reproduces the two guarantees the application layer relies on — at most one unfinished task
 * per operation key, and a loud refusal carrying the existing identifier — but starts nothing
 * itself. A test calls {@link #run(String)} when it wants the body to execute, on the thread it
 * chooses, so every race in the coordinators can be driven to a specific interleaving instead of
 * being hoped for.
 */
final class FakeBackgroundTasks implements BackgroundTasks {

    record Admitted(String id, String operation, Work work) {
    }

    final List<Admitted> admitted = new ArrayList<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final List<String> forgotten = new ArrayList<>();
    private final List<Thread> started = new ArrayList<>();

    /**
     * When set, an admitted body starts on its own thread immediately, as the real facility does.
     * Tests that need the body to be genuinely in flight — "the response came back while the
     * refresh was still running" — turn this on and hold the gateway instead of the task.
     */
    boolean autoRun;

    @Override
    public String submit(String operation, Work work) throws OperationAlreadyRunning {
        String id;
        synchronized (this) {
            for (Admitted task : admitted) {
                if (task.operation().equals(operation) && !isTerminal(task.id())) {
                    throw new OperationAlreadyRunning(operation, task.id());
                }
            }
            id = operation + "#" + (admitted.size() + 1);
            admitted.add(new Admitted(id, operation, work));
            states.put(id, "RUNNING");
            if (!autoRun) return id;
        }
        Thread thread = new Thread(() -> run(id), "fake-task-" + id);
        synchronized (this) {
            started.add(thread);
        }
        thread.start();
        return id;
    }

    /** Waits for every auto-run body to finish, so a test cannot assert against a moving target. */
    void joinAll() throws InterruptedException {
        List<Thread> threads;
        synchronized (this) {
            threads = new ArrayList<>(started);
        }
        for (Thread thread : threads) thread.join(5_000);
    }

    @Override
    public synchronized Optional<Status> find(String taskId) {
        if (taskId == null || forgotten.contains(taskId)) return Optional.empty();
        String state = states.get(taskId);
        if (state == null) return Optional.empty();
        boolean failed = "FAILED".equals(state);
        return Optional.of(new Status(taskId, operationOf(taskId), state,
                failed || "SUCCEEDED".equals(state), failed,
                failed ? "SYNC_FAILED" : null, failed ? "Synchronization failed" : null));
    }

    /** Admits a task whose body this fake will never run: another entry point's unfinished work. */
    String admitForeign(String operation) {
        return submit(operation, () -> {
            throw new AssertionError("a foreign task's body must not be run by the coordinator");
        });
    }

    /** Runs one admitted task's body to completion on the calling thread, recording the outcome. */
    void run(String taskId) {
        Work work = workOf(taskId);
        try {
            work.run();
            synchronized (this) {
                states.put(taskId, "SUCCEEDED");
            }
        } catch (Exception failure) {
            synchronized (this) {
                states.put(taskId, "FAILED");
            }
        }
    }

    /** Runs a body without catching: for the tests that assert the task is told about the failure. */
    void runExpectingFailure(String taskId) {
        try {
            workOf(taskId).run();
            throw new AssertionError("expected " + taskId + " to fail");
        } catch (AssertionError propagate) {
            throw propagate;
        } catch (Exception expected) {
            synchronized (this) {
                states.put(taskId, "FAILED");
            }
        }
    }

    /** Marks a task terminal without running it: the foreign-task-has-ended transition. */
    synchronized void finish(String taskId) {
        states.put(taskId, "SUCCEEDED");
    }

    /** Makes an identifier unresolvable, as eviction or a restart would. */
    synchronized void forget(String taskId) {
        forgotten.add(taskId);
    }

    synchronized String stateOf(String taskId) {
        return states.get(taskId);
    }

    synchronized List<String> operations() {
        List<String> operations = new ArrayList<>();
        for (Admitted task : admitted) operations.add(task.operation());
        return operations;
    }

    synchronized long admittedFor(String operation) {
        return admitted.stream().filter(task -> task.operation().equals(operation)).count();
    }

    synchronized String lastIdFor(String operation) {
        String last = null;
        for (Admitted task : admitted) if (task.operation().equals(operation)) last = task.id();
        return last;
    }

    private synchronized Work workOf(String taskId) {
        for (Admitted task : admitted) if (task.id().equals(taskId)) return task.work();
        throw new AssertionError("no admitted task " + taskId);
    }

    private synchronized String operationOf(String taskId) {
        for (Admitted task : admitted) if (task.id().equals(taskId)) return task.operation();
        return null;
    }

    private synchronized boolean isTerminal(String taskId) {
        String state = states.get(taskId);
        return "SUCCEEDED".equals(state) || "FAILED".equals(state);
    }
}
