package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.BankSlot;

public final class BankParser {
    private BankParser() {}

    /** Parses one bank slot (array element) into a BankSlot DTO. */
    public static BankSlot parseSlot(int slot, JsonNode entry) {
        // Empty slot => all nulls except slot index
        if (entry == null || entry.isNull()) {
            return new BankSlot(slot, null, null, null, null, null, null, null);
        }

        Integer itemId = entry.hasNonNull("id") ? entry.get("id").asInt() : null;
        Integer count  = entry.hasNonNull("count") ? entry.get("count").asInt() : null;

        String binding  = entry.hasNonNull("binding") ? entry.get("binding").asText() : null;
        String boundTo  = entry.hasNonNull("bound_to") ? entry.get("bound_to").asText() : null;
        Integer charges = entry.hasNonNull("charges") ? entry.get("charges").asInt() : null;

        Integer statsId = null;
        String statsAttrsJson = null;

        JsonNode stats = entry.get("stats");
        if (stats != null && !stats.isNull()) {
            if (stats.hasNonNull("id")) statsId = stats.get("id").asInt();

            JsonNode attrs = stats.get("attributes");
            if (attrs != null && !attrs.isNull()) {
                // store JSON string; SQL will cast it to jsonb
                statsAttrsJson = attrs.toString();
            }
        }

        return new BankSlot(slot, itemId, count, binding, boundTo, charges, statsId, statsAttrsJson);
    }
}