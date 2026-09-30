package web;

import application.CraftingProfitService;
import application.TradingPostPriceRefreshService;
import application.CraftingResolutionDetail;
import craft.CraftingSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.DiscChoice;
import web.dto.CraftingProfitRequest;
import web.dto.CraftingProfitResolutionRequest;
import web.dto.CraftingProfitResolutionResponse;
import web.dto.CraftingProfitResponse;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * HTTP boundary for the Crafting Profit calculation (STORY-API-001, TARGET_ARCHITECTURE.md §9) and
 * for one selected recipe's resolution detail (STORY-API-008, §13).
 *
 * <p>Thin by construction: it validates and defaults the request, hands the resulting
 * {@link DiscChoice}/{@link CraftingSettings} to the existing
 * {@link CraftingProfitService#reload} or {@link CraftingProfitService#resolveDetail} use case, and
 * maps what comes back. No crafting rule, orchestration step, total, resolution choice or cost is
 * implemented or re-derived here - in particular the detail route builds no second resolver.
 *
 * <p><b>Request isolation.</b> {@link CraftingProfitService} keeps the most recent reload's data
 * in instance state (to back its lazy per-row tree lookup). This controller therefore takes a
 * <em>factory</em> and builds one service per request, then reads only the {@code ProfitData} that
 * call returned. Nothing request-specific is held in a field, so successive and concurrent
 * requests cannot observe each other's calculation.
 */
@RestController
@RequestMapping("/api/crafting")
public class CraftingProfitApiController {

    private final Supplier<CraftingProfitService> profitServiceFactory;
    private final TradingPostPriceRefreshService priceRefreshService;

    public CraftingProfitApiController(Supplier<CraftingProfitService> profitServiceFactory) {
        this(profitServiceFactory, null);
    }

    @Autowired
    public CraftingProfitApiController(Supplier<CraftingProfitService> profitServiceFactory,
                                       TradingPostPriceRefreshService priceRefreshService) {
        this.profitServiceFactory = profitServiceFactory;
        this.priceRefreshService = priceRefreshService;
    }

    /**
     * Runs the Crafting Profit calculation for the requested scope and settings.
     *
     * <p>The body is optional; an absent body, absent members and absent fields all fall back to
     * the defaults documented on {@link CraftingProfitApiMapper}, so a bare POST performs the
     * default All-scope calculation.
     *
     * <p>Status contract: 200 with the complete result set; 400 for a malformed or invalid
     * request (no calculation is started); 503 when the database is unavailable; 500 when the
     * calculation itself fails. See {@link ApiExceptionHandler}.
     */
    @PostMapping(path = "/profit", produces = MediaType.APPLICATION_JSON_VALUE)
    public CraftingProfitResponse profit(@RequestBody(required = false) CraftingProfitRequest request)
            throws Exception {

        CraftingProfitApiMapper.Effective effective = CraftingProfitApiMapper.toEffective(request);
        if (priceRefreshService != null) priceRefreshService.refreshForProfit();

        CraftingProfitService.ProfitData data = profitServiceFactory.get()
                .reload(effective.toDiscChoice(), effective.toCraftingSettings());

        return CraftingProfitApiMapper.toResponse(effective, data);
    }

    /**
     * Explains how one selected recipe's output requirement is resolved in a Profit calculation
     * (STORY-API-008, TARGET_ARCHITECTURE.md §13.1).
     *
     * <p>The body is required and carries both members: {@code recipeId} and the {@code calculation}
     * it is resolved in, the latter being this route's own table request contract, so scope kinds,
     * settings, defaults and validation are the established ones. Both are validated before a
     * service exists.
     *
     * <p>The service call is one <em>fresh</em> calculation with request-local state - never a lookup
     * of the earlier table request's result - and the response's row and tree are mapped from the
     * inputs that single operation captured.
     *
     * <p>Status contract: 200 with the completed response, including a blocked tree and an
     * unavailable result; 404 {@code RECIPE_NOT_IN_CALCULATION} when this calculation's visible
     * recipes do not contain the requested one; otherwise the route's existing 400/503/500 mapping.
     * See {@link ApiExceptionHandler}.
     */
    @PostMapping(path = "/profit/resolution", produces = MediaType.APPLICATION_JSON_VALUE)
    public CraftingProfitResolutionResponse profitResolution(
            @RequestBody(required = false) CraftingProfitResolutionRequest request) throws Exception {

        CraftingProfitResolutionRequest body = CraftingResolutionMapper.requireBody(request);
        int recipeId = CraftingResolutionMapper.requireRecipeId(body.recipeId());
        CraftingProfitApiMapper.Effective effective = CraftingProfitApiMapper.toEffective(
                CraftingResolutionMapper.requireCalculation(body.calculation()));
        if (priceRefreshService != null) priceRefreshService.refreshForProfit();

        CraftingResolutionDetail detail = CraftingResolutionMapper.requireInCalculation(
                profitServiceFactory.get().resolveDetail(
                        recipeId, effective.toDiscChoice(), effective.toCraftingSettings()));

        return new CraftingProfitResolutionResponse(
                detail.recipeId(),
                new CraftingProfitResolutionResponse.CalculationDto(effective.scope(), effective.settings()),
                CraftingResolutionMapper.CONSISTENCY_FRESH_CALCULATION,
                CraftingResolutionMapper.calculatedAt(),
                CraftingResolutionMapper.toRow(detail),
                CraftingResolutionMapper.treeStatus(detail),
                CraftingResolutionMapper.profitTreeBasis(detail),
                CraftingResolutionMapper.toTree(detail));
    }
}
