package craft;

/**
 * Values owned stock consumed by a plan through the ordinary DOMAIN_SPEC section 11/11.2 rules: sellable copies at the
 * direct Trading Post sell value, bound copies (and sellable copies without a quote) at recursive crafting cost, then
 * vendor sell value. Valuation only: nothing is bought or consumed.
 */
@FunctionalInterface
public interface OwnedValuer {

    /** {@code copper} is the exact aggregate (rounded up once); {@code unvalued} means no value could be established. */
    record Value(long copper, boolean unvalued) { }

    /** @throws ArithmeticException when the aggregate leaves the exact long range */
    Value value(int itemId, long sellable, long bound);
}
