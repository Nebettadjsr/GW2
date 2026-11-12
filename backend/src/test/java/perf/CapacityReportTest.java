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

    @Test void craftingBurstIsConfigurableProfitRequestsWithinAMinuteThenDetailThenPause() {
        for (int[] range : new int[][]{{5, 30}, {7, 7}}) {
            List<WorkloadProfile.ScheduledStart> plan = WorkloadProfile.CRAFTING_CONTEXT.plan(1, 9, 0, 3_600_000,
                    List.of("crafting-profit", "recipe-detail"), range[0], range[1]);
            List<WorkloadProfile.ScheduledStart> profit = plan.stream().filter(s -> s.journey().equals("crafting-profit")).toList();
            List<WorkloadProfile.ScheduledStart> detail = plan.stream().filter(s -> s.journey().equals("recipe-detail")).toList();
            assertFalse(detail.isEmpty());
            // walk workflows: PROFIT requests precede each detail, the burst spans < 60 s, and the next burst follows the pause
            int idx = 0;
            for (WorkloadProfile.ScheduledStart d : detail) {
                List<WorkloadProfile.ScheduledStart> burst = new java.util.ArrayList<>();
                while (idx < profit.size() && profit.get(idx).offsetMillis() < d.offsetMillis()) burst.add(profit.get(idx++));
                assertTrue(burst.size() >= range[0] && burst.size() <= range[1], "burst size " + burst.size());
                assertTrue(burst.get(burst.size() - 1).offsetMillis() - burst.get(0).offsetMillis() < WorkloadProfile.BURST_SPAN_MS);
                assertTrue(d.offsetMillis() > burst.get(burst.size() - 1).offsetMillis());
                if (idx < profit.size()) {
                    assertTrue(profit.get(idx).offsetMillis() - d.offsetMillis() >= WorkloadProfile.CRAFTING_CONTEXT.pauseMinMs(), "in-game pause follows detail");
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).withBurst(10, 5));
    }

    @Test void adjacentWindowsContinueTheSameUserTimelineInsteadOfRestarting() {
        List<String> all = CapacityConfig.DEFAULT_JOURNEYS;
        List<WorkloadProfile.ScheduledStart> whole = WorkloadProfile.BALANCED.plan(6, 11, 0, 600_000, all, 5, 30);
        List<WorkloadProfile.ScheduledStart> first = WorkloadProfile.BALANCED.plan(6, 11, 0, 300_000, all, 5, 30);
        List<WorkloadProfile.ScheduledStart> second = WorkloadProfile.BALANCED.plan(6, 11, 300_000, 300_000, all, 5, 30);
        List<WorkloadProfile.ScheduledStart> shifted = second.stream()
                .map(s -> new WorkloadProfile.ScheduledStart(s.user(), s.offsetMillis() + 300_000, s.journey(), s.poll())).toList();
        List<WorkloadProfile.ScheduledStart> joined = new java.util.ArrayList<>(first);
        joined.addAll(shifted);
        joined.sort(java.util.Comparator.comparingLong(WorkloadProfile.ScheduledStart::offsetMillis)
                .thenComparingInt(WorkloadProfile.ScheduledStart::user).thenComparing(WorkloadProfile.ScheduledStart::journey));
        assertEquals(whole, joined, "two adjacent windows are exactly the slices of one continuous timeline");
        assertTrue(second.stream().mapToLong(WorkloadProfile.ScheduledStart::offsetMillis).distinct().count() > 1,
                "users are not all re-started at the window start");
    }

    @Test void statusPollsRunEveryFiveSecondsOnlyWhileTheActorStaysOnStatus() {
        List<WorkloadProfile.ScheduledStart> plan = WorkloadProfile.BROWSE_HEAVY.plan(1, 4, 0, 1_200_000, List.of("system-status"), 5, 30);
        List<WorkloadProfile.ScheduledStart> visits = plan.stream().filter(s -> !s.poll()).toList();
        List<WorkloadProfile.ScheduledStart> polls = plan.stream().filter(WorkloadProfile.ScheduledStart::poll).toList();
        assertFalse(polls.isEmpty());
        for (WorkloadProfile.ScheduledStart p : polls) {
            long since = visits.stream().filter(v -> v.offsetMillis() <= p.offsetMillis()).mapToLong(v -> p.offsetMillis() - v.offsetMillis()).min().orElseThrow();
            assertEquals(0, since % WorkloadProfile.STATUS_POLL_MS);
            assertTrue(since < WorkloadProfile.BROWSE_HEAVY.browseMaxMs(), "polls stop when the actor navigates away");
        }
    }

    @Test void sparseProbeAndBurstConfigAreValidated() {
        assertEquals(1, CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).probeSamplesPerJourney());
        assertEquals(5, CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).craftBurstMin());
        assertEquals(30, CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).craftBurstMax());
        assertThrows(IllegalArgumentException.class, () -> CapacityConfig.defaults(WorkloadProfile.BALANCED, 1).withProbeSamples(0));
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

    @Test void privatePathsOfEveryStyleAreRejectedInReportInputs() {
        CapacityConfig c = CapacityConfig.defaults(WorkloadProfile.BALANCED, 3).withJourneys(J);
        CapacityOutcome o = outcome();
        for (String bad : List.of("/home/me/snap", "C:\\Users\\me\\snap", "C:/Users/me/snap", "d:/x", "\\\\server\\share\\snap",
                "~/snap", "see /home/me/snap for input", "dir=/Users/me/x", "..\\up")) {
            assertThrows(IllegalArgumentException.class, () -> new CapacityReport(c, o, Map.of("input", bad)), bad);
        }
        new CapacityReport(c, o, Map.of("commit", "abc", "fixtureSource", "synthetic", "note", "50/50 mix, 1..64 users"));
    }

    @Test void companionArtifactIdMustBeASafePublicIdentifier() {
        CapacityConfig c = CapacityConfig.defaults(WorkloadProfile.BALANCED, 3).withJourneys(J);
        CapacityReport r = new CapacityReport(c, outcome(), Map.of());
        for (String bad : List.of("/tmp/x.jsonl", "..\\x", "a/b", "C:\\x", "", "a b", ".hidden", "x..y")) {
            assertThrows(IllegalArgumentException.class, () -> r.toJson(bad), bad);
        }
        assertNotNull(r.toJson("capacity-balanced-42.jsonl"));
    }
}
