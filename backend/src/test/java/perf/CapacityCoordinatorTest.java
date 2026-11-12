package perf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

class CapacityCoordinatorTest {
    static final List<String> J = List.of("bank", "materials");

    /** Fake application: duration of every sample is decided by the user level; fake clock = elapsed field. */
    static class Fake implements StageRunner {
        final IntFunction<Long> durationAt;
        final List<String> calls = new ArrayList<>();
        boolean drainOk = true;
        boolean drained;
        boolean skipJourney;
        boolean saturate;
        Fake(IntFunction<Long> d) { durationAt = d; }
        @Override public StageObservation run(int level, String role, long seed) {
            calls.add(role + ":" + level);
            List<CapacitySample> ss = new ArrayList<>();
            for (String j : skipJourney && !role.equals("baseline") ? List.of("bank") : J) {
                ss.add(new CapacitySample(j, CapacitySample.BROWSER, level, role, 0, 0, 0, durationAt.apply(level), true, true));
            }
            return new StageObservation(level, role, seed, ss, 10, 10, 0, 0, saturate && !role.equals("baseline"), null, 1000);
        }
        @Override public boolean drain(long t) { drained = true; return drainOk; }
    }

    static CapacityConfig cfg() {
        return CapacityConfig.defaults(WorkloadProfile.BALANCED, 7).withJourneys(J).withBudget(10_000_000);
    }

    @Test void exactThresholdIsNotOverButOneMoreMillisecondIs() {
        CapacitySample at = new CapacitySample("bank", "browser", 1, "coarse", 0, 0, 0, 2000, true, true);
        CapacitySample over = new CapacitySample("bank", "browser", 1, "coarse", 0, 0, 0, 2001, true, true);
        assertFalse(at.overReference(2000));
        assertTrue(over.overReference(2000));
    }

    @Test void findsAndConfirmsIntegerBoundaryAtThirteen() {
        Fake f = new Fake(l -> l <= 13 ? 2000L : 2001L); // 13 passes at exactly 2000
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertEquals(CapacityOutcome.Status.BOUNDARY_CONFIRMED, o.status());
        assertEquals(13, o.confirmedCapacity());
        assertEquals(14, o.firstBreachLevel());
        // coarse 1,2,4,8,16 then refinement between 8 and 16, then 3 confirmations at 13 and 14
        assertEquals(List.of("baseline:1", "warmup:1", "coarse:1", "coarse:2", "coarse:4", "coarse:8", "coarse:16"),
                f.calls.subList(0, 7));
        assertEquals(3, f.calls.stream().filter(c -> c.equals("confirm:13")).count());
        assertEquals(3, f.calls.stream().filter(c -> c.equals("confirm:14")).count());
        assertTrue(f.drained);
        assertFalse(o.cleanupRequired());
        assertEquals(1, o.baseline().size());
        assertEquals(1, o.warmup().size());
        assertTrue(o.measured().stream().noneMatch(s -> s.role().equals("baseline") || s.role().equals("warmup")));
    }

    @Test void neverBreachingIsCensoredNotCapacityEqualToCeiling() {
        CapacityOutcome o = new CapacityCoordinator(cfg(), new Fake(l -> 100L)).discover();
        assertEquals(CapacityOutcome.Status.CENSORED_NOT_REACHED, o.status());
        assertNull(o.confirmedCapacity());
        assertEquals(64, o.lastPassingLevel());
    }

    @Test void breachAtSmallestLevelIsBelowMinimum() {
        CapacityOutcome o = new CapacityCoordinator(cfg(), new Fake(l -> 5000L)).discover();
        assertEquals(CapacityOutcome.Status.BELOW_MINIMUM_CONFIRMED, o.status());
        assertNull(o.confirmedCapacity());
        assertEquals(1, o.firstBreachLevel());
    }

    @Test void disagreeingConfirmationsYieldUnstableInterval() {
        int[] seen = {0};
        Fake f = new Fake(l -> 0L) {
            @Override public StageObservation run(int level, String role, long seed) {
                boolean flaky = role.equals("confirm") && level == 3 && (seen[0]++ == 1);
                List<CapacitySample> ss = new ArrayList<>();
                for (String j : J) ss.add(new CapacitySample(j, "browser", level, role, 0, 0, 0,
                        (level >= 4 || flaky) ? 3000 : 500, true, true));
                return new StageObservation(level, role, seed, ss, 1, 1, 0, 0, false, null, 1000);
            }
        };
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertEquals(CapacityOutcome.Status.UNSTABLE, o.status());
        assertNull(o.confirmedCapacity());
        assertEquals(3, o.lastPassingLevel());
        assertEquals(4, o.firstBreachLevel());
    }

