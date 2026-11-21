package craft;

import java.math.BigDecimal;

/**
 * Verified variable output of one craft. {@code mean} is the exact expected number of output units per craft
 * (a decimal, never a float); {@code min} and {@code max} are the guaranteed bounds. A min/max range is not a
 * mean: callers must supply a verified mean, so an unknown outcome simply has no {@code ExpectedYield}.
 * Only {@code min} is ever treated as guaranteed stock; the fractional expectation is for estimated economics.
 */
public record ExpectedYield(BigDecimal mean, int min, int max) {

    public ExpectedYield {
        if (mean == null || min <= 0 || max < min
                || mean.compareTo(BigDecimal.valueOf(min)) < 0 || mean.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException("expected yield needs 0 < min <= mean <= max");
        }
    }

    public static ExpectedYield of(String mean, int min, int max) {
        return new ExpectedYield(new BigDecimal(mean), min, max);
    }

    /** Whole batches for a desired expected net output; seeds reused by later batches are not new output. */
    public long estimatedBatchesForOutput(long outputUnits, int selfSeedUnits) {
        if (outputUnits <= 0 || selfSeedUnits < 0) throw new IllegalArgumentException("positive output and nonnegative seed required");
        long reusedPerBatch = Math.min(selfSeedUnits, min);
        BigDecimal required = BigDecimal.valueOf(outputUnits).subtract(BigDecimal.valueOf(reusedPerBatch));
        if (required.signum() <= 0) return 1;
        BigDecimal netPerBatch = mean.subtract(BigDecimal.valueOf(reusedPerBatch));
        if (netPerBatch.signum() <= 0) throw new IllegalArgumentException("recipe has no expected net output gain");
        return required.divide(netPerBatch, 0, java.math.RoundingMode.CEILING).longValueExact();
    }

    /**
     * Gross copper for {@code units} expected units (may be fractional) at {@code unitCopper}: the single documented
     * copper boundary, rounded down so an estimate never claims a fraction of a copper it cannot sell.
     */
    public static long floorCopper(BigDecimal units, int unitCopper) {
        if (units.signum() <= 0 || unitCopper <= 0) return 0;
        return units.multiply(BigDecimal.valueOf(unitCopper)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
    }
}
