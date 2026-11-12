package perf;

import java.util.List;

/** Honest, never-extrapolated result of a capacity experiment. */
public record CapacityOutcome(Status status, Integer lastPassingLevel, Integer firstBreachLevel,
                              Integer confirmedCapacity, String detail, boolean cleanupRequired,
                              List<StageObservation> baseline, List<StageObservation> warmup,
                              List<StageObservation> measured) {
    public enum Status {
        /** Highest passing level L and L+1 both repeat (all confirmations) – capacity is L simultaneous users. */
        BOUNDARY_CONFIRMED,
        /** Even the smallest level breached repeatedly: capacity below the minimum level. */
        BELOW_MINIMUM_CONFIRMED,
        /** Confirmation observations disagree: interval, not a point. */
        UNSTABLE,
        /** A breach was seen but refinement/confirmation did not finish within budget. */
        PROVISIONAL,
        /** User/time/generator ceiling reached before a breach: capacity not reached, never the ceiling. */
        CENSORED_NOT_REACHED,
        /** Correctness, isolation, coverage or generator behaviour invalidated the evidence. */
        INVALID
    }

    public CapacityOutcome {
        baseline = List.copyOf(baseline);
        warmup = List.copyOf(warmup);
        measured = List.copyOf(measured);
    }
}
