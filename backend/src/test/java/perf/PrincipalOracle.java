package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only per-principal correctness oracle for the capacity experiment.
 *
 * <p>Two different kinds of evidence, deliberately kept apart:
 * <ul>
 *   <li><b>Retained-source facts</b>: identity and counts read from the rows ACTUALLY LOADED for each principal
 *       (the {@link SnapshotImporter.Copies}), checked against that principal's own responses. A copy's roster is
 *       its own renamed characters; a shared hash of identical clone stock could not tell principals apart.</li>
 *   <li><b>Serial baselines</b>: the full no-load response per principal and journey, frozen before the principal
 *       contributes measured traffic and compared exactly under load. These prove CONSISTENCY, not economics: a
 *       baseline is whatever the application answered once, so it is not an independent oracle.</li>
 * </ul>
 * Any mismatch is recorded (first one wins), stops new scheduling and invalidates the evidence. Messages carry the
 * principal index and journey only, never account identifiers, names or keys.
 */
final class PrincipalOracle {
    /** Declared transient fields: per-request identifiers and publication timestamps. Everything else is compared. */
    static final Set<String> TRANSIENT_FIELDS = Set.of("calculationId", "basedOnCalculationId", "ageSeconds", "ageMillis",
            "elapsedMillis", "durationMillis");
    private static boolean transientField(String name) {
        return TRANSIENT_FIELDS.contains(name) || name.endsWith("At");
    }

    /** Facts retained from one principal's loaded source rows. */
    record Facts(Set<String> characters, int bankSlots, long bankCountSum, int materialRows, long materialTotal,
                 List<Integer> bankItemIds, List<Integer> materialItemIds) { }

    private final List<Facts> byUser;
    private final Map<String, String> frozen = new ConcurrentHashMap<>();
    private final AtomicReference<String> firstMismatch = new AtomicReference<>();

    private PrincipalOracle(List<Facts> byUser) { this.byUser = byUser; }

    static PrincipalOracle from(SnapshotImporter.Copies copies) {
        List<Facts> out = new ArrayList<>();
        for (String account : copies.accountIds()) {
            Set<String> names = new TreeSet<>();
            for (ObjectNode r : copies.snapshot().rows("characters")) {
                if (account.equals(r.path("account_id").asText())) names.add(r.path("name").asText());
            }
            int slots = 0;
            long sum = 0;
            List<Integer> bankIds = new ArrayList<>();
            for (ObjectNode r : copies.snapshot().rows("account_bank")) {
                if (account.equals(r.path("account_id").asText()) && !r.path("item_id").isNull()) {
                    slots++;
                    sum += r.path("count").asLong();
                    bankIds.add(r.path("item_id").asInt());
                }
            }
            int rows = 0;
            long total = 0;
            List<Integer> matIds = new ArrayList<>();
            for (ObjectNode r : copies.snapshot().rows("account_materials")) {
                if (account.equals(r.path("account_id").asText())) {
                    rows++;
                    total += r.path("count").asLong();
                    matIds.add(r.path("item_id").asInt());
                }
            }
            out.add(new Facts(Set.copyOf(names), slots, sum, rows, total,
                    bankIds.stream().distinct().sorted().toList(), matIds.stream().distinct().sorted().toList()));
        }
        return new PrincipalOracle(List.copyOf(out));
    }

    Facts facts(int user) { return byUser.get(user); }
    int principals() { return byUser.size(); }

    // ---- invalidation --------------------------------------------------------------------------------------

    /** Records a mismatch (first one wins) and returns false so callers can write {@code return oracle.fail(..)}. */
    boolean fail(int user, String journey, String what) {
        firstMismatch.compareAndSet(null, "principal " + user + " journey " + journey + ": " + what);
        return false;
    }
    String mismatch() { return firstMismatch.get(); }

    // ---- retained-source checks ----------------------------------------------------------------------------

    boolean verifyBank(int user, JsonNode bank) {
        Facts f = facts(user);
        long sum = 0;
        for (JsonNode s : bank.path("slots")) sum += s.path("count").asLong();
        if (bank.path("slotCount").asInt(-1) != f.bankSlots()) return fail(user, "bank", "slot count differs from the principal's loaded rows");
        if (sum != f.bankCountSum()) return fail(user, "bank", "item count sum differs from the principal's loaded rows");
        return true;
    }

