package craft;

public enum BlockedReason {
    NONE,
    NO_RECIPE,
    PRICE_UNAVAILABLE,
    BUYING_DISABLED,
    DAILY_LIMIT,
    CYCLE_DETECTED,
    RECIPE_NOT_ALLOWED,
    INSUFFICIENT_BUDGET,
    /** A single material item ID is required in more than {@link PlanState#MAX_MATERIAL_UNITS} units. */
    MATERIAL_LIMIT,
    /** A copper quantity or total falls outside the supported range of the calculation. */
    VALUE_RANGE
}
