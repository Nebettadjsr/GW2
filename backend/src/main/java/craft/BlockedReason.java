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
    VALUE_RANGE,
    /** The recipe consumes a currency whose account balance is not known (no permission/refresh); never zero or unlimited. */
    WALLET_UNAVAILABLE,
    /** The known wallet balance cannot pay the next craft's currency requirement. */
    INSUFFICIENT_CURRENCY
}