    boolean verifyMaterials(int user, JsonNode materials) {
        Facts f = facts(user);
        long sum = 0;
        int rows = 0;
        for (JsonNode c : materials.path("categories")) for (JsonNode m : c.path("materials")) { sum += m.path("count").asLong(); rows++; }
        if (rows != f.materialRows()) return fail(user, "materials", "material row count differs from the principal's loaded rows");
        if (sum != f.materialTotal()) return fail(user, "materials", "material total differs from the principal's loaded rows");
        return true;
    }

    /** The roster must be exactly this principal's own character identities: no other principal's, none missing. */
    boolean verifyRoster(int user, List<String> names) {
        Set<String> got = new TreeSet<>(names);
        if (got.size() != names.size()) return fail(user, "characters", "duplicate roster entries");
        if (!got.equals(new TreeSet<>(facts(user).characters()))) return fail(user, "characters", "roster identities differ from the principal's own characters");
        return true;
    }

    boolean ownsCharacter(int user, String name) {
        return facts(user).characters().contains(name) || fail(user, "character", "selected character is not this principal's");
    }

    /** The detail payload for a selected character must name that character somewhere (never another's). */
    boolean verifyDetailNames(int user, String expected, JsonNode detail) {
        return mentions(detail, expected) || fail(user, "character-core", "detail does not carry the selected character identity");
    }
    private static boolean mentions(JsonNode n, String text) {
        if (n.isTextual()) return n.asText().equals(text);
        for (JsonNode c : n) if (mentions(c, text)) return true;
        return false;
    }

    // ---- serial baselines ----------------------------------------------------------------------------------

    /** Canonical text of a payload with only the declared transient fields removed; key order is made stable. */
    static String canonical(JsonNode n) {
        return normalize(n).toString();
    }

    private static JsonNode normalize(JsonNode n) {
        if (n.isObject()) {
            ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) names.add(it.next());
            Collections.sort(names);
            for (String name : names) if (!transientField(name)) out.set(name, normalize(n.get(name)));
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (JsonNode c : n) out.add(normalize(c));
            return out;
        }
        return n;
    }

    private static String key(int user, String journey) { return user + "|" + journey; }

    boolean frozen(int user, String journey) { return frozen.containsKey(key(user, journey)); }

    /** Freezes the serial no-load fact; a principal's baseline is never overwritten by a loaded observation. */
    void freeze(int user, String journey, String print) {
        if (frozen.putIfAbsent(key(user, journey), CapacityReport.sha256(print)) != null) {
            throw new IllegalStateException("baseline already frozen for principal " + user + " journey " + journey);
        }
    }

    /** Exact comparison with the principal's OWN frozen baseline (another principal's baseline never counts). */
    boolean matchesFrozen(int user, String journey, String print) {
        String base = frozen.get(key(user, journey));
        if (base == null) return fail(user, journey, "no serial baseline was frozen before measured traffic");
        return base.equals(CapacityReport.sha256(print)) || fail(user, journey, "response differs from the principal's serial baseline");
    }

    // ---- protection regression ---------------------------------------------------------------------------

    /** Sum of the per-recipe craftable counts of a profit payload (rows/results/opportunities). */
    static long craftableTotal(JsonNode profit) {
        long sum = 0;
        for (String f : List.of("rows", "results", "opportunities")) for (JsonNode r : profit.path(f)) sum += r.path("craftableCount").asLong();
        return sum;
    }

    /**
     * Monotonic protection invariant for one principal over the normal crafting payload: protecting stock can only
     * reduce what own materials can craft, and protecting EVERY bank and material id (no buying) leaves nothing.
     */
    boolean verifyProtection(int user, long none, long bankOnly, long materialsOnly, long combined) {
        if (bankOnly > none || materialsOnly > none) return fail(user, "protection", "single-source protection increased craftable counts");
        if (combined > bankOnly || combined > materialsOnly) return fail(user, "protection", "combined protection is weaker than a single source");
        if (combined != 0) return fail(user, "protection", "protecting every bank and material id still leaves craftable recipes");
        return true;
    }
}
