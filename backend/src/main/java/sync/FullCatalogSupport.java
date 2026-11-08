package sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Strict validation for the owner full-catalog operations. Failures name the endpoint kind and the
 * defect only, never the payload, and none of them may be treated as a successful run.
 */
final class FullCatalogSupport {
    private FullCatalogSupport() {}

    /** One finite upstream ID snapshot: a non-empty array of distinct positive integers. */
    static List<Integer> parseIdSnapshot(JsonNode root, String what) {
        if (root == null || !root.isArray()) throw new IllegalStateException(what + " id snapshot is not an array");
        Set<Integer> ids = new LinkedHashSet<>();
        for (JsonNode n : root) {
            if (n == null || !n.isInt() || n.asInt() <= 0) {
                throw new IllegalStateException(what + " id snapshot contains a malformed id");
            }
            if (!ids.add(n.asInt())) throw new IllegalStateException(what + " id snapshot contains a duplicate id");
        }
        if (ids.isEmpty()) throw new IllegalStateException(what + " id snapshot is empty");
        return new ArrayList<>(ids);
    }

    /**
     * Parses a detail batch that must contain exactly the requested IDs: an array, every record
     * well-formed, no duplicate, no unexpected ID and no missing requested ID.
     */
    static <T> List<T> parseExact(JsonNode root, List<Integer> requested, Function<JsonNode, T> parse,
                                  ToIntFunction<T> idOf, String what) {
        if (root == null || !root.isArray()) throw new IllegalStateException(what + " detail batch is not an array");
        Set<Integer> wanted = new HashSet<>(requested);
        Set<Integer> seen = new HashSet<>();
        List<T> out = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            T row = parse.apply(node);
            if (row == null) throw new IllegalStateException(what + " detail batch contains a malformed record");
            int id = idOf.applyAsInt(row);
            if (!wanted.contains(id)) throw new IllegalStateException(what + " detail batch contains an unexpected id");
            if (!seen.add(id)) throw new IllegalStateException(what + " detail batch contains a duplicate id");
            out.add(row);
        }
        if (seen.size() != wanted.size()) throw new IllegalStateException(what + " detail batch is missing requested ids");
        return out;
    }
}
