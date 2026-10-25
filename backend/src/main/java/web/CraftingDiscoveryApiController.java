package web;

import application.CraftingDiscoveryService;
import application.TradingPostRefreshCoordinator;
import application.CraftingResolutionDetail;
import org.springframework.beans.factory.annotation.Autowired;
import craft.CraftingSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import web.dto.DiscoveryFreshnessDto;
import web.access.RequestAccountContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.DiscChoice;
import web.dto.CraftingDiscoveryRequest;
import web.dto.CraftingDiscoveryResolutionRequest;
import web.dto.CraftingDiscoveryResolutionResponse;
import web.dto.CraftingDiscoveryResponse;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * HTTP boundary for the Crafting Discovery calculation (STORY-API-002, TARGET_ARCHITECTURE.md §9) and
 * for one selected recipe's resolution detail (STORY-API-008, §13).
 *
 * <p>Thin by construction, exactly like {@link CraftingProfitApiController}: it validates and
 * defaults the request, hands the resulting {@link DiscChoice}/{@link CraftingSettings} and the
 * selected inventory character to the existing {@link CraftingDiscoveryService#reload} or
 * {@link CraftingDiscoveryService#resolveDetail} use case, and maps what comes back. Recipe
 * eligibility, the missing-discoverable lookup, the rating filter, inventory/price loading, the
 * crafting calculation and every resolution choice behind the detail tree all stay inside that
 * service and {@code craft.*}; none of them is implemented or re-derived here.
 *
 * <p><b>Request isolation.</b> {@link CraftingDiscoveryService} keeps the most recent reload's data
 * in instance state (to back its lazy per-row tree lookup) and deliberately leaves that state
 * untouched when a reload finds nothing to discover. This controller therefore takes a
 * <em>factory</em> and builds one service per request, then reads only the {@code DiscoveryData}
 * that call returned. Nothing request-specific is held in a field, so successive and concurrent
 * requests cannot observe each other's calculation - and an empty result can never surface an
 * earlier request's rows.
 */
@RestController
@RequestMapping("/api/crafting")
public class CraftingDiscoveryApiController {

    private final Supplier<CraftingDiscoveryService> discoveryServiceFactory;
    private final TradingPostRefreshCoordinator priceRefresh;
    private final application.ScopedAccountRefreshCoordinator accountRefresh;
    private final web.access.AccountAccessHttp accessHttp;

    public CraftingDiscoveryApiController(Supplier<CraftingDiscoveryService> discoveryServiceFactory) {
        this(discoveryServiceFactory, null);
    }

    public CraftingDiscoveryApiController(Supplier<CraftingDiscoveryService> discoveryServiceFactory,
                                          TradingPostRefreshCoordinator priceRefresh) {
        this(discoveryServiceFactory, priceRefresh, null, null);
    }

    @Autowired
    public CraftingDiscoveryApiController(Supplier<CraftingDiscoveryService> discoveryServiceFactory,
                                          TradingPostRefreshCoordinator priceRefresh,
                                          application.ScopedAccountRefreshCoordinator accountRefresh,
                                          web.access.AccountAccessHttp accessHttp) {
        this.discoveryServiceFactory = discoveryServiceFactory;
        this.priceRefresh = priceRefresh;
        this.accountRefresh = accountRefresh;
        this.accessHttp = accessHttp;
    }

    /** What the account-input check decided: the envelope to report and whether a result is possible. */
    private record InputGate(DiscoveryFreshnessDto freshness, boolean pending,
                             java.util.Set<String> needing,
                             application.access.AccountAccessService.KeyedProof proof) {
    }

    /**
     * Validates the optional key proof and the selected character, then classifies the account's
     * Discovery inputs: missing = pending/no result, expired = usable and reported STALE. A request
     * with a key prepares the scoped refresh the inputs need; admission follows calculation (or
     * occurs immediately for pending/no-result). Without a key, refresh is reported
     * {@code CREDENTIAL_REQUIRED}; no configured credential is ever used.
     */
    private InputGate gate(HttpServletRequest http, CraftingDiscoveryService service, DiscChoice choice)
            throws Exception {
        String key = web.access.AccountAccessHttp.presentedApiKey(http);
        boolean keyed = key != null && accountRefresh != null;
        application.access.AccountAccessService.KeyedProof proof = null;
        if (keyed) {
            if (accessHttp != null && !accessHttp.isSameOrigin(http)) throw new ForeignOriginException();
            proof = accountRefresh.verify(RequestAccountContext.required(http),
                    web.access.AccountAccessHttp.presentedSessionId(http).orElseThrow(), key,
                    CraftingDiscoveryService.SOURCES);
        }
        service.verifySelectedCharacter(choice);

        CraftingDiscoveryService.InputState state = service.inputState();
        var needing = state.needingRefresh();
        if (needing.isEmpty()) return new InputGate(null, false, needing, proof);

        boolean pending = !state.missing().isEmpty();
        return new InputGate(new DiscoveryFreshnessDto(
                pending ? DiscoveryFreshnessDto.PENDING : DiscoveryFreshnessDto.STALE,
                (pending ? state.missing() : state.stale()).stream().sorted().toList(),
                application.ScopedSourceGate.CREDENTIAL_REQUIRED, null), pending, needing, proof);
    }

    /** Admit only after usable inputs have been calculated; pending requests have no inputs to race. */
    private DiscoveryFreshnessDto admitRefresh(HttpServletRequest http, InputGate inputs) {
        if (inputs.freshness() == null || inputs.proof() == null) return inputs.freshness();
        var admission = accountRefresh.require(RequestAccountContext.required(http),
                web.access.AccountAccessHttp.presentedSessionId(http).orElseThrow(),
                web.access.AccountAccessHttp.presentedApiKey(http), inputs.needing(), inputs.proof());
        return new DiscoveryFreshnessDto(inputs.freshness().status(), inputs.freshness().sources(),
                admission.reuse(), admission.taskId());
    }

    /** Shared Discovery demand coordination; still completes before the calculation reads quotes. */
    private void refreshQuotes(model.AccountScope scope) throws Exception {
        if (priceRefresh != null) priceRefresh.refreshAndAwait(TradingPostRefreshCoordinator.Variant.DISCOVERY, scope);
    }

    /**
     * Lists the still-missing DISCOVERABLE recipes for one discipline+character combination, with
     * the crafting calculation for each.
     *
     * <p>The body is required and must carry a complete scope; omitted settings fall back to the
     * defaults documented on {@link CraftingDiscoveryApiMapper}.
     *
     * <p>Status contract: 200 with the complete result set (an empty list when nothing is left to
     * discover); 400 for a malformed or invalid request (no calculation is started); 503 when the
     * database is unavailable; 500 when the calculation itself fails. See
     * {@link ApiExceptionHandler}.
     */
    @PostMapping(path = "/discovery", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CraftingDiscoveryResponse> discovery(
            HttpServletRequest http,
            @RequestBody(required = false) CraftingDiscoveryRequest request) throws Exception {

        CraftingDiscoveryApiMapper.Effective effective = CraftingDiscoveryApiMapper.toEffective(request);
        var service = discoveryServiceFactory.get().forScope(RequestAccountContext.scopeOf(http));
        InputGate inputs = gate(http, service, effective.toDiscChoice());
        if (inputs.pending()) return pendingTable(effective, service, admitRefresh(http, inputs));
        refreshQuotes(RequestAccountContext.scopeOf(http));

        CraftingDiscoveryService.DiscoveryData data;
        try {
            data = service.reload(effective.toDiscChoice(), effective.toCraftingSettings());
        } catch (CraftingDiscoveryService.AccountInputsUnavailableException unpublishedMeanwhile) {
            return pendingTable(effective, service, new DiscoveryFreshnessDto(DiscoveryFreshnessDto.PENDING,
                    java.util.List.of(), application.ScopedSourceGate.CREDENTIAL_REQUIRED, null));
        }

        CraftingDiscoveryResponse body = CraftingDiscoveryApiMapper.toResponse(effective, data);
        DiscoveryFreshnessDto freshness = admitRefresh(http, inputs);
        return ResponseEntity.ok(freshness == null ? body : new CraftingDiscoveryResponse(
                body.scope(), body.settings(), body.rowCount(), body.rows(), freshness));
    }

    private ResponseEntity<CraftingDiscoveryResponse> pendingTable(
            CraftingDiscoveryApiMapper.Effective effective, CraftingDiscoveryService service,
            DiscoveryFreshnessDto freshness) throws SQLException {
        service.recordAuthorizedUse();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CraftingDiscoveryResponse(
                effective.scope(), effective.settings(), 0, java.util.List.of(), freshness));
    }

    /**
     * Explains how one selected recipe's output requirement is resolved in a Discovery calculation
     * (STORY-API-008, TARGET_ARCHITECTURE.md §13.1).
     *
     * <p>The body is required and carries both members: {@code recipeId} and the {@code calculation}
     * it is resolved in, the latter being this route's own table request contract - so the required
     * individual character/discipline scope (also the inventory scope), Discovery's own settings
     * defaults and its fixed {@code allowDailyCrafts=true} all apply
     * unchanged. Both members are validated before a service exists.
     *
     * <p>The service call is one <em>fresh</em> calculation with request-local state - never a lookup
     * of the earlier table request's result, and an operation with nothing discoverable cannot
     * surface an earlier one's tree - and the response's row and tree are mapped from the inputs that
     * single operation captured.
     *
     * <p>Status contract: 200 with the completed response, including a blocked tree and an
     * unavailable result; 404 {@code RECIPE_NOT_IN_CALCULATION} when this calculation's visible
     * recipes do not contain the requested one; otherwise the route's existing 400/503/500 mapping.
     * See {@link ApiExceptionHandler}.
     */
    @PostMapping(path = "/discovery/resolution", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CraftingDiscoveryResolutionResponse> discoveryResolution(
            HttpServletRequest http,
            @RequestBody(required = false) CraftingDiscoveryResolutionRequest request) throws Exception {

        CraftingDiscoveryResolutionRequest body = CraftingResolutionMapper.requireBody(request);
        int recipeId = CraftingResolutionMapper.requireRecipeId(body.recipeId());
        CraftingDiscoveryApiMapper.Effective effective = CraftingDiscoveryApiMapper.toEffective(
                CraftingResolutionMapper.requireCalculation(body.calculation()));
        var service = discoveryServiceFactory.get().forScope(RequestAccountContext.scopeOf(http));
        InputGate inputs = gate(http, service, effective.toDiscChoice());
        var calculation = new CraftingDiscoveryResolutionResponse.CalculationDto(
                effective.scope(), effective.settings());
        if (inputs.pending()) {
            service.recordAuthorizedUse();
            return pendingResolution(recipeId, calculation, admitRefresh(http, inputs));
        }
        refreshQuotes(RequestAccountContext.scopeOf(http));

        CraftingResolutionDetail detail;
        try {
            detail = CraftingResolutionMapper.requireInCalculation(service.resolveDetail(
                    recipeId, effective.toDiscChoice(), effective.toCraftingSettings()));
        } catch (CraftingDiscoveryService.AccountInputsUnavailableException unpublishedMeanwhile) {
            return pendingResolution(recipeId, calculation, new DiscoveryFreshnessDto(
                    DiscoveryFreshnessDto.PENDING, java.util.List.of(),
                    application.ScopedSourceGate.CREDENTIAL_REQUIRED, null));
        }

        return ResponseEntity.ok(new CraftingDiscoveryResolutionResponse(
                detail.recipeId(),
                calculation,
                CraftingResolutionMapper.CONSISTENCY_FRESH_CALCULATION,
                CraftingResolutionMapper.calculatedAt(),
                CraftingResolutionMapper.toRow(detail),
                CraftingResolutionMapper.treeStatus(detail),
                CraftingResolutionMapper.TREE_BASIS_SINGLE_OUTPUT_REQUIREMENT,
                CraftingResolutionMapper.toDiscoveryTree(detail),
                admitRefresh(http, inputs)));
    }

    private static ResponseEntity<CraftingDiscoveryResolutionResponse> pendingResolution(
            int recipeId, CraftingDiscoveryResolutionResponse.CalculationDto calculation,
            DiscoveryFreshnessDto freshness) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CraftingDiscoveryResolutionResponse(
                recipeId, calculation, CraftingResolutionMapper.CONSISTENCY_FRESH_CALCULATION,
                CraftingResolutionMapper.calculatedAt(), null, null, null, null, freshness));
    }
}
