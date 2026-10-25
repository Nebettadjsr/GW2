package web.dto;

/**
 * Transport response body for {@code POST /api/crafting/profit/resolution} (STORY-API-008,
 * TARGET_ARCHITECTURE.md §10.2 and STORY-API-008's response envelope).
 *
 * <p>{@code recipeId} and {@code calculation} echo the <em>requested</em> identity and the effective
 * inputs, which is what a caller associates the response with. They say nothing about how the root
 * requirement was actually sourced - that belongs to {@link #tree()}, whose root carries its own
 * actually selected recipe (§13.3, AR-003).
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param status       {@code CALCULATED} | {@code PENDING}. {@code PENDING} (HTTP 202) means no
 *                     usable synchronized inputs existed, so {@code row} and {@code tree} are
 *                     absent rather than fabricated
 * @param consistency  {@code FRESH_CALCULATION} when this response explains the same input
 *                     generation the caller's row came from, or when the caller named none;
 *                     {@code RECALCULATED_FOR_CHANGED_INPUTS} when the inputs changed since, in
 *                     which case {@link #row()} is the matching updated row and the caller's older
 *                     row is not what {@link #tree()} explains. Either way this is a new
 *                     calculation, not the stored result of the earlier table request
 * @param freshness    the inputs this response was calculated from, in the same shape the table
 *                     route reports them; {@code calculationId} is what identifies the generation
 * @param calculatedAt UTC ISO-8601 instant this calculation completed; informational only, never a
 *                     data version or snapshot identifier
 * @param row          the recipe's normal row, recalculated in this operation - the same shared
 *                     {@link CraftingRowDto} the table route reports, with the same per-craft and
 *                     total meanings
 * @param treeStatus   {@code AVAILABLE} or {@code RESULT_UNAVAILABLE}
 * @param treeBasis    {@code SELECTED_RESULT_OUTPUT_QUANTITY} when the row counted output: the root
 *                     and its costs represent all output units from {@code row.craftableCount}
 *                     completed executions of the requested recipe, including its output batch
 *                     size. {@code FIRST_BLOCKED_ATTEMPT} when the row counted no craft: the tree
 *                     preserves one rejected attempt and its concrete blockers. When no result
 *                     tree exists, the selected-result basis remains the contract value.
 * @param tree         the root requirement, or null only when {@code treeStatus} is
 *                     {@code RESULT_UNAVAILABLE}; an unavailable result is never a fabricated empty
 *                     tree
 */
public record CraftingProfitResolutionResponse(int recipeId,
                                               String status,
                                               CalculationDto calculation,
                                               String consistency,
                                               CalculationFreshnessDto freshness,
                                               String calculatedAt,
                                               CraftingRowDto row,
                                               String treeStatus,
                                               String treeBasis,
                                               ResolutionNodeDto tree) {

    /** A completed detail calculation over usable cached inputs. */
    public static final String STATUS_CALCULATED = "CALCULATED";

    /** No usable synchronized inputs existed; refresh was admitted and nothing was explained. */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * The effective inputs this calculation ran with, in the shape the Profit table response echoes
     * them ({@link CraftingProfitResponse}); every effective setting is explicit.
     */
    public record CalculationDto(CraftingProfitResponse.EffectiveScopeDto scope,
                                 CraftingProfitResponse.EffectiveSettingsDto settings) {
    }
}
