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
    private final WarmDeadline deadline;
    private long spentMillis;
    private int firstCoarseBreach = -1;

    /** The first level that breached in the coarse ramp (before refinement narrowed the boundary), or -1. */
    public int firstCoarseBreachLevel() { return firstCoarseBreach; }

    public CapacityCoordinator(CapacityConfig config, StageRunner runner) { this(config, runner, null); }

    /** {@code deadline} (nullable) is the hard warm-data freshness ceiling; stages that do not fit or cross it are not evidence. */
    public CapacityCoordinator(CapacityConfig config, StageRunner runner, WarmDeadline deadline) {
        this.config = config;
        this.runner = runner;
        this.deadline = deadline;
    }

    private enum Verdict { PASS, BREACH, INVALID, INCOMPLETE, SATURATED, BUDGET, EXPIRED }

    private record Result(Verdict verdict, String detail) { }

    public CapacityOutcome discover() {
        CapacityOutcome outcome;
        try {
            outcome = run();
        } catch (RuntimeException | Error failure) {
            // Preserve the original failure as the result; still stop and drain below.
            outcome = new CapacityOutcome(CapacityOutcome.Status.INVALID, null, null, null,
                    "Runner failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    false, baseline, warmup, measured);
        }
        String extra = null;
        boolean drained;
        try {
            runner.stopScheduling();
        } catch (RuntimeException e) {
            extra = "stopScheduling failed: " + e.getClass().getSimpleName();
        }
        try {
            drained = runner.drain(config.drainTimeoutMillis());
        } catch (RuntimeException e) {
            drained = false;
            extra = (extra == null ? "" : extra + "; ") + "drain failed: " + e.getClass().getSimpleName();
        }
        if (!drained || extra != null) {
            outcome = new CapacityOutcome(outcome.status(), outcome.lastPassingLevel(), outcome.firstBreachLevel(),
                    outcome.confirmedCapacity(), outcome.detail() + " Workers did not drain"
                    + (extra == null ? "" : " (" + extra + ")") + ": isolated resources retained; cleanup required.",
                    true, baseline, warmup, measured);
        }
        return outcome;
    }

    private CapacityOutcome run() {
        // Preflight no-load baseline and warmup (separate sample sets, not measured stages).
        if (config.observationWindowMillis() > config.maxMillisPerProfile()) {
            return end(CapacityOutcome.Status.CENSORED_NOT_REACHED, null, null, null,
                    "Profile budget cannot cover even the baseline window; nothing was started.");
        }
        if (!fitsDeadline()) {
            return end(CapacityOutcome.Status.CENSORED_NOT_REACHED, null, null, null,
                    "Warm deadline too short for the baseline window; nothing was started.");
        }
        StageObservation b = runner.run(1, StageObservation.BASELINE, config.seed());
        if (crossed()) return end(CapacityOutcome.Status.INVALID, null, null, null, "Baseline crossed the warm deadline; rejected (prepared data may have expired).");
        baseline.add(b);
        spentMillis += b.elapsedMillis();
        String bad = validate(b, false);
        if (bad != null) return end(CapacityOutcome.Status.INVALID, null, null, null, "Baseline invalid: " + bad);
        if (!fits()) {
            return end(CapacityOutcome.Status.CENSORED_NOT_REACHED, null, null, null,
                    "Profile budget exhausted after the baseline; warmup and measured stages were not started.");
        }
        if (!fitsDeadline()) {
            return end(CapacityOutcome.Status.CENSORED_NOT_REACHED, null, null, null,
                    "Warm deadline reached after the baseline; warmup and measured stages were not started.");
        }
        StageObservation w = runner.run(1, StageObservation.WARMUP, config.seed());
        if (crossed()) return end(CapacityOutcome.Status.INVALID, null, null, null, "Warmup crossed the warm deadline; rejected (prepared data may have expired).");
        warmup.add(w);
        spentMillis += w.elapsedMillis();
        // Warmup latency is separate evidence, but its correctness/isolation/coverage must hold.
        String warmBad = validate(w, true);
        if (warmBad != null) return end(CapacityOutcome.Status.INVALID, null, null, null, "Warmup invalid: " + warmBad);

        int lastPass = 0;
        int breach = -1;
        for (int level : config.coarseLevels()) {
            if (level > config.maxUsers()) break;
            Result r = observe(level, StageObservation.COARSE, config.seed());
            if (r.verdict == Verdict.BREACH) { breach = level; firstCoarseBreach = level; break; }
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
            case EXPIRED -> firstBreach != null ? CapacityOutcome.Status.PROVISIONAL : CapacityOutcome.Status.CENSORED_NOT_REACHED;
            default -> firstBreach != null ? CapacityOutcome.Status.PROVISIONAL : CapacityOutcome.Status.CENSORED_NOT_REACHED;
        };
        return end(s, lastPass, firstBreach, null, r.detail);
    }

    private CapacityOutcome end(CapacityOutcome.Status s, Integer pass, Integer breach, Integer cap, String detail) {
        return new CapacityOutcome(s, pass, breach, cap, detail, false, baseline, warmup, measured);
    }

    /** Forecast: the next window is expected to need at least the configured window or the longest window seen. */
    private boolean fits() {
        long need = config.observationWindowMillis();
        for (var list : List.of(baseline, warmup, measured)) {
            for (StageObservation o : list) need = Math.max(need, o.elapsedMillis());
        }
        return spentMillis + need <= config.maxMillisPerProfile();
    }

    /** Longest window forecast must end before the hard warm deadline. */
    private boolean fitsDeadline() {
        if (deadline == null) return true;
        long need = config.observationWindowMillis();
        for (var list : List.of(baseline, warmup, measured)) {
            for (StageObservation o : list) need = Math.max(need, o.elapsedMillis());
        }
        return !deadline.expired() && need <= deadline.remainingMillis();
    }

    private boolean crossed() { return deadline != null && deadline.expired(); }

    private Result observe(int level, String role, long seed) {
        if (!fits()) {
            return new Result(Verdict.BUDGET, "Time budget exhausted before level " + level + " (" + role
                    + "); result is censored/provisional, not extrapolated.");
        }
        if (!fitsDeadline()) {
            return new Result(Verdict.EXPIRED, "Warm deadline (freshness ceiling) reached before level " + level + " (" + role
                    + "); remaining stages were not started, result is censored/provisional.");
        }
        StageObservation o = runner.run(level, role, seed);
        if (crossed()) {
            return new Result(Verdict.EXPIRED, "Level " + level + " (" + role + ") crossed the warm deadline and was rejected; "
                    + "prepared data may have expired, so it is not evidence.");
        }
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