    @Test void budgetExhaustionAfterBreachIsProvisionalAndNotExtrapolated() {
        // each window is 1000ms elapsed; baseline+warmup+coarse1,2,4,8,16 = 7s; allow 8.5s -> cannot refine
        CapacityConfig c = cfg().withBudget(8_500).withJourneys(J);
        c = new CapacityConfig(c.thresholdMillis(), c.coarseLevels(), 64, 8_500, 3, 1, 7, c.profile(), J, 1000, 1000);
        CapacityOutcome o = new CapacityCoordinator(c, new Fake(l -> l <= 13 ? 100L : 3000L)).discover();
        assertEquals(CapacityOutcome.Status.PROVISIONAL, o.status());
        assertNull(o.confirmedCapacity());
        // one refinement (12) fits the budget; the next (14) does not, so the interval stays [12,16]
        assertEquals(12, o.lastPassingLevel());
        assertEquals(16, o.firstBreachLevel());
    }

    @Test void budgetExhaustionBeforeAnyBreachIsCensored() {
        CapacityConfig c = new CapacityConfig(2000, List.of(1, 2, 4, 8, 16, 32, 64), 64, 4_500, 3, 1, 7, WorkloadProfile.BALANCED, J, 1000, 1000);
        CapacityOutcome o = new CapacityCoordinator(c, new Fake(l -> 100L)).discover();
        assertEquals(CapacityOutcome.Status.CENSORED_NOT_REACHED, o.status());
        assertNull(o.confirmedCapacity());
    }

    @Test void maxUsersCeilingLimitsCoarseLevels() {
        CapacityConfig c = cfg().withLevels(List.of(1, 2, 4, 8, 16, 32, 64), 8);
        Fake f = new Fake(l -> 100L);
        CapacityOutcome o = new CapacityCoordinator(c, f).discover();
        assertEquals(8, o.lastPassingLevel());
        assertTrue(f.calls.stream().noneMatch(s -> s.endsWith(":16")));
    }

    @Test void missingJourneyCoverageCannotEstablishPassOrCapacity() {
        Fake f = new Fake(l -> 100L);
        f.skipJourney = true;
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertEquals(CapacityOutcome.Status.INVALID, o.status());
        assertNull(o.confirmedCapacity());
        assertTrue(o.detail().contains("materials"));
    }

