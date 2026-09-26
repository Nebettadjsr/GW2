package web;

import application.TradingPostPriceRefreshService;
import web.task.BackgroundTaskService;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The two Trading Post price-refresh variants a caller can request, and the mapping from the
 * transport value to the existing use case and to its admission key (STORY-API-005).
 *
 * <p>Two variants and no third option: {@code application.TradingPostPriceRefreshService} has
 * exposed exactly these two, distinctly scoped workflows since STORY-APP-006 — the Crafting Profit
 * item set and the Crafting Discovery item set — and this route translates them rather than
 * widening them. There is deliberately no combined or "all prices" value: the two workflows select
 * different items for different pages, so a request that did not name one would have to invent a
 * third workflow at the HTTP boundary.
 *
 * <p>The variant is therefore required. {@link #ofRequestValue(Object)} refuses anything else
 * <em>before</em> the trigger admits a task, so an unrecognised value refreshes nothing.
 */
enum PriceRefreshVariant {

    /**
     * {@code CraftingProfitView}'s item set, refreshed by
     * {@link TradingPostPriceRefreshService#refreshForProfit()}.
     */
    PROFIT("PRICE_REFRESH_PROFIT"),

    /**
     * {@code CraftingDiscoveryView}'s item set, refreshed by
     * {@link TradingPostPriceRefreshService#refreshForDiscovery()}.
     */
    DISCOVERY("PRICE_REFRESH_DISCOVERY");

    /** The one request field this route accepts, named in its validation messages. */
    static final String VARIANT_FIELD = "variant";

    private final String operation;

    PriceRefreshVariant(String operation) {
        this.operation = operation;
    }

    /**
     * This variant's admission key, also the {@code operation} reported by the acceptance and the
     * status body.
     *
     * <p><b>One key per variant</b>, which is the facility's existing per-operation rule applied as
     * it already is to {@code ACCOUNT_SYNC}/{@code GLOBAL_SYNC}: a second submission of the
     * <em>same</em> variant while one is unfinished is refused, and the two variants are independent
     * of each other and of the two sync operations. The shared-write consequences of that
     * independence are recorded in {@code CURRENT_ARCHITECTURE.md} §5.9; in short, both variants
     * upsert {@code tp_prices} keyed on {@code item_id} and neither deletes rows, so overlapping runs
     * cannot lose or mix a row, and the overlap they do have is already reachable from the two
     * JavaFX buttons today.
     */
    String operation() {
        return operation;
    }

    /**
     * The task body for this variant: exactly one call of the corresponding existing use-case
     * method, and never the other one.
     */
    BackgroundTaskService.TaskBody bodyOf(TradingPostPriceRefreshService service) {
        return switch (this) {
            case PROFIT -> service::refreshForProfit;
            case DISCOVERY -> service::refreshForDiscovery;
        };
    }

    /**
     * Resolves the {@code variant} field's raw value from the request body.
     *
     * @param value the value as deserialized, {@code null} when the field (or the whole body) was
     *              absent
     * @throws ApiValidationException if no variant was named, or if the value is not one of the two
     *                                supported ones — including a differently-cased or non-string
     *                                value, since the accepted values are an exact contract (the
     *                                same rule the calculation routes' {@code scope.kind} follows)
     */
    static PriceRefreshVariant ofRequestValue(Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new ApiValidationException(VARIANT_FIELD
                    + " is required: the Profit and Discovery price refreshes cover different item sets, "
                    + "so one of " + acceptedValues() + " must be named");
        }

        return Arrays.stream(values())
                .filter(variant -> variant.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new ApiValidationException(
                        VARIANT_FIELD + " must be one of " + acceptedValues()));
    }

    private static String acceptedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
