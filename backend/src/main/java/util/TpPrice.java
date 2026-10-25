package util;

public record TpPrice(
        int itemId,
        Long buyQty,
        Integer buyUnit,
        Long sellQty,
        Integer sellUnit
) {
    public boolean hasMarketData() {
        return buyQty != null && buyUnit != null && sellQty != null && sellUnit != null;
    }

    /** “Real no TP data” (GW2 returns buys/sells as null). */
    public static TpPrice noData(int itemId) {
        return new TpPrice(itemId, null, null, null, null);
    }
}