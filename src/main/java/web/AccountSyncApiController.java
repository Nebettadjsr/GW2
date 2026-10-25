package web;

import application.ScopedAccountRefreshCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import repo.AccountSyncStateRepository;
import web.access.AccountAccessHttp;
import web.access.RequestAccountContext;
import web.dto.ApiErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.SyncTaskAcceptedResponse;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * order (account bank, account materials, account recipes, account Luck, then every character's crafting and
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
    static final String PROFIT_ACCOUNT_SYNC_OPERATION = ACCOUNT_SYNC_OPERATION;

    /** This route's path, also used in its validation message. */
    static final String ACCOUNT_SYNC_PATH = "/api/sync/account";

    private static final Set<String> FULL_SOURCES = new LinkedHashSet<>(List.of(
            AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
            AccountSyncStateRepository.RECIPES, AccountSyncStateRepository.LUCK,
            AccountSyncStateRepository.CHARACTERS));
    /** Profit's sources: everything but Luck, which Profit never consumes. */
    private static final Set<String> PROFIT_SOURCES = new LinkedHashSet<>(List.of(
            AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
            AccountSyncStateRepository.RECIPES, AccountSyncStateRepository.CHARACTERS));

    /** How long a demand behind a narrower active run waits for it before it is reported as not covered. */
    static final java.time.Duration RECHECK_BOUND = java.time.Duration.ofSeconds(5);
    private static final int WAITING_RETRY_SECONDS = 10;

    private final ScopedAccountRefreshCoordinator coordinator;
    private final AccountAccessHttp http;

    public AccountSyncApiController(ScopedAccountRefreshCoordinator coordinator, AccountAccessHttp http) {
        this.coordinator = coordinator;
        this.http = http;
    }

    /**
     * Admits a keyed refresh of the authorized account (STORY-API-011, SYNC-006). The account comes
     * only from the session the guard resolved; the transient key is read from
     * {@code X-GW2-API-Key} and handed to the coordinator, which revalidates it against that account
     * before admitting anything. No key means nothing is admitted ({@code 400 CREDENTIAL_REQUIRED});
     * no configured credential is ever used. 202 carries the task id, 403 an origin, account or grant
     * mismatch, 401 an invalid key, 503 (with Retry-After) unavailable proof.
     */
    @PostMapping(path = "/account", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncAccount(HttpServletRequest request,
                                         @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField(ACCOUNT_SYNC_PATH, body);
        return admit(request, FULL_SOURCES);
    }

    @PostMapping(path = "/account/crafting-profit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncCraftingProfitData(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/crafting-profit", body);
        return admit(request, PROFIT_SOURCES);
    }

    private ResponseEntity<?> admit(HttpServletRequest request, Set<String> sources) {
        if (!http.isSameOrigin(request)) {
            return error(HttpStatus.FORBIDDEN, "ORIGIN_REJECTED",
                    "This request did not come from this application's own origin");
        }
        var context = RequestAccountContext.required(request);
        String apiKey = AccountAccessHttp.presentedApiKey(request);
        if (apiKey == null) {
            return error(HttpStatus.BAD_REQUEST, "CREDENTIAL_REQUIRED",
                    "Supply a GW2 API key in the " + AccountAccessHttp.API_KEY_HEADER + " header");
        }
        String sessionId = AccountAccessHttp.presentedSessionId(request).orElseThrow();
        ScopedAccountRefreshCoordinator.Admission admission;
        try {
            admission = coordinator.requireAfterBoundedWait(context, sessionId, apiKey, sources, RECHECK_BOUND);
        } catch (ScopedAccountRefreshCoordinator.Rejected rejected) {
            return switch (rejected.reason()) {
                case SESSION -> error(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "Prove a GW2 API key to continue");
                case INVALID_CREDENTIAL, MALFORMED_PROOF -> error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIAL",
                        "The GW2 API key could not be validated");
                case ACCOUNT_MISMATCH, INSUFFICIENT_GRANTS -> error(HttpStatus.FORBIDDEN, "ACCOUNT_ACCESS_DENIED",
                        "This session is not authorized for this account operation");
                case UPSTREAM_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.RETRY_AFTER, "30")
                        .body(new ApiErrorResponse("PROOF_UNAVAILABLE",
                                "GW2 could not validate the key right now; retry shortly"));
            };
        }
        String statusPath = SyncTaskApiController.statusPath(admission.taskId());
        boolean covers = !ScopedAccountRefreshCoordinator.REUSE_WAITING_FOR_ACTIVE_TASK.equals(admission.reuse());
        if (!covers) {
            // The only task to point at belongs to a narrower run (or another entry point); its success
            // is not this request's sync. Nothing was admitted for the uncovered sources: retry later.
            return ResponseEntity.accepted().location(URI.create(statusPath))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(WAITING_RETRY_SECONDS))
                    .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, context.accessContext())
                    .body(new SyncTaskAcceptedResponse(admission.taskId(), ACCOUNT_SYNC_OPERATION, statusPath,
                            admission.reuse(), false, WAITING_RETRY_SECONDS));
        }
        return ResponseEntity.accepted().location(URI.create(statusPath))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, context.accessContext())
                .body(new SyncTaskAcceptedResponse(admission.taskId(), ACCOUNT_SYNC_OPERATION, statusPath,
                        admission.reuse(), true, null));
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(code, message));
    }
}
