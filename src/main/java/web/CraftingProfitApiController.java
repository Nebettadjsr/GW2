package web;

import application.CachedCraftingProfitService;
import application.CraftingResolutionDetail;
import craft.CraftingSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.DiscChoice;
import web.access.RequestAccountContext;
import web.dto.CraftingProfitRequest;
import web.dto.CraftingProfitResolutionRequest;
import web.dto.CraftingProfitResolutionResponse;
import web.dto.CraftingProfitResponse;

/**
 * HTTP boundary for the Crafting Profit calculation (STORY-API-001, TARGET_ARCHITECTURE.md §9/§9.1)
 * and for one selected recipe's resolution detail (STORY-API-008, §10.2).
 *
 * <p>Thin by construction: it validates and defaults the request, hands the resulting
 * {@link DiscChoice}/{@link CraftingSettings} to the existing
 * {@link CachedCraftingProfitService} use case, and maps what comes back. No crafting rule,
 * orchestration step, total, resolution choice, cost or freshness decision is implemented or
 * re-derived here - in particular the detail route builds no second resolver, and nothing on this
 * boundary decides whether a result is provisional.
 *
 * <p><b>Cached-result contract (STORY-APP-014).</b> Neither route waits for account synchronization
 * or a Trading Post fetch any more. The use case calculates from the last successfully published
 * inputs and admits the refresh work itself, so:
 *
 * <ul>
 *   <li><b>200</b> with the ordinary authoritative result whenever usable cached inputs existed -
 *       including when they had expired, which the response reports as
 *       {@code freshness.provisional} rather than refusing. Expiry alone is never a rejection.</li>
 *   <li><b>202</b> with the freshness envelope and task references and <em>no</em> rows when no
 *       source has ever synchronized, so a client is never handed an empty result that reads as
 *       "nothing is craftable".</li>
 *   <li><b>400</b> for a malformed or invalid request (no calculation is started), <b>404</b>
 *       {@code RECIPE_NOT_IN_CALCULATION} on the detail route, <b>503</b> when the database is
 *       unavailable, <b>500</b> when the calculation itself fails. See
 *       {@link ApiExceptionHandler}.</li>
 * </ul>
 *
 * <p><b>Request isolation.</b> The application service builds one calculation service per call and
 * reads only what that call returned, so nothing request-specific is held in a field here and
 * successive or concurrent requests cannot observe each other's calculation or each other's
 * freshness envelope.
 */
@RestController
@RequestMapping("/api/crafting")
public class CraftingProfitApiController {

    private final CachedCraftingProfitService profitService;

    public CraftingProfitApiController(CachedCraftingProfitService profitService) {
        this.profitService = profitService;
    }

    /**
     * Runs the Crafting Profit calculation for the requested scope and settings over the inputs
     * currently available, and reports what those inputs were.
     *
     * <p>The body is optional; an absent body, absent members and absent fields all fall back to
     * the defaults documented on {@link CraftingProfitApiMapper}, so a bare POST performs the
     * default All-scope calculation.
     */
    @PostMapping(path = "/profit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CraftingProfitResponse> profit(
            @RequestBody(required = false) CraftingProfitRequest request,
            HttpServletRequest http) throws Exception {

        CraftingProfitApiMapper.Effective effective = CraftingProfitApiMapper.toEffective(request);

        CachedCraftingProfitService.ProfitOutcome outcome = profitService.profit(
                RequestAccountContext.scopeOf(http), effective.toDiscChoice(), effective.toCraftingSettings());

        if (!outcome.hasResult()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(CraftingProfitApiMapper.toPendingResponse(effective, outcome.freshness()));
        }

        return ResponseEntity.ok(
                CraftingProfitApiMapper.toResponse(effective, outcome.data(), outcome.freshness()));
    }

    /**
     * Explains how one selected recipe's output requirement is resolved in a Profit calculation
     * (STORY-API-008, TARGET_ARCHITECTURE.md §10.2).
     *
     * <p>The body is required and carries {@code recipeId} and the {@code calculation} it is
     * resolved in, the latter being this route's own table request contract, so scope kinds,
     * settings, defaults and validation are the established ones. Both are validated before a
     * calculation starts. The optional {@code basedOnCalculationId} is the table response's input
     * generation, which lets this response say whether it still explains that generation.
     *
     * <p>The service call is one <em>fresh</em> calculation with request-local state - never a
     * lookup of the earlier table request's result - and the response's row and tree are mapped
     * from the inputs that single operation captured, so they are compatible with each other by
     * construction.
     */
    @PostMapping(path = "/profit/resolution", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CraftingProfitResolutionResponse> profitResolution(
            @RequestBody(required = false) CraftingProfitResolutionRequest request,
            HttpServletRequest http) throws Exception {

        CraftingProfitResolutionRequest body = CraftingResolutionMapper.requireBody(request);
        int recipeId = CraftingResolutionMapper.requireRecipeId(body.recipeId());
        CraftingProfitApiMapper.Effective effective = CraftingProfitApiMapper.toEffective(
                CraftingResolutionMapper.requireCalculation(body.calculation()));

        CachedCraftingProfitService.ResolutionOutcome outcome = profitService.resolution(
                RequestAccountContext.scopeOf(http), recipeId, effective.toDiscChoice(), effective.toCraftingSettings(),
                body.basedOnCalculationId());

        CraftingProfitResolutionResponse.CalculationDto calculation =
                new CraftingProfitResolutionResponse.CalculationDto(effective.scope(), effective.settings());

        if (!outcome.hasResult()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CraftingProfitResolutionResponse(
                    recipeId,
                    CraftingProfitResolutionResponse.STATUS_PENDING,
                    calculation,
                    outcome.consistency(),
                    CalculationFreshnessMapper.toDto(outcome.freshness()),
                    CraftingResolutionMapper.calculatedAt(),
                    null, null, null, null));
        }

        CraftingResolutionDetail detail = CraftingResolutionMapper.requireInCalculation(outcome.detail());

        return ResponseEntity.ok(new CraftingProfitResolutionResponse(
                detail.recipeId(),
                CraftingProfitResolutionResponse.STATUS_CALCULATED,
                calculation,
                outcome.consistency(),
                CalculationFreshnessMapper.toDto(outcome.freshness()),
                CraftingResolutionMapper.calculatedAt(),
                CraftingResolutionMapper.toRow(detail),
                CraftingResolutionMapper.treeStatus(detail),
                CraftingResolutionMapper.profitTreeBasis(detail),
                CraftingResolutionMapper.toTree(detail)));
    }
}
