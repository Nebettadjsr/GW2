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
 * HTTP trigger for account synchronization (STORY-API-003).
 *
 * <p>Asynchronous by decision rather than by measurement: UD-007 requires operations that call the
 * GW2 API for synchronization to run as backend tasks with status reporting, because ArenaNet's rate
 * limits force batching and holding one HTTP request open for the whole sequence is needlessly
 * fragile. No duration was measured or assumed to reach that choice.
 *
 * <p>Each route validates the request and supplies its source set to
 * {@link ScopedAccountRefreshCoordinator}, which admits or coalesces work for the verified account.
 * Application services own fetching, publication and each source's independent success or failure;
 * this boundary reports admission and the task identifier without claiming refresh completion.
 */
@RestController
@RequestMapping("/api/sync")
public class AccountSyncApiController {

    /**
     * Reported operation name. The scoped coordinator excludes concurrent writers per verified
     * account, so different accounts remain independent.
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

    /** Luck's single source: the narrow recovery for a never-published Luck, needing no Bank/characters/recipes grants. */
    private static final Set<String> LUCK_SOURCES = new LinkedHashSet<>(List.of(AccountSyncStateRepository.LUCK));

    /** Optional tab sources; neither belongs to the full or Profit source sets. */
    private static final Set<String> BUILD_TABS_SOURCES =
            new LinkedHashSet<>(List.of(AccountSyncStateRepository.CHARACTER_BUILD_TABS));
    private static final Set<String> EQUIPMENT_TABS_SOURCES =
            new LinkedHashSet<>(List.of(AccountSyncStateRepository.CHARACTER_EQUIPMENT_TABS));

    /** Whether this account has a published CHARACTERS roster for optional tab reads. */
    @FunctionalInterface
    interface ParentPublished {
        boolean test(model.AccountScope scope) throws Exception;
    }

    /** How long a demand behind a narrower active run waits for it before it is reported as not covered. */
    static final java.time.Duration RECHECK_BOUND = java.time.Duration.ofSeconds(5);
    private static final int WAITING_RETRY_SECONDS = 10;

    private final ScopedAccountRefreshCoordinator coordinator;
    private final AccountAccessHttp http;

    private final ParentPublished parentPublished;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountSyncApiController(ScopedAccountRefreshCoordinator coordinator, AccountAccessHttp http) {
        this(coordinator, http, scope -> {
            try (var con = repo.Db.open()) {
                return new repo.AccountSourceStateRepository()
                        .read(con, scope, AccountSyncStateRepository.CHARACTERS).isPresent();
            }
        });
    }

    AccountSyncApiController(ScopedAccountRefreshCoordinator coordinator, AccountAccessHttp http,
                             ParentPublished parentPublished) {
        this.coordinator = coordinator;
        this.http = http;
        this.parentPublished = parentPublished;
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
        return admit(request, withWalletWhenGranted(request, FULL_SOURCES));
    }

    @PostMapping(path = "/account/crafting-profit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncCraftingProfitData(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/crafting-profit", body);
        return admit(request, withWalletWhenGranted(request, PROFIT_SOURCES));
    }

    @PostMapping(path = "/account/wallet", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncWallet(HttpServletRequest request,
                                        @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/wallet", body);
        return admit(request, Set.of(AccountSyncStateRepository.WALLET));
    }

    /**
     * The wallet is optional: only a key holding the {@code wallet} grant adds it, so an item-only key still
     * refreshes every other source and never needs (or fails for lack of) that permission.
     */
    private static Set<String> withWalletWhenGranted(HttpServletRequest request, Set<String> sources) {
        var context = RequestAccountContext.required(request);
        if (!context.permits(application.access.AccountOperation.WALLET_READ)) return sources;
        Set<String> withWallet = new LinkedHashSet<>(sources);
        withWallet.add(AccountSyncStateRepository.WALLET);
        return withWallet;
    }

    @PostMapping(path = "/account/bank", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncBank(HttpServletRequest request,
                                      @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/bank", body);
        return admit(request, Set.of(AccountSyncStateRepository.BANK));
    }

    @PostMapping(path = "/account/materials", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncMaterials(HttpServletRequest request,
                                           @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/materials", body);
        return admit(request, Set.of(AccountSyncStateRepository.MATERIALS));
    }

    @PostMapping(path = "/account/characters", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncCharacters(HttpServletRequest request,
                                            @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/characters", body);
        return admit(request, Set.of(AccountSyncStateRepository.CHARACTERS));
    }

    @PostMapping(path = "/account/luck", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncAccountLuck(HttpServletRequest request,
                                             @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/luck", body);
        return admit(request, LUCK_SOURCES);
    }

    /**
     * Demands only the optional equipment-tab coverage. The key is proved first; then a published
     * same-account CHARACTERS roster is required, otherwise nothing is admitted and no tab is fetched.
     */
    @PostMapping(path = "/account/character-equipment-tabs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncCharacterEquipmentTabs(HttpServletRequest request,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/character-equipment-tabs", body);
        return admit(request, EQUIPMENT_TABS_SOURCES, true);
    }

    /** Demands only the optional build-tab coverage; same proof and published-parent rules as equipment tabs. */
    @PostMapping(path = "/account/character-build-tabs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncCharacterBuildTabs(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        SyncRequestValidation.rejectAnyRequestField("/api/sync/account/character-build-tabs", body);
        return admit(request, BUILD_TABS_SOURCES, true);
    }

    private ResponseEntity<?> admit(HttpServletRequest request, Set<String> sources) {
        return admit(request, sources, false);
    }

    private ResponseEntity<?> admit(HttpServletRequest request, Set<String> sources, boolean needsParent) {
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
            if (needsParent) {
                var proof = coordinator.verify(context, sessionId, apiKey, sources);
                boolean published;
                try {
                    published = parentPublished.test(RequestAccountContext.scopeOf(request));
                } catch (Exception e) {
                    return error(HttpStatus.SERVICE_UNAVAILABLE, "DATA_STORE_UNAVAILABLE",
                            "The character roster could not be checked right now");
                }
                if (!published) {
                    return error(HttpStatus.CONFLICT, "PREREQUISITE_UNAVAILABLE",
                            "This source needs a published character roster; refresh characters first");
                }
                admission = coordinator.requireAfterBoundedWait(context, sessionId, apiKey, sources, RECHECK_BOUND,
                        proof);
            } else {
                admission = coordinator.requireAfterBoundedWait(context, sessionId, apiKey, sources, RECHECK_BOUND);
            }
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
