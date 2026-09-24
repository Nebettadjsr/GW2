package web;

import application.CraftingDiscoveryService;
import craft.CraftingSettings;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.DiscChoice;
import web.dto.CraftingDiscoveryRequest;
import web.dto.CraftingDiscoveryResponse;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * HTTP boundary for the Crafting Discovery calculation (STORY-API-002, TARGET_ARCHITECTURE.md §9).
 *
 * <p>Thin by construction, exactly like {@link CraftingProfitApiController}: it validates and
 * defaults the request, hands the resulting {@link DiscChoice}/{@link CraftingSettings} and the
 * selected inventory character to the existing {@link CraftingDiscoveryService#reload} use case,
 * and maps what comes back. Recipe eligibility, the missing-discoverable lookup, the rating filter,
 * inventory/price loading and the crafting calculation all stay inside that service and
 * {@code craft.*}; none of them is implemented or re-derived here.
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

    public CraftingDiscoveryApiController(Supplier<CraftingDiscoveryService> discoveryServiceFactory) {
        this.discoveryServiceFactory = discoveryServiceFactory;
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
    public CraftingDiscoveryResponse discovery(
            @RequestBody(required = false) CraftingDiscoveryRequest request) throws SQLException {

        CraftingDiscoveryApiMapper.Effective effective = CraftingDiscoveryApiMapper.toEffective(request);

        CraftingDiscoveryService.DiscoveryData data = discoveryServiceFactory.get()
                .reload(effective.toDiscChoice(), effective.toCraftingSettings(),
                        effective.inventoryCharacterName());

        return CraftingDiscoveryApiMapper.toResponse(effective, data);
    }
}
