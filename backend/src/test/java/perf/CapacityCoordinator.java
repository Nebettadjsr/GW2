package perf;

import java.util.ArrayList;
import java.util.List;

/**
 * Baseline, warmup, coarse ramp, integer refinement and confirmation selection (STORY-KPI-002).
 * Pure decision logic over a {@link StageRunner}; deterministic and testable with fake runners.
 * A stage "passes" only when every sample is {@code <= threshold}, every enabled journey has a
 * browser sample, nothing is provisional/incorrect and the generator was not saturated.
 */
public final class CapacityCoordinator {
    private final CapacityConfig config;
    private final StageRunner runner;
    private final List<StageObservation> baseline = new ArrayList<>();
    private final List<StageObservation> warmup = new ArrayList<>();
    private final List<StageObservation> measured = new ArrayList<>();
    private long spentMillis;

    public CapacityCoordinator(CapacityConfig config, StageRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    private enum Verdict { PASS, BREACH, INVALID, INCOMPLETE, SATURATED, BUDGET }

    private record Result(Verdict verdict, String detail) { }

    public CapacityOutcome discover() {
        CapacityOutcome outcome;
        try {
            outcome = run();
        } finally {
            // stop scheduling is the runner's job; drain must finish before any teardown
        }
        boolean drained = runner.drain(config.drainTimeoutMillis());
        if (!drained) {
            outcome = new CapacityOutcome(outcome.status(), outcome.lastPassingLevel(), outcome.firstBreachLevel(),
                    outcome.confirmedCapacity(), outcome.detail() + " Workers did not drain: isolated resources retained; cleanup required.",
                    true, baseline, warmup, measured);
        }
        return outcome;
    }

    private CapacityOutcome run() {
        // Preflight no-load baseline and warmup (separate sample sets, not measured stages).
        StageObservation b = runner.run(1, StageObservation.BASELINE, config.seed());
        baseline.add(b);
        spentMillis += b.elapsedMillis();
        String bad = validate(b, false);
        if (bad != null) return end(CapacityOutcome.Status.INVALID, null, null, null, "Baseline invalid: " + bad);
        StageObservation w = runner.run(1, StageObservation.WARMUP, config.seed());
        warmup.add(w);
        spentMillis += w.elapsedMillis();
        if (w.invalid()) return end(CapacityOutcome.Status.INVALID, null, null, null, "Warmup invalid: " + w.invalidReason());

        int lastPass = 0;
        int breach = -1;
        for (int level : config.coarseLevels()) {
            if (level > config.maxUsers()) break;
            Result r = observe(level, StageObservation.COARSE, config.seed());
            if (r.verdict == Verdict.BREACH) { breach = level; break; }
            if (r.verdict == Verdict.PASS) { lastPass = level; continue; }
            return stop(r, lastPass, null);
        }
        if (breach < 0) {
            return end(CapacityOutcome.Status.CENSORED_NOT_REACHED, lastPass == 0 ? null : lastPass, null, null,
                    "No breach up to the configured ceiling (" + lastPass + " users); capacity is not reached, not equal to the ceiling.");
        }
        int lo = lastPass, hi = breach;
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            Result r = observe(mid, StageObservation.REFINE, config.seed());
            if (r.verdict == Verdict.BREACH) hi = mid;
            else if (r.verdict == Verdict.PASS) lo = mid;
            else return stop(r, lo == 0 ? null : lo, hi);
        }
        int lowBreaches = 0, lowObs = 0, highBreaches = 0;
        for (int i = 1; i <= config.confirmations(); i++) {
            long seed = config.seed() + i;
            if (lo >= 1) {
                Result r = observe(lo, StageObservation.CONFIRM, seed);
                if (r.verdict == Verdict.BREACH) { lowBreaches++; lowObs++; }
                else if (r.verdict == Verdict.PASS) lowObs++;
                else return stop(r, lo, hi);
            }
            Result r = observe(hi, StageObservation.CONFIRM, seed);
            if (r.verdict == Verdict.BREACH) highBreaches++;
            else if (r.verdict != Verdict.PASS) return stop(r, lo == 0 ? null : lo, hi);
        }
        int n = config.confirmations();
        boolean lowStable = lo < 1 || lowBreaches == 0;
        if (lowStable && highBreaches == n) {
            return lo < 1
                    ? end(CapacityOutcome.Status.BELOW_MINIMUM_CONFIRMED, null, hi, null,
                            "Level " + hi + " breached on all " + n + " confirmations; capacity is below the smallest level.")
                    : end(CapacityOutcome.Status.BOUNDARY_CONFIRMED, lo, hi, lo,
                            lo + " users passed and " + hi + " breached on all " + n + " confirmations (sparse probes: low-sample evidence).");
        }
        return end(CapacityOutcome.Status.UNSTABLE, lo == 0 ? null : lo, hi, null,
                "Confirmations disagree (" + lowBreaches + "/" + lowObs + " breaches at " + lo + ", "
                        + highBreaches + "/" + n + " at " + hi + "); boundary lies in [" + lo + "," + hi + "].");
    }

    private CapacityOutcome stop(Result r, Integer lastPass, Integer firstBreach) {
        CapacityOutcome.Status s = switch (r.verdict) {
            case INVALID, INCOMPLETE, SATURATED -> CapacityOutcome.Status.INVALID;
            default -> firstBreach != null ? CapacityOutcome.Status.PROVISIONAL : CapacityOutcome.Status.CENSORED_NOT_REACHED;
        };
        return end(s, lastPass, firstBreach, null, r.detail);
    }

    private CapacityOutcome end(CapacityOutcome.Status s, Integer pass, Integer breach, Integer cap, String detail) {
        return new CapacityOutcome(s, pass, breach, cap, detail, false, baseline, warmup, measured);
    }

    private Result observe(int level, String role, long seed) {
        if (spentMillis + config.observationWindowMillis() > config.maxMillisPerProfile()) {
            return new Result(Verdict.BUDGET, "Time budget exhausted before level " + level + " (" + role
                    + "); result is censored/provisional, not extrapolated.");
        }
        StageObservation o = runner.run(level, role, seed);
        measured.add(o);
        spentMillis += o.elapsedMillis();
        String bad = validate(o, true);
        if (bad != null) {
            Verdict v = o.invalid() || o.incorrect() ? Verdict.INVALID : o.generatorSaturated() ? Verdict.SATURATED : Verdict.INCOMPLETE;
            return new Result(v, "Level " + level + " (" + role + ") cannot establish capacity: " + bad);
        }
        return new Result(o.breached(config.thresholdMillis()) ? Verdict.BREACH : Verdict.PASS, "");
    }

    /** Returns a reason the stage cannot be evidence, or null. Breaches do not make a stage invalid. */
    private String validate(StageObservation o, boolean requireCoverage) {
        if (o.invalid()) return o.invalidReason();
        if (o.incorrect()) return "correctness/isolation failure";
        if (o.samples().stream().anyMatch(s -> !s.finalUsable())) return "provisional/empty/error content counted instead of final usable content";
        if (o.generatorSaturated()) return "generator saturated (attributed to the generator, not the application)";
        if (requireCoverage || o.role().equals(StageObservation.BASELINE)) {
            var missing = o.missingJourneys(config.journeys());
            if (!missing.isEmpty()) return "missing browser coverage for " + missing;
        }
        return null;
    }
}
