package web;

import application.BackgroundTasks;
import web.task.BackgroundTaskService;
import web.task.TaskAlreadyRunningException;
import web.task.TaskSnapshot;
import web.task.TaskState;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Adapts the shared in-process task facility to the application layer's {@link BackgroundTasks}
 * port, so freshness coordination can admit and observe refresh work without depending on the HTTP
 * boundary that owns the facility.
 *
 * <p>Translation only: the admission rule, the execution model and the retention bound all stay in
 * {@link BackgroundTaskService}. The failure code it maps is the one
 * {@code SyncTaskStatusMapper} already publishes for the same snapshot, so a caller reading the
 * freshness envelope and a caller polling the status route are told the same thing, and neither is
 * given the exception's own text.
 */
public final class BackgroundTaskServiceTasks implements BackgroundTasks {

    static final String DATA_STORE_FAILURE_CODE = "DATA_STORE_UNAVAILABLE";
    static final String SYNC_FAILURE_CODE = "SYNC_FAILED";

    private final BackgroundTaskService tasks;

    public BackgroundTaskServiceTasks(BackgroundTaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    public String submit(String operation, Work work) throws OperationAlreadyRunning {
        try {
            return tasks.submit(operation, work::run);
        } catch (TaskAlreadyRunningException existing) {
            throw new OperationAlreadyRunning(operation, existing.existingTaskId());
        }
    }

    @Override
    public Optional<Status> find(String taskId) {
        return tasks.find(taskId).map(BackgroundTaskServiceTasks::toStatus);
    }

    private static Status toStatus(TaskSnapshot task) {
        boolean failed = task.state() == TaskState.FAILED;
        return new Status(
                task.id(),
                task.operation(),
                task.state().name(),
                task.state().isTerminal(),
                failed,
                failed ? failureCode(task) : null,
                failed ? "Refresh work failed; previously synchronized data was not rolled back" : null);
    }

    private static String failureCode(TaskSnapshot task) {
        return task.failure() instanceof SQLException ? DATA_STORE_FAILURE_CODE : SYNC_FAILURE_CODE;
    }
}
