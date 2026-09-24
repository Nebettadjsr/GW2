package web;

import application.CraftingProfitService;
import craft.CraftingSettings;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.DiscChoice;
import web.dto.CraftingProfitRequest;
import web.dto.CraftingProfitResponse;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * HTTP boundary for the Crafting Profit calculation (STORY-API-001, TARGET_ARCHITECTURE.md §9).
 *
 * <p>Thin by construction: it validates and defaults the request, hands the resulting
 * {@link DiscChoice}/{@link CraftingSettings} to the existing
 * {@link CraftingProfitService#reload} use case, and maps what comes back. No crafting rule,
 * orchestration step or total is implemented or re-derived here.
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

    public CraftingProfitApiController(Supplier<CraftingProfitService> profitServiceFactory) {
        this.profitServiceFactory = profitServiceFactory;
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
            throws SQLException {

        CraftingProfitApiMapper.Effective effective = CraftingProfitApiMapper.toEffective(request);

        CraftingProfitService.ProfitData data = profitServiceFactory.get()
                .reload(effective.toDiscChoice(), effective.toCraftingSettings());

        return CraftingProfitApiMapper.toResponse(effective, data);
    }
}