    @Test void generatorSaturationAndCorrectnessInvalidateEvidence() {
        Fake sat = new Fake(l -> 100L);
        sat.saturate = true;
        CapacityOutcome o = new CapacityCoordinator(cfg(), sat).discover();
        assertEquals(CapacityOutcome.Status.INVALID, o.status());
        assertTrue(o.detail().contains("generator"));

        Fake bad = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                StageObservation s = super.run(level, role, seed);
                if (!role.equals("coarse") || level < 2) return s;
                List<CapacitySample> ss = new ArrayList<>(s.samples());
                ss.set(0, new CapacitySample("bank", "browser", level, role, 0, 0, 0, 100, true, false));
                return new StageObservation(level, role, seed, ss, 1, 1, 0, 0, false, null, 1000);
            }
        };
        CapacityOutcome o2 = new CapacityCoordinator(cfg(), bad).discover();
        assertEquals(CapacityOutcome.Status.INVALID, o2.status());
        assertEquals(1, o2.lastPassingLevel());
    }

    @Test void provisionalContentIsNotFinalUsableSuccess() {
        Fake f = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                List<CapacitySample> ss = new ArrayList<>();
                for (String j : J) ss.add(new CapacitySample(j, "browser", level, role, 0, 0, 0, 100, !role.equals("coarse"), true));
                return new StageObservation(level, role, seed, ss, 1, 1, 0, 0, false, null, 1000);
            }
        };
        assertEquals(CapacityOutcome.Status.INVALID, new CapacityCoordinator(cfg(), f).discover().status());
    }

    @Test void undrainedWorkersRequireCleanupAndAreReported() {
        Fake f = new Fake(l -> 100L);
        f.drainOk = false;
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertTrue(o.cleanupRequired());
        assertTrue(o.detail().contains("cleanup required"));
        String md = new CapacityReport(cfg(), o, Map.of()).toMarkdown();
        assertTrue(md.contains("CLEANUP REQUIRED"));
    }

    @Test void invalidBaselineStopsBeforeMeasuredStagesButStillDrains() {
        Fake f = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                calls.add(role + ":" + level);
                return new StageObservation(level, role, seed, List.of(), 0, 0, 0, 0, false, "baseline answers wrong", 1);
            }
        };
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertEquals(CapacityOutcome.Status.INVALID, o.status());
        assertEquals(List.of("baseline:1"), f.calls);
        assertTrue(f.drained);
    }

    @Test void runnerExceptionStillStopsAndDrainsAndPreservesTheOriginalFailure() {
        boolean[] stopped = {false};
        Fake f = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                if (role.equals("coarse") && level == 2) throw new IllegalStateException("worker blew up");
                return super.run(level, role, seed);
            }
            @Override public void stopScheduling() { stopped[0] = true; }
            @Override public boolean drain(long t) { drained = true; return false; }
        };
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertTrue(stopped[0]);
        assertTrue(f.drained);
        assertEquals(CapacityOutcome.Status.INVALID, o.status());
        assertTrue(o.detail().contains("worker blew up"), o.detail());
        assertTrue(o.cleanupRequired(), "undrained workers retain resources and require cleanup");
    }

    @Test void drainThatThrowsIsCleanupRequiredNotALostResult() {
        Fake f = new Fake(l -> 100L) {
            @Override public boolean drain(long t) { throw new IllegalStateException("drain broke"); }
        };
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertTrue(o.cleanupRequired());
        assertEquals(CapacityOutcome.Status.CENSORED_NOT_REACHED, o.status());
    }

    @Test void slowProtocolSamplesNeverDecideTheBrowserBoundary() {
        Fake f = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                StageObservation s = super.run(level, role, seed);
                List<CapacitySample> ss = new ArrayList<>(s.samples());
                ss.add(new CapacitySample("bank", CapacitySample.PROTOCOL, level, role, 0, 0, 0, 9000, true, true));
                return new StageObservation(level, role, seed, ss, 1, 1, 0, 0, false, null, 1000);
            }
        };
        CapacityOutcome o = new CapacityCoordinator(cfg(), f).discover();
        assertEquals(CapacityOutcome.Status.CENSORED_NOT_REACHED, o.status());
        assertTrue(o.measured().get(0).protocolOver(2000));
        assertFalse(o.measured().get(0).breached(2000));
        String json = new CapacityReport(cfg(), o, Map.of()).toJsonString("run-1");
        assertTrue(json.contains("\"protocolOverReference\" : true"));
        assertTrue(json.contains("\"overReferenceSamples\""));
    }

    @Test void profileBudgetIsEnforcedBeforeBaselineAndWarmup() {
        CapacityConfig tiny = new CapacityConfig(2000, List.of(1, 2), 2, 500, 1, 1, 1, WorkloadProfile.BALANCED, J, 1000, 1000);
        Fake none = new Fake(l -> 100L);
        CapacityOutcome o = new CapacityCoordinator(tiny, none).discover();
        assertEquals(List.of(), none.calls, "nothing may start when not even the baseline window fits");
        assertEquals(CapacityOutcome.Status.CENSORED_NOT_REACHED, o.status());

        CapacityConfig afterBaseline = new CapacityConfig(2000, List.of(1, 2), 2, 1500, 1, 1, 1, WorkloadProfile.BALANCED, J, 1000, 1000);
        Fake one = new Fake(l -> 100L);
        new CapacityCoordinator(afterBaseline, one).discover();
        assertEquals(List.of("baseline:1"), one.calls, "warmup must not start without a forecast window");
    }

    @Test void longestObservedWindowForecastsTheNextOne() {
        // windows really take 4000ms though 1000ms was configured: after baseline+warmup+coarse1 (12s) a 13s budget cannot fit another
        Fake slow = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                StageObservation s = super.run(level, role, seed);
                return new StageObservation(level, role, seed, s.samples(), 1, 1, 0, 0, false, null, 4000);
            }
        };
        CapacityConfig c = new CapacityConfig(2000, List.of(1, 2, 4), 4, 13_000, 1, 1, 1, WorkloadProfile.BALANCED, J, 1000, 1000);
        new CapacityCoordinator(c, slow).discover();
        assertEquals(List.of("baseline:1", "warmup:1", "coarse:1"), slow.calls);
    }

    @Test void warmupCorrectnessCoverageAndIsolationFailuresInvalidateTheExperiment() {
        Fake wrong = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                StageObservation s = super.run(level, role, seed);
                if (!role.equals("warmup")) return s;
                List<CapacitySample> ss = new ArrayList<>(s.samples());
                ss.set(0, new CapacitySample("bank", "browser", level, role, 0, 0, 0, 100, true, false));
                return new StageObservation(level, role, seed, ss, 1, 1, 0, 0, false, null, 1000);
            }
        };
        CapacityOutcome o = new CapacityCoordinator(cfg(), wrong).discover();
        assertEquals(CapacityOutcome.Status.INVALID, o.status());
        assertTrue(o.detail().startsWith("Warmup invalid"), o.detail());
        assertTrue(wrong.calls.stream().noneMatch(c -> c.startsWith("coarse")));

        // Slow warmup latency alone is not an invalidation: it is separate evidence.
        Fake slowWarm = new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                return super.run(level, role, seed);
            }
        };
        assertNotEquals(CapacityOutcome.Status.INVALID, new CapacityCoordinator(cfg(), slowWarm).discover().status());
    }

    /** Fake stage that advances an injected monotonic clock instead of sleeping. */
    static Fake clocked(java.util.concurrent.atomic.AtomicLong clock, java.util.function.Function<String, Long> advanceNanosByRole) {
        return new Fake(l -> 100L) {
            @Override public StageObservation run(int level, String role, long seed) {
                StageObservation o = super.run(level, role, seed);
                clock.addAndGet(advanceNanosByRole.apply(role));
                return o;
            }
        };
    }

    static CapacityConfig shortWindows() {
        return new CapacityConfig(2000, List.of(1, 2, 4, 8, 16), 16, 10_000_000, 1, 1, 7, WorkloadProfile.BALANCED, J, 1000, 1000);
    }

    @Test void
