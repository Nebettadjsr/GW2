package perf;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Test-only, explicit warm-publication preparation of a captured single-account fixture (STORY-KPI-002).
 *
 * <p>A captured cache is stale and its sources are published at widely different times (the real private
 * input has source-marker lags of ~325000 s, price lags of ~300000 s and older optional equipment/Builds
 * coverage whose parent reference no longer equals the current CHARACTERS generation). A single uniform
 * clock shift cannot make that state pass the <em>normal</em> production freshness and coverage
 * compatibility checks, and there is deliberately no product bypass. This class rewrites only publication
 * metadata, outside every measured window, and discloses every change:
 *
 * <ul>
 *   <li>Every source marker is re-dated to {@code now - (BASE + SPREAD * lag / maxLag)} (lag = behind the
 *       newest marker): relative order is kept, the spread is linearly compressed so every source, including
 *       the older optional equipment/Builds ones, is fresh for the bounded warm window. BANK, MATERIALS,
 *       ACCOUNT_RECIPES and CHARACTERS are required; a missing one is refused, never fabricated.</li>
 *   <li>Their coverage {@code parent_generation} is set to the current CHARACTERS generation, the only
 *       relationship the production compatibility check consumes. Source generations are untouched.</li>
 *   <li>Trading-post price {@code fetched_at} values are compressed into the price TTL the same way.</li>
 *   <li>Row {@code fetched_at} follow their source marker; other time columns move by the single global
 *       delta, never into the future.</li>
 * </ul>
 * Facts (stock, items, recipes, equipment, builds, inventory, prices, generations) are never altered. The
 * raw capture is not modified and the result is never evidence that the capture was fresh.
 */
public final class WarmPreparation {
    /** Normal production limits this preparation is sized against. */
    public static final Duration ACCOUNT_MAX_AGE = application.CachedCraftingProfitService.DEFAULT_ACCOUNT_MAX_AGE;
    public static final Duration TP_TTL = sync.tp.TpPriceFreshness.TTL;
    static final long BASE_SECONDS = 5, SPREAD_SECONDS = 60, TP_BASE = 5, TP_SPREAD = 30;

    static final Set<String> REQUIRED = Set.of("BANK", "MATERIALS", "ACCOUNT_RECIPES", "CHARACTERS");
    private static final Map<String, String> TABLE_SOURCE = Map.ofEntries(
            Map.entry("account_bank", "BANK"), Map.entry("account_materials", "MATERIALS"),
            Map.entry("account_recipes", "ACCOUNT_RECIPES"), Map.entry("account_luck", "LUCK"),
            Map.entry("characters", "CHARACTERS"), Map.entry("character_crafting", "CHARACTERS"),
            Map.entry("character_recipes", "CHARACTERS"), Map.entry("character_items", "CHARACTERS"),
            Map.entry("character_bags", "CHARACTERS"), Map.entry("character_bag_layout_publication", "CHARACTERS"),
            Map.entry("character_equipment_tabs", "CHARACTER_EQUIPMENT_TABS"),
            Map.entry("character_equipment_tab_entries", "CHARACTER_EQUIPMENT_TABS"),
            Map.entry("character_build_tabs", "CHARACTER_BUILD_TABS"),
            Map.entry("character_build_tab_specializations", "CHARACTER_BUILD_TABS"));
    private static final Map<String, String> COVERAGE_TABLES = Map.of(
            "character_equipment_coverage", "CHARACTER_EQUIPMENT_TABS", "character_build_coverage", "CHARACTER_BUILD_TABS");

    /** The prepared snapshot plus a disclosure map for the report (never contains row data). */
    public record Result(FixtureSnapshot snapshot, Map<String, String> disclosure, Duration warmWindow) {}

    private WarmPreparation() {}

