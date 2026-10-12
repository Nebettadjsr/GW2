package application;

import java.util.Optional;

/**
 * The application layer's view of the existing in-process background-task facility
 * (TARGET_ARCHITECTURE.md §9.1: "Account refresh and TP refresh remain separate operations in the
 * existing asynchronous in-process task facility").
 *
 * <p>A port rather than a direct dependency, so freshness coordination stays independent of the
 * HTTP boundary that owns the facility and of its transport DTOs, and so the deterministic
 * coordination tests can drive admission and completion without threads of their own.
 *
 * <p>Only two guarantees are relied upon, both already documented on the facility: at most one
 * unfinished task per operation key, and a submission that would break that rule fails loudly with
 * the existing identifier rather than queueing behind it.
 */
public interface BackgroundTasks {

    /** Work to run away from the caller's thread; a thrown exception makes the task failed. */
    interface Work {
        void run() throws Exception;
    }

    /**
     * Admits {@code work} under {@code operation}.
     *
     * @return the new task's identifier
     * @throws OperationAlreadyRunning if that operation still has an unfinished task, carrying it
     */
    String submit(String operation, Work work) throws OperationAlreadyRunning;

    /** The task's last reported state, or empty when this process can no longer identify it. */
    Optional<Status> find(String taskId);

    /**
     * @param terminal     the task will not change state again
     * @param failed       the task ended by throwing
     * @param failureCode  stable code for a failed task, null otherwise
     * @param failureMessage disclosure-safe message for a failed task, null otherwise
     */
    record Status(String taskId,
                  String operation,
                  String state,
                  boolean terminal,
                  boolean failed,
                  String failureCode,
                  String failureMessage) {
    }

    /** The facility refused a second unfinished task for one operation key. */
    final class OperationAlreadyRunning extends RuntimeException {

        private final String operation;
        private final String existingTaskId;

        public OperationAlreadyRunning(String operation, String existingTaskId) {
            super("operation " + operation + " already has unfinished task " + existingTaskId);
            this.operation = operation;
            this.existingTaskId = existingTaskId;
        }

        public String operation() {
            return operation;
        }

        public String existingTaskId() {
            return existingTaskId;
        }
    }
}
