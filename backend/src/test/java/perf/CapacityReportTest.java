package perf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapacityReportTest {
    static final List<String> J = List.of("bank", "materials");

    private CapacityOutcome outcome() {
        CapacityConfig c = CapacityConfig.defaults(WorkloadProfile.BALANCED, 3).withJourneys(J);
        return new CapacityCoordinator(c, new CapacityCoordinatorTest.Fake(l -> l <= 2 ? 1999L : 2001L)).discover();
    }

    @Test void jsonlIsChecksummedLosslessAndEveryOverSampleIsKept() {
        CapacityConfig c = CapacityConfig.defaults(WorkloadProfile.BALANCED, 3).withJourneys(J);
        CapacityOutcome o = outcome();
        CapacityReport r = new CapacityReport(c, o, Map.of("commit", "abc"));
        String jsonl = r.samplesJsonl();
        ObjectNode json = r.toJson("capacity-run-1.samples.jsonl");
        assertTrue(CapacityReport.verifyCompanion(json, jsonl));
        assertFalse(CapacityReport.verifyCompanion(json, jsonl + " "));
        long totalSamples = r.allStages().stream().mapToLong(s -> s.samples().size()).sum();
        assertEquals(totalSamples, json.get("samplesCompanion").get("count").asLong());
        long over = r.allStages().stream().flatMap(s -> s.samples().stream()).filter(s -> s.durationMillis() > 2000).count();
        assertTrue(over > 0);
        assertEquals(over, json.get("overReferenceSamples").size());
        assertEquals(over, jsonl.lines().filter(l -> l.contains("\"overReference\":true")).count());
        assertEquals("BOUNDARY_CONFIRMED", json.get("outcome").get("status").asText());
        assertEquals(2, json.get("outcome").get("confirmedCapacityUsers").asInt());
        assertTrue(json.get("config").get("thresholdSemantics").asText().contains("strictly greater"));
    }

    @Test void reportCarriesNoPrivateIdentifiersAndRejectsPaths() {
        CapacityConfig c = CapacityConfig.defaults(WorkloadProfile.BALANCED, 3).withJourneys(J);
        String text = new CapacityReport(c, outcome(), Map.of("commit", "abc")).toJsonString("x") + new CapacityReport(c, outcome(), Map.of()).samplesJsonl();
        assertFalse(text.toLowerCase().contains("guid"));
        assertFalse(text.toLowerCase().contains("cookie"));
        assertThrows(IllegalArgumentException.class, () -> new CapacityReport(c, outcome(), Map.of("snapshot", "/home/me/private")));
    }

    @Test void lowSamplesAreFlaggedAndDistributionsPresent() {
        CapacityConfig c = CapacityConfig.defaults(WorkloadProfile.BALANCED, 3).withJourneys(J);
        ObjectNode json = new CapacityReport(c, outcome(), Map.of()).toJson("x");
        ObjectNode per = (ObjectNode) json.get("stages").get(2).get("perJourney");
        assertTrue(per.get("browser:bank").get("lowSampleWarning").asBoolean());
        assertTrue(per.get("browser:bank").has("p99"));
        assertTrue(per.get("browser:bank").has("max"));
    }

    @Test void scheduleIsSeededOpenLoopAndOmitsLocalActions() {
        List<WorkloadProfile.ScheduledStart> a = WorkloadProfile.BALANCED.plan(8, 42, 600_000, CapacityConfig.DEFAULT_JOURNEYS);
        List<WorkloadProfile.ScheduledStart> b = WorkloadProfile.BALANCED.plan(8, 42, 600_000, CapacityConfig.DEFAULT_JOURNEYS);
        assertEquals(a, b);
        assertNotEquals(a, WorkloadProfile.BALANCED.plan(8, 43, 600_000, CapacityConfig.DEFAULT_JOURNEYS));
        // only the enabled journeys are scheduled (sorting/search/checkbox are not journeys)
        Set<String> journeys = new java.util.HashSet<>();
        a.forEach(s -> journeys.add(s.journey()));
        assertTrue(CapacityConfig.DEFAULT_JOURNEYS.containsAll(journeys));
        // random phases: users do not all start together
        long distinctFirst = a.stream().filter(s -> true).collect(java.util.stream.Collectors.toMap(
                WorkloadProfile.ScheduledStart::user, WorkloadProfile.ScheduledStart::offsetMillis, Math::min)).values().stream().distinct().count();
        assertTrue(distinctFirst > 1);
        assertTrue(a.stream().allMatch(s -> s.offsetMillis() >= 0 && s.offsetMillis() < 600_000));
    }

    @Test void profilesChangeTheAccountCraftingMix() {
        long[] account = new long[2];
        int i = 0;
        for (WorkloadProfile p : new WorkloadProfile[]{WorkloadProfile.BROWSE_HEAVY, WorkloadProfile.CRAFTING_CONTEXT}) {
            account[i++] = p.plan(40, 5, 3_600_000, List.of("bank", "crafting-profit")).stream().filter(s -> s.journey().equals("bank")).count();
        }
        assertTrue(account[0] > account[1], "browse-heavy must schedule more account journeys than crafting-context");
    }

    @Test void disabledJourneysAreNeverScheduledAndConfigIsValidated() {
        assertTrue(WorkloadProfile.BALANCED.plan(5, 1, 900_000, List.of("bank")).stream().allMatch(s -> s.journey().equals("bank")));
        assertThrows(IllegalArgumentException.class, () -> CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).withLevels(List.of(2, 1), 4));
        assertThrows(IllegalArgumentException.class, () -> CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).withBudget(0));
        assertEquals(WorkloadProfile.BROWSE_HEAVY, WorkloadProfile.parse("browse-heavy"));
    }
}
