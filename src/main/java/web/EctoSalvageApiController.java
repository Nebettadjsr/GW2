package web;

import application.EctoSalvageService;
import ecto.EctoSalvageCalculator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.EctoSalvageResponse;

/**
 * HTTP boundary for the Ectoplasm Salvage calculation (STORY-WEB-013, TARGET_ARCHITECTURE.md §9):
 * the four Ecto-buy/Dust-sell scenarios the JavaFX Ecto Salvage Analyzer already shows
 * (CURRENT_ARCHITECTURE.md §5.3).
 *
 * <p>Thin by construction: one call to the existing {@link EctoSalvageService} per accepted request,
 * then a field-for-field copy into transport records. It fetches no price itself, holds no quote
 * acquisition sequence, applies no fee, scales no yield and computes no profit, net cost or Luck
 * cost - every number in the response came out of the domain through that one call. It reads no
 * database and starts no synchronization either: unlike the crafting routes, this use case sources
 * its quotes live through the application's own gateway.
 *
 * <p>One shared service instance, for the reason {@link BankContentsApiController} records: the
 * service keeps no per-call state - it holds only its price gateway and returns a fresh result
 * object per call - so two callers have no result to observe from each other.
 */
@RestController
@RequestMapping("/api/ecto")
public class EctoSalvageApiController {

    private final EctoSalvageService ectoSalvageService;

    public EctoSalvageApiController(EctoSalvageService ectoSalvageService) {
        this.ectoSalvageService = ectoSalvageService;
    }

    /**
     * Runs the existing parameterless calculation and returns its four scenarios.
     *
     * <p>The use case takes no input, so there is nothing to configure on it: no yield, no fee, no
     * item and no acquisition mode is adjustable here, and a request that tries to supply one is
     * rejected before the service is called rather than silently ignored.
     *
     * <p>Status contract: 200 with {@code resultAvailable: true} and the four scenarios; 200 with
     * {@code resultAvailable: false} and four null scenarios when the Trading Post returned no
     * usable quotes for both items (a completed calculation with no result, not a failure); 400
     * {@code UNSUPPORTED_REQUEST} when the request carries any query parameter, with no calculation
     * performed; 502 {@code PRICE_SOURCE_UNAVAILABLE} when acquiring the live quotes or running the
     * calculation failed. See {@link EctoSalvageApiExceptionHandler}.
     */
    @GetMapping(path = "/salvage", produces = MediaType.APPLICATION_JSON_VALUE)
    public EctoSalvageResponse salvage(HttpServletRequest request) throws Exception {
        rejectUnsupportedInput(request);

        EctoSalvageService.EctoScenarios scenarios = ectoSalvageService.calculate();

        return new EctoSalvageResponse(
                scenarios.available(),
                EctoSalvageService.ECTO_ID,
                EctoSalvageService.DUST_ID,
                assumptions(),
                toScenario(scenarios.instantBuyInstantSell()),
                toScenario(scenarios.instantBuyListingSell()),
                toScenario(scenarios.listingBuyInstantSell()),
                toScenario(scenarios.listingBuyListingSell()));
    }

    /**
     * The route accepts no input at all, so any query parameter describes a calculation this use
     * case cannot perform. Answering it with the unparameterised result would tell a caller its
     * parameter was honoured.
     */
    private static void rejectUnsupportedInput(HttpServletRequest request) {
        // The parameter map, not the raw query string: that is what a request's parameters actually
        // are, and a check on the raw string would pass a test while missing a real one (or the
        // reverse). A GET carries no other input, and the request body is not read at all.
        if (!request.getParameterMap().isEmpty()) {
            throw new ApiValidationException(
                    "The Ectoplasm salvage calculation takes no parameters; remove them from the request");
        }
    }

    /** Domain constants, read from their owner; no value here is chosen or converted at this boundary. */
    private static EctoSalvageResponse.EctoSalvageAssumptionsDto assumptions() {
        return new EctoSalvageResponse.EctoSalvageAssumptionsDto(
                EctoSalvageCalculator.LUCK_PER_ECTO,
                EctoSalvageCalculator.DUST_PER_ECTO,
                EctoSalvageCalculator.ECTOS_PER_1000_LUCK,
                EctoSalvageCalculator.SELL_FEE_PERCENT);
    }

    /**
     * Field-for-field copy of one scenario, preserving every value and its sign. A scenario the
     * service reported as absent stays absent: it becomes null, never a zero-valued scenario.
     */
    private static EctoSalvageResponse.EctoScenarioDto toScenario(
            EctoSalvageCalculator.ScenarioResult result) {

        if (result == null) return null;

        return new EctoSalvageResponse.EctoScenarioDto(
                result.ectoAcquisitionCost(),
                result.dustGrossUnitPrice(),
                result.dustNetUnitPrice(),
                result.netValueOfRecoveredDust(),
                result.netCostPerEcto(),
                result.profitPerEcto(),
                result.costPer1000Luck());
    }
}
