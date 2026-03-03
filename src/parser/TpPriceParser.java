package parser;

import com.fasterxml.jackson.databind.JsonNode;
import util.TpPrice;

public final class TpPriceParser {
    private TpPriceParser() {}

    /**
     * Parses a TP price node from either:
     * - batch endpoint: array elements contain { id, buys, sells }
     * - single endpoint: object contains { id, buys, sells }
     *
     * Rules:
     * - If buys/sells are null => return TpPriceQuote.noData(id)
     * - Otherwise read quantity/unit_price normally
     */
    public static TpPrice parse(JsonNode node) {
        if (node == null || node.isNull()) return null;

        JsonNode idNode = node.get("id");
        if (idNode == null || idNode.isNull()) return null;

        int itemId = idNode.asInt();

        JsonNode buys = node.get("buys");
        JsonNode sells = node.get("sells");

        // GW2: buys/sells can be JSON null => means “no TP data”
        if (buys == null || sells == null || buys.isNull() || sells.isNull()) {
            return TpPrice.noData(itemId);
        }

        long buyQty  = buys.path("quantity").asLong();
        int  buyUnit = buys.path("unit_price").asInt();

        long sellQty  = sells.path("quantity").asLong();
        int  sellUnit = sells.path("unit_price").asInt();

        return new TpPrice(itemId, buyQty, buyUnit, sellQty, sellUnit);
    }
}