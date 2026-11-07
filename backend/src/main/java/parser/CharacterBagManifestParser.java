package parser;

import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterBagRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates the bag manifest of a character-detail "bags" array (STORY-CHAR-006): one row
 * per position, installed or explicitly null. Capacity is the upstream {@code size}; it is never
 * inferred from occupied slots. Any contradiction aborts instead of being truncated or guessed.
 */
public final class CharacterBagManifestParser {
    private CharacterBagManifestParser() {}

    public static List<CharacterBagRow> parse(JsonNode bagsArr) {
        if (bagsArr == null || !bagsArr.isArray()) throw new IllegalStateException("bags is not an array");
        List<CharacterBagRow> out = new ArrayList<>(bagsArr.size());
        for (int i = 0; i < bagsArr.size(); i++) {
            JsonNode bag = bagsArr.get(i);
            if (bag == null || bag.isNull()) {
                out.add(new CharacterBagRow(i, false, null, null));
                continue;
            }
            if (!bag.isObject()) throw new IllegalStateException("bag " + i + " is not an object");
            int id = positive(bag.get("id"), "bag " + i + " id");
            int size = positive(bag.get("size"), "bag " + i + " size");
            JsonNode inv = bag.get("inventory");
            if (inv == null || !inv.isArray()) throw new IllegalStateException("bag " + i + " lacks inventory");
            // Upstream inventories are slot-aligned: one entry (item or null) per slot. A shorter or
            // longer array is incomplete or contradictory evidence, so empties are never invented.
            if (inv.size() != size) {
                throw new IllegalStateException("bag " + i + " inventory has " + inv.size()
                        + " entries but capacity " + size);
            }
            out.add(new CharacterBagRow(i, true, id, size));
        }
        return out;
    }

    private static int positive(JsonNode n, String what) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() <= 0) {
            throw new IllegalStateException(what + " is missing or not a positive integer");
        }
        return n.intValue();
    }
}
