package web;

import application.ScopedAccountRefreshCoordinator;
import application.access.AccountAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import web.access.AccountAccessHttp;
import web.dto.ApiErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.SyncTaskStatusResponse;
import web.task.BackgroundTaskService;
import web.task.TaskSnapshot;

/**
 * Status route for the background synchronization tasks (STORY-API-003, UD-007's "asynchronous
 * backend tasks with status reporting").
 *
 * <p>One route for every operation rather than one per trigger: the task facility is shared, the
 * status contract does not vary by operation, and the operation is reported in the body. A later
 * sync/refresh trigger therefore needs no status route of its own.
 *
 * <p>Thin: it looks the identifier up and maps what it finds. It starts nothing, cancels nothing and
 * waits for nothing.
 */
@RestController
@RequestMapping(SyncTaskApiController.TASKS_PATH)
public class SyncTaskApiController {

    static final String TASKS_PATH = "/api/sync/tasks";

    private final BackgroundTaskService taskService;
    private final AccountAccessService access;
    private final ScopedAccountRefreshCoordinator coordinator;

    /** Global-task lookup only: with no account facility, every account task is reported unknown. */
    public SyncTaskApiController(BackgroundTaskService taskService) {
        this(taskService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SyncTaskApiController(BackgroundTaskService taskService, AccountAccessService access,
                                 ScopedAccountRefreshCoordinator coordinator) {
        this.taskService = taskService;
        this.access = access;
        this.coordinator = coordinator;
    }

    /** Path of the status resource for {@code taskId}; the triggers hand this to the caller. */
    static String statusPath(String taskId) {
        return TASKS_PATH + "/" + taskId;
    }

    /**
     * Reports one task's lifecycle state.
     *
     * <p>Status contract: <b>200</b> for a known identifier, whatever its state — an unfinished,
     * a succeeded and a failed task are all a successful <em>lookup</em>, distinguished by the body's
     * {@code state}, never by the HTTP status. <b>404</b> {@code TASK_NOT_FOUND} for an identifier
     * this process cannot resolve (see {@link UnknownTaskException}).
     */
    @GetMapping(path = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        TaskSnapshot task = taskService.find(taskId)
                .orElseThrow(() -> new UnknownTaskException(taskId));

        if (!ScopedAccountRefreshCoordinator.OPERATION.equals(task.operation())) {
            return ResponseEntity.ok(SyncTaskStatusMapper.toResponse(task));
        }
        // An account task: only a live, context-matched session whose grants cover the task's
        // sources may observe it. Foreign and unknown identifiers are indistinguishable (404).
        if (access == null || coordinator == null) throw new UnknownTaskException(taskId);
        var presented = AccountAccessHttp.presentedSessionId(request);
        if (presented.isEmpty()) return denied(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        var resolution = access.resolve(presented.get(), AccountAccessHttp.assertedAccessContext(request));
        switch (resolution.outcome()) {
            case NO_SESSION, EXPIRED -> { return denied(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED"); }
            case CONTEXT_MISMATCH -> { return denied(HttpStatus.FORBIDDEN, "ACCOUNT_ACCESS_DENIED"); }
            case AUTHENTICATED -> { /* ownership checked below */ }
        }
        var context = resolution.context();
        if (coordinator.observe(context, taskId).isEmpty()) throw new UnknownTaskException(taskId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, context.accessContext())
                .body(SyncTaskStatusMapper.toResponse(task));
    }

    private static ResponseEntity<ApiErrorResponse> denied(HttpStatus status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(code, status == HttpStatus.UNAUTHORIZED
                        ? "Prove a GW2 API key to continue"
                        : "This session is not authorized for this account operation"));
    }
}
