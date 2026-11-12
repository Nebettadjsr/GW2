package perf;

import java.util.List;

/**
 * Normalized, disclosed configuration of one steady warm capacity-discovery experiment (STORY-KPI-002).
 * Every value is an experiment budget or an assumption, never an expected capacity or an SLA.
 */
public record CapacityConfig(
        long thresholdMillis,
        List<Integer> coarseLevels,
        int maxUsers,
        long maxMillisPerProfile,
        int confirmations,
        int probeBudget,
        long seed,
        WorkloadProfile profile,
        List<String> journeys,
        long observationWindowMillis,
        long drainTimeoutMillis,
        int craftBurstMin,
        int craftBurstMax,
        int probeSamplesPerJourney) {

    /** Documented crafting burst: 5-30 PROFIT requests within one minute (assumption, configurable). */
    public static final int DEFAULT_BURST_MIN = 5, DEFAULT_BURST_MAX = 30;

    public CapacityConfig(long thresholdMillis, List<Integer> coarseLevels, int maxUsers, long maxMillisPerProfile,
                          int confirmations, int probeBudget, long seed, WorkloadProfile profile, List<String> journeys,
                          long observationWindowMillis, long drainTimeoutMillis) {
        this(thresholdMillis, coarseLevels, maxUsers, maxMillisPerProfile, confirmations, probeBudget, seed, profile, journeys,
                observationWindowMillis, drainTimeoutMillis, DEFAULT_BURST_MIN, DEFAULT_BURST_MAX, 1);
    }

    public static final long REFERENCE_MILLIS = 2000;
    public static final List<String> DEFAULT_JOURNEYS = List.of(
            "bank", "materials", "characters", "character-core", "character-inventory",
            "crafting-profit", "recipe-detail", "system-status");

    public CapacityConfig {
        if (thresholdMillis <= 0) throw new IllegalArgumentException("threshold must be positive");
        coarseLevels = List.copyOf(coarseLevels);
        journeys = List.copyOf(journeys);
        if (coarseLevels.isEmpty()) throw new IllegalArgumentException("at least one coarse level is required");
        int prev = 0;
        for (int l : coarseLevels) {
            if (l <= prev) throw new IllegalArgumentException("coarse levels must be strictly ascending and positive");
            prev = l;
        }
        if (maxUsers < 1 || maxUsers > 1000) throw new IllegalArgumentException("maxUsers must be 1..1000");
        if (maxMillisPerProfile <= 0 || observationWindowMillis <= 0 || drainTimeoutMillis <= 0) {
            throw new IllegalArgumentException("time budgets must be finite and positive");
        }
        if (confirmations < 1) throw new IllegalArgumentException("confirmations must be >= 1");
        if (probeBudget < 1 || probeBudget > 2) throw new IllegalArgumentException("probeBudget (browser contexts) must be 1..2");
        if (journeys.isEmpty()) throw new IllegalArgumentException("at least one journey must be enabled");
        if (craftBurstMin < 1 || craftBurstMax < craftBurstMin || craftBurstMax > 200) {
            throw new IllegalArgumentException("crafting burst must satisfy 1 <= min <= max <= 200");
        }
        if (probeSamplesPerJourney < 1 || probeSamplesPerJourney > 5) throw new IllegalArgumentException("probeSamplesPerJourney must be 1..5");
        if (profile == null) throw new IllegalArgumentException("profile is required");
    }

    /** Conservative defaults from the issue: levels 1..64, max 64 users, 30 minutes per profile, 3 confirmations, 1 probe. */
    public static CapacityConfig defaults(WorkloadProfile profile, long seed) {
        return new CapacityConfig(REFERENCE_MILLIS, List.of(1, 2, 4, 8, 16, 32, 64), 64, 30L * 60_000,
                3, 1, seed, profile, DEFAULT_JOURNEYS, 120_000, 60_000);
    }

    public CapacityConfig withJourneys(List<String> enabled) {
        return new CapacityConfig(thresholdMillis, coarseLevels, maxUsers, maxMillisPerProfile, confirmations,
                probeBudget, seed, profile, enabled, observationWindowMillis, drainTimeoutMillis, craftBurstMin, craftBurstMax, probeSamplesPerJourney);
    }

    public CapacityConfig withLevels(List<Integer> levels, int max) {
        return new CapacityConfig(thresholdMillis, levels, max, maxMillisPerProfile, confirmations,
                probeBudget, seed, profile, journeys, observationWindowMillis, drainTimeoutMillis, craftBurstMin, craftBurstMax, probeSamplesPerJourney);
    }

    public CapacityConfig withBudget(long maxMillis) {
        return new CapacityConfig(thresholdMillis, coarseLevels, maxUsers, maxMillis, confirmations,
                probeBudget, seed, profile, journeys, observationWindowMillis, drainTimeoutMillis, craftBurstMin, craftBurstMax, probeSamplesPerJourney);
    }

    public CapacityConfig withBurst(int min, int max) {
        return new CapacityConfig(thresholdMillis, coarseLevels, maxUsers, maxMillisPerProfile, confirmations,
                probeBudget, seed, profile, journeys, observationWindowMillis, drainTimeoutMillis, min, max, probeSamplesPerJourney);
    }

    public CapacityConfig withProbeSamples(int perJourney) {
        return new CapacityConfig(thresholdMillis, coarseLevels, maxUsers, maxMillisPerProfile, confirmations,
                probeBudget, seed, profile, journeys, observationWindowMillis, drainTimeoutMillis, craftBurstMin, craftBurstMax, perJourney);
    }
}
