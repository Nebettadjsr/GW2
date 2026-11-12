package perf;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Result of one finite measured window at one user level. */
public record StageObservation(int level, String role, long seed, List<CapacitySample> samples,
                               int scheduledStarts, int achievedStarts, int lateStarts, int missedStarts,
                               boolean generatorSaturated, String invalidReason, long elapsedMillis) {
    public static final String COARSE = "coarse", REFINE = "refine", CONFIRM = "confirm", BASELINE = "baseline", WARMUP = "warmup";

    public StageObservation {
        samples = List.copyOf(samples);
    }

    public boolean invalid() { return invalidReason != null; }

    public List<CapacitySample> browserSamples() {
        return samples.stream().filter(s -> CapacitySample.BROWSER.equals(s.kind())).toList();
    }

    /** Enabled journeys with no browser sample in this stage (each must have one to establish all-page capacity). */
    public Set<String> missingJourneys(List<String> enabled) {
        Set<String> seen = browserSamples().stream().map(CapacitySample::journey).collect(Collectors.toSet());
        return enabled.stream().filter(j -> !seen.contains(j)).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public boolean coverageComplete(List<String> enabled) { return missingJourneys(enabled).isEmpty(); }

    public boolean breached(long thresholdMillis) {
        return samples.stream().anyMatch(s -> s.overReference(thresholdMillis));
    }

    public boolean incorrect() { return samples.stream().anyMatch(s -> !s.correct()); }
}