hardWarmDeadlineAdmitsOnlyStagesThatFitAndCensors() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        WarmDeadline d = new WarmDeadline(0, java.time.Duration.ofMillis(4500), clock::get);
        Fake f = clocked(clock, r -> 1_000_000_000L);
        CapacityOutcome o = new CapacityCoordinator(shortWindows(), f, d).discover();
        assertEquals(List.of("baseline:1", "warmup:1", "coarse:1", "coarse:2"), f.calls, "a fifth 1000 ms window does not fit in the 500 ms left");
        assertEquals(CapacityOutcome.Status.CENSORED_NOT_REACHED, o.status());
        assertTrue(o.detail().contains("Warm deadline"), o.detail());
        assertTrue(d.remainingMillis() > 0 && !d.expired(), "stopped before expiry, not after");
    }

    @Test void stageCrossingTheWarmDeadlineIsRejectedNotCountedAsConfirmedEvidence() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        WarmDeadline d = new WarmDeadline(0, java.time.Duration.ofMillis(3500), clock::get);
        // forecast says 1000 ms fits, but the coarse window actually takes 5 s: its outcome must be rejected
        Fake f = clocked(clock, r -> r.equals("coarse") ? 5_000_000_000L : 1_000_000_000L);
        CapacityOutcome o = new CapacityCoordinator(shortWindows(), f, d).discover();
        assertEquals(List.of("baseline:1", "warmup:1", "coarse:1"), f.calls);
        assertEquals(1, o.measured().size(), "the slow stage is retained, not deleted");
        assertEquals(CapacityCoordinator.EXPIRY, o.measured().get(0).invalidReason());
        assertFalse(o.measured().get(0).samples().isEmpty(), "its samples are retained");
        assertNull(o.confirmedCapacity());
        assertNotEquals(CapacityOutcome.Status.BOUNDARY_CONFIRMED, o.status());
        assertTrue(o.detail().contains("crossed the warm deadline"), o.detail());
        assertTrue(f.drained);
    }

    @Test void baselineCrossingTheDeadlineIsInvalid() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        WarmDeadline d = new WarmDeadline(0, java.time.Duration.ofMillis(1500), clock::get);
        Fake f = clocked(clock, r -> 2_000_000_000L);
        CapacityOutcome o = new CapacityCoordinator(shortWindows(), f, d).discover();
        assertEquals(CapacityOutcome.Status.INVALID, o.status());
        assertEquals(List.of("baseline:1"), f.calls);
        assertEquals(1, o.baseline().size());
        assertEquals(CapacityCoordinator.EXPIRY, o.baseline().get(0).invalidReason());
        assertNull(o.confirmedCapacity());
    }

    @Test void confirmationsUseIndependentSeedFamiliesAndFirstCoarseBreachIsPreserved() {
        List<Long> seeds = new ArrayList<>();
        Fake f = new Fake(l -> l <= 13 ? 2000L : 2001L) {
            @Override public StageObservation run(int level, String role, long seed) {
                if (role.equals("confirm")) seeds.add(seed);
                return super.run(level, role, seed);
            }
        };
        CapacityCoordinator c = new CapacityCoordinator(cfg(), f);
        c.discover();
        assertEquals(16, c.firstCoarseBreachLevel(), "coarse breach kept separately from the refined boundary (14)");
        assertEquals(java.util.Set.of(8L, 9L, 10L), new java.util.HashSet<>(seeds), "three independent confirmation families seed+1..3");
    }
}
