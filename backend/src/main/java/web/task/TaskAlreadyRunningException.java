package web.task;

/**
 * Thrown by {@link BackgroundTaskService#submit} when the operation still has an unfinished task
 * (STORY-API-003). Overlapping work is refused rather than queued, and the existing identifier is
 * carried along so the caller can be pointed at the task that is already doing the job.
 */
public class TaskAlreadyRunningException extends RuntimeException {

    private final String operation;
    private final String existingTaskId;

    public TaskAlreadyRunningException(String operation, String existingTaskId) {
        super("Operation " + operation + " already has unfinished task " + existingTaskId);
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