    public static Result prepare(FixtureSnapshot raw, OffsetDateTime now) throws SnapshotImporter.ImportException {
        now = now.withOffsetSameInstant(java.time.ZoneOffset.UTC);
        List<ObjectNode> states = raw.rows("account_source_state");
        Map<String, OffsetDateTime> oldAt = new LinkedHashMap<>();
        Map<String, Long> generation = new HashMap<>();
        for (ObjectNode s : states) {
            oldAt.put(s.get("source").asText(), SnapshotImporter.parse(s.get("completed_at").asText()));
            generation.put(s.get("source").asText(), s.get("generation").asLong());
        }
        for (String r : REQUIRED) {
            if (!oldAt.containsKey(r)) throw new SnapshotImporter.ImportException("required source " + r + " has no marker; a missing source is never fabricated");
        }
        OffsetDateTime newest = Collections.max(oldAt.values());
        Duration global = Duration.between(newest, now);

        Map<String, OffsetDateTime> newAt = new LinkedHashMap<>();
        Map<String, Long> newAgeSeconds = new LinkedHashMap<>();
        long maxLag = oldAt.values().stream().mapToLong(v -> Duration.between(v, newest).toSeconds()).max().orElse(0);
        StringBuilder markers = new StringBuilder();
        for (var e : oldAt.entrySet()) {
            boolean optional = COVERAGE_TABLES.containsValue(e.getKey());
            long lag = Math.max(0, Duration.between(e.getValue(), newest).toSeconds());
            long ageMillis = BASE_SECONDS * 1000 + (maxLag == 0 ? 0 : SPREAD_SECONDS * 1000 * lag / maxLag);
            long age = (ageMillis + 999) / 1000;
            newAt.put(e.getKey(), now.minus(Duration.ofMillis(ageMillis)));
            newAgeSeconds.put(e.getKey(), age);
            markers.append(e.getKey()).append(optional ? "(optional)" : "").append(" lag ").append(lag).append("s->").append(age).append("s; ");
        }

        // Trading-post prices: compressed into the TTL, preserving order.
        OffsetDateTime tpNewest = null;
        for (ObjectNode r : raw.rows("tp_prices")) {
            if (r.hasNonNull("fetched_at")) {
                OffsetDateTime v = SnapshotImporter.parse(r.get("fetched_at").asText());
                if (tpNewest == null || v.isAfter(tpNewest)) tpNewest = v;
            }
        }
        long tpMaxLagAll = 0;
        for (ObjectNode r : raw.rows("tp_prices")) {
            if (r.hasNonNull("fetched_at")) tpMaxLagAll = Math.max(tpMaxLagAll, Duration.between(SnapshotImporter.parse(r.get("fetched_at").asText()), tpNewest).toSeconds());
        }
        long tpOriginalMaxLag = 0, tpMaxAge = TP_BASE;
        Map<String, List<ObjectNode>> out = new LinkedHashMap<>();
        for (var t : raw.tables().entrySet()) {
            String table = t.getKey();
            String source = TABLE_SOURCE.get(table);
            List<ObjectNode> rows = new ArrayList<>();
            for (ObjectNode r : t.getValue()) {
                ObjectNode c = r.deepCopy();
                if (table.equals("account_source_state")) {
                    String s = c.get("source").asText();
                    c.put("completed_at", newAt.get(s).toString());
                } else if (table.equals("tp_prices")) {
                    if (c.hasNonNull("fetched_at")) {
                        long lag = Math.max(0, Duration.between(SnapshotImporter.parse(c.get("fetched_at").asText()), tpNewest).toSeconds());
                        tpOriginalMaxLag = Math.max(tpOriginalMaxLag, lag);
                        long ageMillis = TP_BASE * 1000 + (tpMaxLagAll == 0 ? 0 : TP_SPREAD * 1000 * lag / tpMaxLagAll);
                        tpMaxAge = Math.max(tpMaxAge, (ageMillis + 999) / 1000);
                        c.put("fetched_at", now.minus(Duration.ofMillis(ageMillis)).toString());
                    }
                } else if (COVERAGE_TABLES.containsKey(table)) {
                    c.put("parent_generation", generation.get("CHARACTERS"));
                } else {
                    Duration delta = source != null && newAt.containsKey(source) ? Duration.between(oldAt.get(source), newAt.get(source)) : global;
                    OffsetDateTime cap = source != null && newAt.containsKey(source) ? newAt.get(source) : now;
                    for (String f : List.of("fetched_at", "last_seen_at", "last_successful_sync_at", "created_at", "updated_at", "created_at_gw")) {
                        if (!c.hasNonNull(f)) continue;
                        OffsetDateTime v = SnapshotImporter.parse(c.get(f).asText()).plus(f.equals("fetched_at") ? delta : global);
                        OffsetDateTime limit = f.equals("fetched_at") ? cap : now;
                        c.put(f, (v.isAfter(limit) ? limit : v).toString());
                    }
                }
                rows.add(c);
            }
            out.put(table, rows);
        }
        long maxAccountAge = newAgeSeconds.values().stream().mapToLong(Long::longValue).max().orElse(0);
        Duration window = Duration.ofSeconds(Math.min(ACCOUNT_MAX_AGE.toSeconds() - maxAccountAge, TP_TTL.toSeconds() - tpMaxAge));

        Map<String, String> d = new LinkedHashMap<>();
        d.put("warmPreparation", "test-only publication-metadata rewrite outside measured windows; no product bypass; production freshness and coverage checks unchanged; not evidence the raw capture was fresh");
        d.put("sourceMarkers", markers.toString().trim());
        d.put("tpPrices", "price age lag up to " + tpOriginalMaxLag + "s compressed to <=" + (TP_BASE + TP_SPREAD) + "s");
        d.put("parentGeneration", "equipment/Builds coverage parent_generation set to CHARACTERS generation " + generation.get("CHARACTERS")
                + " (original values: " + originalParents(raw) + ")");
        d.put("clockDelta", "global delta " + global.toSeconds() + "s for non-source time columns, never into the future");
        d.put("factsPreserved", "stock, items, recipes, equipment, builds, inventory, prices and source generations unchanged");
        d.put("warmWindowSeconds", String.valueOf(window.toSeconds()));
        d.put("warmWindowRule", "min(account max age " + ACCOUNT_MAX_AGE.toSeconds() + "s - oldest source age, price TTL " + TP_TTL.toSeconds() + "s - oldest price age); after it the data would expire naturally and the run is not a steady warm run");
        FixtureSnapshot prepared = new FixtureSnapshot(raw.kind(), raw.version(), out, raw.expected(), raw.sourceRevision(),
                "warm-publication preparation (fixture preparation): " + markers.toString().trim());
        return new Result(prepared, d, window);
    }

    private static String originalParents(FixtureSnapshot raw) {
        List<String> p = new ArrayList<>();
        for (var e : COVERAGE_TABLES.entrySet()) {
            for (ObjectNode r : raw.rows(e.getKey())) p.add(e.getKey() + "=" + r.path("parent_generation").asText());
        }
        return p.isEmpty() ? "none" : String.join(",", p);
    }
}
