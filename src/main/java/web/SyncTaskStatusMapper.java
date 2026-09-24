package web;

import web.dto.ApiErrorResponse;
import web.dto.SyncTaskStatusResponse;
import web.task.TaskSnapshot;
import web.task.TaskState;

import java.sql.SQLException;
import java.time.Instant;

/**
 * Copies a {@link TaskSnapshot} into the transport status DTO (STORY-API-003). Translation only: it
 * makes no judgement about the work, it reports the state the task facility recorded.
 *
 * <p>Applies the boundary's existing error-disclosure convention (see {@link ApiExceptionHandler}): a
 * failed task reports a stable code and a fixed message, never the exception's own text — that
 * detail is logged where the failure happened, in {@code web.task.BackgroundTaskService}. The code
 * for a data-store failure is the one the calculation routes already use, so a caller reads it the
 * same way on either boundary.
 *
 * <p>The failure message says explicitly that completed steps were not rolled back. The synchronizing
 * use case is a straight-line sequence that stops at its first failing step and has never had
 * partial-completion handling, so a failure genuinely can leave earlier steps' writes in place. This
 * boundary reports that instead of implying either an undo or a completion.
 */
final class SyncTaskStatusMapper {

    private static final String NO_ROLLBACK =
            "; steps that had already completed were not rolled back";

    private SyncTaskStatusMapper() {
    }

    static SyncTaskStatusResponse toResponse(TaskSnapshot task) {
        return new SyncTaskStatusResponse(
                task.id(),
                task.operation(),
                task.state().name(),
                text(task.submittedAt()),
                text(task.startedAt()),
                text(task.finishedAt()),
                task.state() == TaskState.FAILED ? failure(task) : null);
    }

    private static ApiErrorResponse failure(TaskSnapshot task) {
        if (task.failure() instanceof SQLException) {
            return new ApiErrorResponse("DATA_STORE_UNAVAILABLE",
                    "Synchronization failed reading or writing the data store" + NO_ROLLBACK);
        }
        return new ApiErrorResponse("SYNC_FAILED",
                "Synchronization failed" + NO_ROLLBACK);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
