package craft;

/** Independent crafting-domain Trading Post price quote (STORY-DOM-017): no persistence/JDBC dependency. */
public class PriceQuote {
    public final Integer buyUnit;   // buy_unit_price (can be null)
    public final Integer sellUnit;  // sell_unit_price (can be null)

    public PriceQuote(Integer buyUnit, Integer sellUnit) {
        this.buyUnit = buyUnit;
        this.sellUnit = sellUnit;
    }
}
