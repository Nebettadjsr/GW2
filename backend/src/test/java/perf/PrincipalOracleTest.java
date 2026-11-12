package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Default-CI proof that the oracle tells principals, economics and states apart where HTTP 200 / a global hash cannot. */
class PrincipalOracleTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static PrincipalOracle oracle;

    @BeforeAll
    static void load() throws Exception {
        FixtureSnapshot base = SyntheticFixtures.build();
        Map<String, List<ObjectNode>> only = new LinkedHashMap<>();
        for (String t : FixtureSnapshot.ALL_TABLES) {
            boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(t);
            only.put(t, base.rows(t).stream().filter(r -> !scoped || SyntheticFixtures.A.equals(r.get("account_id").asText())).toList());
        }
        FixtureSnapshot a = new FixtureSnapshot(base.kind(), base.version(), only, Map.of(), base.sourceRevision(), null);
        oracle = PrincipalOracle.from(SnapshotImporter.principalCopies(a, 2));
    }

    private static JsonNode json(String s) throws Exception { return M.readTree(s); }

    @Test
    void factsComeFromEachCopysOwnLoadedRowsAndMatchTheHandWrittenExpectation() {
        // SyntheticFixtures.expected() for A, written by hand: 3 bank slots / 85 items, 4 material rows / 400, 2 characters.
        for (int u = 0; u < 2; u++) {
            PrincipalOracle.Facts f = oracle.facts(u);
            assertEquals(3, f.bankSlots());
            assertEquals(85, f.bankCountSum());
            assertEquals(4, f.materialRows());
            assertEquals(java.util.List.of("5:1:1:200", "5:2:2:150", "5:3:3:40", "5:4:4:10", "5:5:5:0", "5:6:6:0"), f.displayedMaterials());
            assertEquals(400, f.materialTotal());
            assertEquals(2, f.characters().size());
        }
        assertTrue(oracle.facts(0).characters().contains("Aria c0"));
        assertTrue(oracle.facts(1).characters().contains("Aria c1"));
        assertTrue(oracle.facts(0).characters().stream().noneMatch(n -> n.endsWith("c1")), "copy 0 owns none of copy 1's names");
    }

    @Test
    void anotherPrincipalsRosterIsRejectedEvenThoughBothAreHttp200AndSameSize() {
        PrincipalOracle o = fresh();
        PrincipalOracle o2 = fresh();
        List<String> other = oracle.facts(1).characters().stream().toList();
        assertFalse(o2.verifyRoster(0, other), "same count, other principal's identities");
        assertTrue(o2.mismatch().contains("principal 0"));
        assertFalse(o2.mismatch().contains("c1"), "reports carry no names");
        assertFalse(fresh().verifyRoster(0, List.of(oracle.facts(0).characters().iterator().next())), "missing character");
        assertFalse(fresh().ownsCharacter(0, "Aria c1"));
    }

    @Test
    void wrongBankAndMaterialCountsAreRejectedAndZeroIsNotMissing() throws Exception {
        assertTrue(fresh().verifyBank(0, json("{\"slotCount\":3,\"slots\":[{\"count\":50},{\"count\":30},{\"count\":5}]}")));
        assertFalse(fresh().verifyBank(0, json("{\"slotCount\":3,\"slots\":[{\"count\":50},{\"count\":30},{\"count\":6}]}")), "wrong sum");
        assertFalse(fresh().verifyBank(0, json("{\"slotCount\":2,\"slots\":[{\"count\":50},{\"count\":35}]}")), "wrong slot count");
        assertFalse(fresh().verifyBank(0, json("{\"slotCount\":0,\"slots\":[]}")), "empty is not A's bank");
        String mats = matsJson(200, 150, 40, 10, 0, 0);
        assertTrue(fresh().verifyMaterials(1, json(mats)), "six catalog positions, two zero-filled");
        assertFalse(fresh().verifyMaterials(1, json(matsJson(200, 150, 40, 11, 0, 0))), "wrong quantity");
        assertFalse(fresh().verifyMaterials(1, json(matsJson(150, 200, 40, 10, 0, 0))), "swapped quantities, same total");
        assertFalse(fresh().verifyMaterials(1, json(matsJson(200, 150, 40, 10, 1, 0))), "zero position shown non-zero");
        assertFalse(fresh().verifyMaterials(1, json("{\"categories\":[]}")), "missing materials");
        assertFalse(fresh().verifyMaterials(1, json(mats.replace(",{\"position\":6,\"itemId\":6,\"count\":0}", ""))), "missing catalog position");
    }

    private static String matsJson(int... counts) {
        StringBuilder sb = new StringBuilder("{\"categories\":[{\"category\":5,\"materials\":[");
        for (int i = 0; i < counts.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"position\":").append(i + 1).append(",\"itemId\":").append(i + 1)
                    .append(",\"count\":").append(counts[i]).append("}");
        }
        return sb.append("]}]}").toString();
    }

    @Test
    void mixedOccupiedAndEmptyBankSlotsCountEverySlotButOnlyOccupiedQuantities() throws Exception {
        FixtureSnapshot base = SyntheticFixtures.build();
        Map<String, List<ObjectNode>> rows = new LinkedHashMap<>();
        for (String t : FixtureSnapshot.ALL_TABLES) {
            boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(t);
            rows.put(t, new java.util.ArrayList<>(base.rows(t).stream().filter(r -> !scoped || SyntheticFixtures.A.equals(r.get("account_id").asText())).toList()));
        }
        ObjectNode empty = rows.get("account_bank").get(0).deepCopy();
        empty.put("slot", 3);
        empty.putNull("item_id");
        empty.putNull("count");
        rows.get("account_bank").add(empty);
        PrincipalOracle o = PrincipalOracle.from(SnapshotImporter.principalCopies(
                new FixtureSnapshot(base.kind(), base.version(), rows, Map.of(), base.sourceRevision(), null), 1));
        assertEquals(4, o.facts(0).bankSlots(), "empty slot is a served slot");
        assertEquals(85, o.facts(0).bankCountSum());
        assertEquals(3, o.facts(0).bankItemIds().size());
        String bank = "{\"slotCount\":4,\"slots\":[{\"count\":50},{\"count\":30},{\"count\":5},{\"itemId\":null}]}";
        assertTrue(o.verifyBank(0, json(bank)));
        assertFalse(o.verifyBank(0, json(bank.replace("\"slotCount\":4", "\"slotCount\":3"))));
    }

    @Test
    void playedTimeCreationAndBirthdayAreCharacterFactsNotTransient() throws Exception {
        String base = "{\"ageSeconds\":100,\"createdAt\":\"2020-01-01T00:00:00Z\",\"nextBirthdayAt\":\"2027-01-01T00:00:00Z\",\"deaths\":3,\"completedAt\":\"x\"}";
        String c = PrincipalOracle.canonical(json(base));
        assertEquals(c, PrincipalOracle.canonical(json(base.replace("\"x\"", "\"y\""))), "operational timestamp ignored");
        for (String changed : List.of(base.replace("100", "101"), base.replace("2020", "2021"), base.replace("2027", "2028"), base.replace(":3", ":4"))) {
            assertNotEquals(c, PrincipalOracle.canonical(json(changed)), changed);
        }
    }

    @Test
    void frozenBaselineIsPerPrincipalAndCatchesEconomicCountAndStateChangesButNotTransientFields() throws Exception {
        PrincipalOracle o = fresh();
        String base = "{\"calculationId\":\"c1\",\"generatedAt\":\"t1\",\"rows\":[{\"recipeId\":20001,\"craftableCount\":60,\"profit\":120,\"state\":\"FRESH\"}]}";
        o.freeze(0, "crafting-profit", PrincipalOracle.canonical(json(base)));
        o.freeze(1, "crafting-profit", PrincipalOracle.canonical(json(base.replace("60", "3"))));
        assertTrue(o.matchesFrozen(0, "crafting-profit",
                PrincipalOracle.canonical(json(base.replace("\"c1\"", "\"c2\"").replace("\"t1\"", "\"t9\"")))), "only declared transient fields are ignored");
        assertEquals(null, o.mismatch());
        // principal 1 answered principal 0's (valid-looking) result: a global hash would accept it
        assertFalse(o.matchesFrozen(1, "crafting-profit", PrincipalOracle.canonical(json(base))));
        assertNotNull(o.mismatch());
        for (String wrong : List.of(base.replace("120", "121"), base.replace("60", "0"), base.replace("\"FRESH\"", "\"STALE\""),
                base.replace(",\"profit\":120", ""), base.replace("[{", "[{\"extra\":null,"))) {
            assertFalse(o.matchesFrozen(0, "crafting-profit", PrincipalOracle.canonical(json(wrong))), wrong);
        }
    }

    @Test
    void anUnfrozenPrincipalCannotContributeAndFreezingTwiceIsRefused() {
        PrincipalOracle o = fresh();
        assertFalse(o.matchesFrozen(0, "bank", "x"));
        assertTrue(o.mismatch().contains("no serial baseline"));
        o.freeze(0, "bank", "x");
        assertThrows(IllegalStateException.class, () -> o.freeze(0, "bank", "y"));
        assertTrue(o.frozen(0, "bank"));
        assertFalse(o.frozen(1, "bank"));
    }

    @Test
    void protectionIsMonotonicNotZeroAndUnavailableCountsAreNeverCoerced() {
        assertTrue(fresh().verifyProtection(0, 60, 50, 10, 0));
        assertTrue(fresh().verifyProtection(0, 60, 50, 10, 7), "character-inventory stock remains craftable");
        assertTrue(fresh().verifyProtection(1, 3, 3, 3, 3));
        assertFalse(fresh().verifyProtection(0, 60, 61, 10, 0), "protection raised the count");
        assertFalse(fresh().verifyProtection(0, 60, 50, 10, 20), "combined weaker than a single source");
        assertFalse(fresh().verifyProtection(0, 60, 50, -1, 0), "unavailable is not zero");
    }

    @Test
    void craftableTotalSumsRowsAndTreatsMissingAsZeroOnlyWhenAbsentFromEveryRowField() throws Exception {
        assertEquals(63, PrincipalOracle.craftableTotal(json("{\"rows\":[{\"craftableCount\":60},{\"craftableCount\":3}]}")));
        assertEquals(0, PrincipalOracle.craftableTotal(json("{\"rows\":[]}")));
        assertEquals(-1, PrincipalOracle.craftableTotal(json("{\"rows\":[{\"recipeId\":1}]}")), "missing count is unavailable, not zero");
    }

    @Test
    void detailMustNameTheSelectedCharacter() throws Exception {
        assertTrue(fresh().verifyDetailNames(0, "Aria c0", json("{\"character\":{\"name\":\"Aria c0\"}}")));
        assertFalse(fresh().verifyDetailNames(0, "Aria c0", json("{\"character\":{\"name\":\"Aria c1\"}}")));
    }

    private static PrincipalOracle fresh() {
        try {
            return PrincipalOracle.from(copies());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static SnapshotImporter.Copies copies() throws Exception {
        FixtureSnapshot base = SyntheticFixtures.build();
        Map<String, List<ObjectNode>> only = new LinkedHashMap<>();
        for (String t : FixtureSnapshot.ALL_TABLES) {
            boolean scoped = FixtureSnapshot.SCOPED_TABLES.contains(t);
            only.put(t, base.rows(t).stream().filter(r -> !scoped || SyntheticFixtures.A.equals(r.get("account_id").asText())).toList());
        }
        return SnapshotImporter.principalCopies(new FixtureSnapshot(base.kind(), base.version(), only, Map.of(), base.sourceRevision(), null), 2);
    }
}
