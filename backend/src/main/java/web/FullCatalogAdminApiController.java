package web;

import application.FullCatalogAdministrationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.access.OwnerAccess;
import web.dto.SyncTaskAcceptedResponse;
import web.task.BackgroundTaskService;

import java.net.URI;
import java.util.Map;

/**
 * Owner-only full-catalog operations (STORY-STATUS-004): full item catalog, full recipe catalog and
 * full Trading Post synchronization. Authorization precedes validation and admission, exactly as
 * for {@link GlobalSyncApiController}; observation reuses {@link SyncTaskApiController}.
 *
 * <p>Item and recipe mutation share the {@code GLOBAL_SYNC} exclusion key with the global sync, so
 * they can never mutate the shared catalog tables concurrently with it or with each other; each
 * reports its own stable operation name. Full TP has its own exclusion and is independent of
 * account tasks and of ordinary Profit/Discovery refreshes.
 */
@RestController
@RequestMapping("/api/sync/full")
public class FullCatalogAdminApiController {

    static final String ITEMS_PATH = "/api/sync/full/items";
    static final String RECIPES_PATH = "/api/sync/full/recipes";
    static final String TRADING_POST_PATH = "/api/sync/full/trading-post";

    static final String FULL_ITEM_CATALOG = "FULL_ITEM_CATALOG";
    static final String FULL_RECIPE_CATALOG = "FULL_RECIPE_CATALOG";
    static final String FULL_TP_CATALOG = "FULL_TP_CATALOG";
    /** Shared exclusion key of the global sync and the item/recipe catalog tasks. */
    static final String SHARED_CATALOG_EXCLUSION = GlobalSyncApiController.GLOBAL_SYNC_OPERATION;

    private final FullCatalogAdministrationService service;
    private final BackgroundTaskService taskService;
    private final OwnerAccess ownerAccess;

    public FullCatalogAdminApiController(FullCatalogAdministrationService service,
                                         BackgroundTaskService taskService, OwnerAccess ownerAccess) {
        this.service = service;
        this.taskService = taskService;
        this.ownerAccess = ownerAccess;
    }

    @PostMapping(path = "/items", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> items(@RequestBody(required = false) Map<String, Object> request, HttpServletRequest http) {
        return admit(ITEMS_PATH, FULL_ITEM_CATALOG, SHARED_CATALOG_EXCLUSION, service::syncItems, request, http);
    }

    @PostMapping(path = "/recipes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> recipes(@RequestBody(required = false) Map<String, Object> request, HttpServletRequest http) {
        return admit(RECIPES_PATH, FULL_RECIPE_CATALOG, SHARED_CATALOG_EXCLUSION, service::syncRecipes, request, http);
    }

    @PostMapping(path = "/trading-post", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tradingPost(@RequestBody(required = false) Map<String, Object> request, HttpServletRequest http) {
        return admit(TRADING_POST_PATH, FULL_TP_CATALOG, FULL_TP_CATALOG, service::syncTradingPost, request, http);
    }

    private ResponseEntity<?> admit(String path, String operation, String exclusionKey,
                                    BackgroundTaskService.TaskBody body, Map<String, Object> request,
                                    HttpServletRequest http) {
        ResponseEntity<?> denial = ownerAccess.deny(http);
        if (denial != null) return denial;
        SyncRequestValidation.rejectAnyRequestField(path, request);
        String taskId = taskService.submitAdministrative(operation, exclusionKey, body);
        String statusPath = SyncTaskApiController.statusPath(taskId);
        return ResponseEntity.accepted().location(URI.create(statusPath))
                .body(new SyncTaskAcceptedResponse(taskId, operation, statusPath));
    }
}
