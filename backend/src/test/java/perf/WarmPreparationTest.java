package perf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Default fast tests for the test-only warm-publication preparation against realistic stale/older states. */
class WarmPreparationTest {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2031-05-05T10:00:00Z");

    /** Single-account synthetic capture made as stale as the private input: lags, older optional coverage, old parents. */
    static FixtureSnapshot staleCapture() {
        FixtureSnapshot base = SyntheticFixtures.build();
        OffsetDateTime newest = OffsetDateTime.parse("2030-01-10T00:00:00Z");
        Map<String, List<ObjectNode>> t = new LinkedHashMap<>();
        for (String table : FixtureSnapshot.ALL_TABLES) {
            boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(table);
            List<ObjectNode> rows = new ArrayList<>();
            for (ObjectNode r : base.rows(table)) {
                if (scoped && !SyntheticFixtures.A.equals(r.get("account_id").asText())) continue;
                ObjectNode c = r.deepCopy();
                if (table.equals("account_source_state")) {
                    long lag = switch (c.get("source").asText()) {
                        case "BANK" -> 0; case "MATERIALS" -> 7; case "ACCOUNT_RECIPES" -> 3600; case "LUCK" -> 90000;
                        case "CHARACTERS" -> 120; case "CHARACTER_EQUIPMENT_TABS" -> 325684; default -> 200000;
                    };
                    c.put("completed_at", newest.minusSeconds(lag).toString());
                    if (c.get("source").asText().equals("CHARACTERS")) c.put("generation", 5);
                }
                if (table.equals("tp_prices")) c.put("fetched_at", newest.minusSeconds(rows.size() % 2 == 0 ? 300560 : 12).toString());
                if (table.endsWith("_coverage")) c.put("parent_generation", 2);
                rows.add(c);
            }
            t.put(table, rows);
        }
        return new FixtureSnapshot(base.kind(), base.version(), t, Map.of(), base.sourceRevision(), null);
    }

    @Test
    void staleOlderStateBecomesFreshUnderNormalLimitsWithoutChangingFacts() throws Exception {
        FixtureSnapshot raw = staleCapture();
        String rawIdentity = raw.identity();
        WarmPreparation.Result r = WarmPreparation.prepare(raw, NOW);
        assertEquals(rawIdentity, raw.identity(), "raw capture is not modified");
        assertNotEquals(rawIdentity, r.snapshot().identity());
        Map<String, OffsetDateTime> at = new HashMap<>();
        for (ObjectNode s : r.snapshot().rows("account_source_state")) {
            OffsetDateTime v = OffsetDateTime.parse(s.get("completed_at").asText());
            at.put(s.get("source").asText(), v);
            assertFalse(application.AccountDataFreshnessPolicy.isStale(Optional.of(v.toInstant()), NOW.toInstant(), WarmPreparation.ACCOUNT_MAX_AGE),
                    s.get("source").asText() + " is fresh under the normal limit");
            Duration remaining = WarmPreparation.ACCOUNT_MAX_AGE.minus(Duration.between(v, NOW));
            assertTrue(remaining.compareTo(r.warmWindow()) >= 0, "warm window never exceeds a source's remaining freshness");
        }
        assertTrue(at.get("ACCOUNT_RECIPES").isBefore(at.get("CHARACTERS")), "relative source order is kept (recipes older than characters)");
        assertTrue(at.get("CHARACTER_EQUIPMENT_TABS").isBefore(at.get("BANK")), "optional coverage stays older than required sources");
        for (ObjectNode p : r.snapshot().rows("tp_prices")) {
            assertTrue(sync.tp.TpPriceFreshness.isFresh(OffsetDateTime.parse(p.get("fetched_at").asText()).toInstant(), NOW.plus(r.warmWindow()).toInstant()),
                    "price fresh through the whole warm window");
        }
        assertTrue(r.warmWindow().toSeconds() > 300, "bounded window: " + r.warmWindow());
        // Older equipment + build parent references now match the CHARACTERS generation (5), the only relationship the product checks.
        for (String cov : List.of("character_equipment_coverage", "character_build_coverage")) {
            assertFalse(r.snapshot().rows(cov).isEmpty());
            for (ObjectNode c : r.snapshot().rows(cov)) assertEquals(5, c.get("parent_generation").asLong());
        }
        // Generations, stock and every non-time fact are identical.
        for (String table : FixtureSnapshot.ALL_TABLES) {
            assertEquals(raw.rows(table).size(), r.snapshot().rows(table).size(), table);
            for (int i = 0; i < raw.rows(table).size(); i++) {
                ObjectNode a = raw.rows(table).get(i).deepCopy(), b = r.snapshot().rows(table).get(i).deepCopy();
                for (String f : List.of("fetched_at", "completed_at", "last_seen_at", "last_successful_sync_at", "created_at", "updated_at", "created_at_gw", "parent_generation")) { a.remove(f); b.remove(f); }
                assertEquals(a, b, table + " facts");
            }
        }
        // Nothing lies in the future, which the normal policy treats as stale.
        for (var e : r.snapshot().tables().entrySet()) {
            for (ObjectNode row : e.getValue()) {
                for (String f : List.of("fetched_at", "completed_at", "last_seen_at", "last_successful_sync_at")) {
                    if (row.hasNonNull(f)) assertFalse(OffsetDateTime.parse(row.get(f).asText()).isAfter(NOW), e.getKey() + "." + f);
                }
            }
        }
        assertTrue(r.snapshot().clockTransformation().contains("fixture preparation"));
        assertTrue(r.disclosure().get("sourceMarkers").contains("lag 325684s"));
        assertTrue(r.disclosure().get("tpPrices").contains("300548s"));
        assertTrue(r.disclosure().get("parentGeneration").contains("character_equipment_coverage=2"));
        assertTrue(r.disclosure().get("warmPreparation").contains("not evidence the raw capture was fresh"));
    }

    @Test
    void identityDiffersAndPrincipalCopiesStayConsistentAfterPreparation() throws Exception {
        FixtureSnapshot prepared = WarmPreparation.prepare(staleCapture(), NOW).snapshot();
        SnapshotImporter.Copies copies = SnapshotImporter.principalCopies(prepared, 3);
        assertEquals(3, copies.accountIds().size());
        assertEquals(prepared.rows("account_source_state").size() * 3, copies.snapshot().rows("account_source_state").size());
        assertEquals(prepared.rows("tp_prices").size(), copies.snapshot().rows("tp_prices").size(), "shared catalog once");
    }

    @Test
    void missingRequiredSourceIsRefusedNotFabricated() {
        FixtureSnapshot raw = staleCapture();
        Map<String, List<ObjectNode>> t = new LinkedHashMap<>(raw.tables());
        t.put("account_source_state", raw.rows("account_source_state").stream().filter(s -> !s.get("source").asText().equals("BANK")).toList());
        FixtureSnapshot missing = new FixtureSnapshot(raw.kind(), raw.version(), t, Map.of(), raw.sourceRevision(), null);
        assertThrows(SnapshotImporter.ImportException.class, () -> WarmPreparation.prepare(missing, NOW));
    }
}
