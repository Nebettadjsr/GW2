package web.dto;

/**
 * Backend-computed hint for a Mystic Forge recipe with a variable output, so the frontend never recalculates.
 * {@code expectedOutputPerCraft} is the exact decimal mean (string); min/max are the guaranteed bounds, not a
 * mean. {@code estimated} is also set for parents whose route merely uses such a step (no yield of their own).
 * The profit figures of an estimated row are expected economics, never guaranteed yield or feasibility.
 */
public record ExpectedYieldDto(String source,
                               boolean estimated,
                               String expectedOutputPerCraft,
                               Integer minOutput,
                               Integer maxOutput,
                               String expectedSellableUnitsTotal,
                               Integer guaranteedSellableUnitsTotal,
                               int reusedSeedUnits) {
}
