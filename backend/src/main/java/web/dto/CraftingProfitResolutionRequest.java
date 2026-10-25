package web.dto;

/**
 * Transport request body for {@code POST /api/crafting/profit/resolution} (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.1).
 *
 * <p>Two members, both required: the recipe whose resolution is asked about, and the calculation it
 * is resolved in. {@code calculation} is deliberately the <em>existing</em> table request contract
 * ({@link CraftingProfitRequest}), so the browser can copy the effective scope and settings the
 * table response echoed straight back rather than relying on defaults a second time, and so this
 * route adds no calculation control of its own.
 *
 * <p>Nothing else is an input: row numbers, prices and material maps are not part of this contract,
 * because the detail operation is a fresh calculation and not retrieval of an earlier one (§13.2).
 * The one optional member below is not an exception to that — it is never used to retrieve or
 * reconstruct an earlier calculation, only to tell the caller whether this fresh one explains the
 * same inputs.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param recipeId the recipe's own identity, never merely its output item ID; required and positive
 * @param calculation the scope and settings to resolve it in; required, though its individual
 *                    members keep {@link CraftingProfitRequest}'s optional-with-defaults semantics
 * @param basedOnCalculationId optional: the {@code freshness.calculationId} of the table response
 *                    whose row the caller is asking about (TARGET_ARCHITECTURE.md §9.1/§10.2). When
 *                    given and the inputs have changed since, the response says
 *                    {@code RECALCULATED_FOR_CHANGED_INPUTS} and carries the matching updated row,
 *                    so a detail computed from newer inputs is never presented as the explanation of
 *                    an older provisional row. When absent, no comparison is made and nothing about
 *                    the calculation changes
 */
public record CraftingProfitResolutionRequest(Integer recipeId,
                                              CraftingProfitRequest calculation,
                                              String basedOnCalculationId) {
}
