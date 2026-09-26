package craft;

/**
 * The domain explanation of <em>one</em> execution of a selected recipe, resolved from the
 * calculation inputs it was given (DOMAIN_SPEC.md section 44, TARGET_ARCHITECTURE.md section 13.3).
 *
 * <p>The root requirement asks for {@code outputQuantity} units of {@code outputItemId} - the
 * recipe's own output count. It is not a trace of every craft a simulation could perform, not the
 * craft that follows an exhausted simulation, and not a recipe skeleton multiplied out; its costs
 * are therefore never the multi-craft totals of a table row.
 *
 * <p>{@link #available()} distinguishes an explanation that exists - including one whose first
 * craft is blocked, which still carries its reasons - from the absence of a calculation result,
 * for which {@link #unavailable(int, int, int)} returns an explanation with no tree rather than a
 * fabricated empty one.
 *
 * @param recipeId       the recipe that was asked about.
 * @param outputItemId   that recipe's output item.
 * @param outputQuantity that recipe's output count, which the root requirement requests.
 * @param available      whether a resolution result exists to explain.
 * @param root           the explained root requirement, or null when {@code available} is false.
 */
public record SingleCraftExplanation(
        int recipeId,
        int outputItemId,
        int outputQuantity,
        boolean available,
        CraftTraceNode root
) {

    /** No calculation result exists for this recipe; see {@link #available()}. */
    public static SingleCraftExplanation unavailable(int recipeId, int outputItemId, int outputQuantity) {
        return new SingleCraftExplanation(recipeId, outputItemId, outputQuantity, false, null);
    }
}
