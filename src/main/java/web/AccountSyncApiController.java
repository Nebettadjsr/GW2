package web;

import application.AccountRefreshService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.SyncTaskAcceptedResponse;
import web.task.BackgroundTaskService;

import java.net.URI;
import java.util.Map;

/**
 * HTTP trigger for account synchronization (STORY-API-003, TARGET_ARCHITECTURE.md §9/§22/§23).
 *
 * <p>Asynchronous by decision rather than by measurement: UD-007 requires operations that call the
 * GW2 API for synchronization to run as backend tasks with status reporting, because ArenaNet's rate
 * limits force batching and holding one HTTP request open for the whole sequence is needlessly
 * fragile. No duration was measured or assumed to reach that choice.
 *
 * <p>Thin by construction: it validates the request, submits
 * {@link AccountRefreshService#refreshAll()} as the task body, and returns the identifier. The step
 * order (account bank, account materials, account recipes, then every character's crafting and
 * recipes), the persistence and the short-circuit on the first failing step all stay inside that
 * application service — no individual sync step is reachable from this route, and none of the
 * sequence is restated here.
 *
 * <p>The JavaFX "Sync Account" button keeps calling the same use case in process, unchanged; this is
 * a second entry point over it, not a replacement.
 */
@RestController
@RequestMapping("/api/sync")
public class AccountSyncApiController {

    /**
     * Admission key for account synchronization. One unfinished account sync at a time: the sequence
     * writes account-wide data and is GW2-API rate limited, so a second concurrent run would
     * duplicate the same work against the same rows.
     */
    static final String ACCOUNT_SYNC_OPERATION = "ACCOUNT_SYNC";

    private final AccountRefreshService accountRefreshService;
    private final BackgroundTaskService taskService;

    public AccountSyncApiController(AccountRefreshService accountRefreshService,
                                    BackgroundTaskService taskService) {
        this.accountRefreshService = accountRefreshService;
        this.taskService = taskService;
    }

    /**
     * Accepts an account synchronization and returns without waiting for it.
     *
     * <p>The use case takes no inputs, so neither does the route: the body must be absent or an empty
     * JSON object. An unexpected field is rejected rather than silently ignored, so a caller that
     * believes it is parameterising the sync finds out.
     *
     * <p>Status contract: <b>202</b> with the task identifier, its operation and its status path,
     * plus a {@code Location} header pointing at the same path — acceptance only, since the work has
     * not run yet and this body therefore carries no outcome; <b>400</b>
     * {@code INVALID_REQUEST}/{@code MALFORMED_REQUEST}, raised before a task exists, so a rejected
     * request synchronizes nothing; <b>409</b> {@code SYNC_ALREADY_RUNNING} while a previous account
     * sync is unfinished, naming that task instead of starting a second one. Success and failure of
     * the work itself are reported by {@link SyncTaskApiController}, never here. See
     * {@link SyncApiExceptionHandler}.
     */
    @PostMapping(path = "/account", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SyncTaskAcceptedResponse> syncAccount(
            @RequestBody(required = false) Map<String, Object> request) {

        rejectAnyRequestField(request);

        String taskId = taskService.submit(ACCOUNT_SYNC_OPERATION, accountRefreshService::refreshAll);
        String statusPath = SyncTaskApiController.statusPath(taskId);

        return ResponseEntity.accepted()
                .location(URI.create(statusPath))
                .body(new SyncTaskAcceptedResponse(taskId, ACCOUNT_SYNC_OPERATION, statusPath));
    }

    private static void rejectAnyRequestField(Map<String, Object> request) {
        if (request != null && !request.isEmpty()) {
            throw new ApiValidationException(
                    "POST /api/sync/account accepts no request fields; send no body or an empty JSON object");
        }
    }
}
