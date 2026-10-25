package web;

import application.GlobalDataRefreshService;
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
 * HTTP trigger for global-data synchronization (STORY-API-004, TARGET_ARCHITECTURE.md §9's
 * {@code POST /api/sync/global} example, §22/§23).
 *
 * <p>Asynchronous by decision rather than by measurement, for the same reason as the account trigger:
 * UD-007 requires operations that call the GW2 API for synchronization to run as backend tasks with
 * status reporting. No duration was measured or assumed to reach that choice.
 *
 * <p>Thin by construction: it validates the request, submits
 * {@link GlobalDataRefreshService#refreshAll()} as the task body, and returns the identifier. The
 * step order (tradeable items, then global recipes, then one crafting-graph rebuild), the
 * persistence, the rebuild itself and the short-circuit on the first failing step all stay inside
 * that application service — no individual sync step and no graph rebuild is reachable from this
 * route, and none of the sequence is restated here.
 *
 * <p>The JavaFX "Sync ALL tradeable Items, Recipes and (re)build Crafting Graph" button keeps
 * calling the same use case in process, unchanged; this is a second entry point over it, not a
 * replacement.
 *
 * <p>Status reporting is not duplicated either: {@link SyncTaskApiController} already serves every
 * operation, so this trigger adds a route but no status route.
 */
@RestController
@RequestMapping("/api/sync")
public class GlobalSyncApiController {

    /** This route's path, also used in its validation message. */
    static final String GLOBAL_SYNC_PATH = "/api/sync/global";

    /**
     * Admission key for global-data synchronization, and the {@code operation} reported by both the
     * acceptance and the status body.
     *
     * <p>One unfinished global sync at a time: the sequence rewrites the account-independent
     * tradeable-item and recipe tables and then rebuilds the single
     * {@code crafting_graph_cache.json}, so a second concurrent run would repeat the same GW2 API
     * work against the same rows and have two rebuilds writing that one cache file.
     *
     * <p>Distinct from {@link AccountSyncApiController#ACCOUNT_SYNC_OPERATION}, so the facility's
     * per-key admission rule leaves the two independent: a global sync and an account sync may be
     * accepted and run at the same time, each refusing only a second submission of <em>its own</em>
     * operation. That is the existing policy rather than a new judgement about whether the two are
     * safe together; what can be observed is that they write disjoint tables (this one
     * {@code tp_tradeable_items}/{@code recipes}/{@code recipe_ingredients} plus the cache file, the
     * other {@code account_*}/{@code character*}), and that they share the GW2 API rate budget and
     * the database, so running both at once makes each slower.
     */
    static final String GLOBAL_SYNC_OPERATION = "GLOBAL_SYNC";

    private final GlobalDataRefreshService globalDataRefreshService;
    private final BackgroundTaskService taskService;

    public GlobalSyncApiController(GlobalDataRefreshService globalDataRefreshService,
                                   BackgroundTaskService taskService) {
        this.globalDataRefreshService = globalDataRefreshService;
        this.taskService = taskService;
    }

    /**
     * Accepts a global-data synchronization and returns without waiting for it.
     *
     * <p>The use case takes no inputs, so neither does the route: the body must be absent or an empty
     * JSON object (see {@link SyncRequestValidation}).
     *
     * <p>Status contract, identical in shape to the account trigger's: <b>202</b> with the task
     * identifier, its operation and its status path, plus a {@code Location} header pointing at the
     * same path — acceptance only, since the work has not run yet and this body therefore carries no
     * outcome; <b>400</b> {@code INVALID_REQUEST}/{@code MALFORMED_REQUEST}, raised before a task
     * exists, so a rejected request synchronizes nothing; <b>409</b> {@code SYNC_ALREADY_RUNNING}
     * while a previous global sync is unfinished, naming that task instead of starting a second one.
     * Success and failure of the work itself are reported by {@link SyncTaskApiController}, never
     * here. See {@link SyncApiExceptionHandler}.
     */
    @PostMapping(path = "/global", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SyncTaskAcceptedResponse> syncGlobalData(
            @RequestBody(required = false) Map<String, Object> request) {

        SyncRequestValidation.rejectAnyRequestField(GLOBAL_SYNC_PATH, request);

        String taskId = taskService.submit(GLOBAL_SYNC_OPERATION, globalDataRefreshService::refreshAll);
        String statusPath = SyncTaskApiController.statusPath(taskId);

        return ResponseEntity.accepted()
                .location(URI.create(statusPath))
                .body(new SyncTaskAcceptedResponse(taskId, GLOBAL_SYNC_OPERATION, statusPath));
    }
}
