package web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;
import web.task.TaskAlreadyRunningException;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Status mapping for the synchronization/refresh trigger and task-status routes (STORY-API-003; the
 * global-sync trigger was added to its scope by STORY-API-004 and the price-refresh trigger by
 * STORY-API-005, neither of which needed new code here — their 400/409/404 mean the same things and
 * read the same way, and sharing them is what lets a caller handle all three triggers identically).
 *
 * <p>A separate advice from {@link ApiExceptionHandler} on purpose. That one's messages, and its 500
 * {@code CALCULATION_FAILED}, describe a crafting calculation — the wrong thing to tell a caller
 * about a synchronization — and the calculation routes' established contract must not be reworded to
 * make room for this one. Codes that mean the same thing keep the same name, and
 * {@link ApiValidationException} is reused unchanged, so a caller's error handling still works
 * across both boundaries. Both advices are scoped with {@code assignableTypes}, so neither intercepts
 * the framework's own 404/405 routing responses.
 *
 * <p>Nothing here maps a failure of the synchronization itself. That failure belongs to the task, not
 * to the request that scheduled it, and is reported by {@link SyncTaskApiController} — these routes
 * only accept work and look it up, so they have no calculation-style 500 path of their own.
 */
@RestControllerAdvice(assignableTypes = {
        AccountSyncApiController.class,
        GlobalSyncApiController.class,
        PriceRefreshApiController.class,
        SyncTaskApiController.class})
public class SyncApiExceptionHandler {

    private static final Logger LOG = Logger.getLogger(SyncApiExceptionHandler.class.getName());

    /** Request could not describe a trigger; nothing was scheduled. */
    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ApiValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    /** Body was present but not JSON, or structurally unusable. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        LOG.log(Level.FINE, "Unreadable sync request body", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("MALFORMED_REQUEST",
                        "Request body could not be read as JSON matching the sync request contract"));
    }

    /**
     * An equivalent synchronization has not finished. Refused rather than queued, and the running
     * task is named so the caller can follow that one instead of starting overlapping work.
     */
    @ExceptionHandler(TaskAlreadyRunningException.class)
    public ResponseEntity<ApiErrorResponse> handleAlreadyRunning(TaskAlreadyRunningException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("SYNC_ALREADY_RUNNING",
                        "A " + e.operation() + " task is already in progress as task "
                                + e.existingTaskId() + "; query its status instead of starting another"));
    }

    /** Identifier this process cannot resolve; see {@link UnknownTaskException} for the causes. */
    @ExceptionHandler(UnknownTaskException.class)
    public ResponseEntity<ApiErrorResponse> handleUnknownTask(UnknownTaskException e) {
        LOG.log(Level.FINE, "Status requested for an unknown task", e);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("TASK_NOT_FOUND",
                        "No task with that identifier is known to this server; task records are held in "
                                + "memory only and do not survive a restart"));
    }
}
