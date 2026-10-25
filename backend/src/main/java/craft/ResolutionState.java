package craft;

/**
 * A domain state worth highlighting on an explained requirement (DOMAIN_SPEC.md sections 11.2, 21,
 * 31 and 42). States describe the requirement, not its presentation, and may coexist with the
 * {@link AcquisitionMethod}s that did succeed for it.
 */
public enum ResolutionState {

    /** Some or all of the requested quantity could not be obtained. */
    BLOCKED,

    /** A required purchase has no usable Trading Post price; it is not a free item. */
    PRICE_UNAVAILABLE,

    /** A daily-limited craft could not be repeated within the day's allowance. */
    DAILY_LIMIT,

    /**
     * Consumed owned quantity was valued at zero because no value could be established for it
     * (DOMAIN_SPEC.md section 11.2); such a quantity must not be read as genuinely free.
     */
    UNVALUED_NONTRADEABLE
}
