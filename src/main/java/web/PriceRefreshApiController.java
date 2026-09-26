package web;

import application.TradingPostPriceRefreshService;
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
 * HTTP trigger for Trading Post price refresh (STORY-API-005, TARGET_ARCHITECTURE.md §9's
 * {@code POST /api/prices/refresh} example, §22/§23).
 *
 * <p>Asynchronous by decision rather than by measurement, for the same reason as the two sync
 * triggers: UD-007 requires operations that call the GW2 API for synchronization to run as backend
 * tasks with status reporting, and both variants fetch quotes from the rate-limited commerce
 * endpoint in batches. No duration was measured or assumed to reach that choice.
 *
 * <p>Thin by construction: it validates the request, submits one existing use-case method as the
 * task body ({@link PriceRefreshVariant#bodyOf}), and returns the identifier. Which items are
 * relevant ({@code sync.tp.relevance.CraftingProfitItemCollector}/{@code DiscoveryItemCollector},
 * including their ten-minute freshness filter), the batching, the quote fetching and the
 * {@code tp_prices} persistence all stay where they already are, behind
 * {@link TradingPostPriceRefreshService}; none of it is reachable from or restated at this route,
 * and no calculation runs here.
 *
 * <p>Two consequences of that thinness are part of the contract and are not hidden:
 * <ul>
 *   <li><b>A succeeded task does not mean prices were fetched.</b> Each collector skips items whose
 *       stored quote is younger than ten minutes, so a refresh issued shortly after another one can
 *       legitimately select no items and succeed having called the GW2 API not at all. The service
 *       reports no item count, so neither does this route.</li>
 *   <li><b>A failed task says nothing about how much was written.</b> The refresh commits per
 *       fetched batch, so earlier batches stay committed — which is exactly what the shared failure
 *       message already says. How many items were refreshed before the failure is not something the
 *       service exposes.</li>
 * </ul>
 *
 * <p>Both JavaFX "Refresh Trade Post Prices" buttons keep calling the same two methods in process,
 * unchanged; this is a second entry point over them, not a replacement, and the admission rule below
 * applies only to tasks this route accepts.
 *
 * <p>Status reporting is not duplicated: {@link SyncTaskApiController} already serves every
 * operation, so this trigger adds a route but no status route.
 */
@RestController
@RequestMapping("/api/prices")
public class PriceRefreshApiController {

    /** This route's path, also used in its validation message. */
    static final String PRICE_REFRESH_PATH = "/api/prices/refresh";

    private final TradingPostPriceRefreshService priceRefreshService;
    private final BackgroundTaskService taskService;

    public PriceRefreshApiController(TradingPostPriceRefreshService priceRefreshService,
                                     BackgroundTaskService taskService) {
        this.priceRefreshService = priceRefreshService;
        this.taskService = taskService;
    }

    /**
     * Accepts one price-refresh variant and returns without waiting for it.
     *
     * <p>The body must name the variant and nothing else: {@code {"variant": "PROFIT"}} or
     * {@code {"variant": "DISCOVERY"}}. An unexpected field is refused before the value is read, so
     * a caller who believes it is narrowing the item set finds out rather than being silently
     * ignored — item selection belongs to the collectors, not to the request.
     *
     * <p>Status contract, identical in shape to the two sync triggers': <b>202</b> with the task
     * identifier, the variant's operation key and its status path, plus a {@code Location} header
     * pointing at the same path — acceptance only, since the work has not run yet and this body
     * therefore carries no outcome; <b>400</b> {@code INVALID_REQUEST} (no variant, an unsupported
     * variant, or an unexpected field) / {@code MALFORMED_REQUEST} (unreadable body), raised before
     * a task exists, so a rejected request refreshes nothing and calls neither variant; <b>409</b>
     * {@code SYNC_ALREADY_RUNNING} while a previous refresh <em>of the same variant</em> is
     * unfinished, naming that task instead of starting a second one. Success and failure of the work
     * itself are reported by {@link SyncTaskApiController}, never here. See
     * {@link SyncApiExceptionHandler}.
     */
    @PostMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SyncTaskAcceptedResponse> refreshPrices(
            @RequestBody(required = false) Map<String, Object> request) {

        SyncRequestValidation.rejectUnknownRequestFields(
                PRICE_REFRESH_PATH, request, PriceRefreshVariant.VARIANT_FIELD);
        PriceRefreshVariant variant = PriceRefreshVariant.ofRequestValue(
                request == null ? null : request.get(PriceRefreshVariant.VARIANT_FIELD));

        String taskId = taskService.submit(variant.operation(), variant.bodyOf(priceRefreshService));
        String statusPath = SyncTaskApiController.statusPath(taskId);

        return ResponseEntity.accepted()
                .location(URI.create(statusPath))
                .body(new SyncTaskAcceptedResponse(taskId, variant.operation(), statusPath));
    }
}
