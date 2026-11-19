package api;

import java.time.Instant;
import java.util.List;

/**
 * Bounded latest result of the owner API compatibility check (STORY-OPS-008). Holds only
 * classified diagnostics built from fixed phrases and our own contract field names; never an
 * upstream response body, a credential or a raw error message. The only upstream text kept is the
 * length-limited version-directory description.
 */
public record ApiCompatibilityResult(
        State state,
        Instant checkedAt,
        long durationMs,
        Status overall,
        String recipeProfile,
        List<VersionEntry> versions,
        List<String> newerVersions,
        List<Probe> probes) {

    /** Lifecycle as seen by the owner; {@code COMPLETE} carries a result, the others carry none. */
    public enum State { NOT_CHECKED, RUNNING, COMPLETE }

    /**
     * OK compatible; ATTENTION known gap, new version or informational discrepancy needing review;
     * UNAVAILABLE upstream could not be judged (timeout, 429, 5xx, unreachable, not run);
     * MISMATCH a consumed shape is missing, wrong-typed, malformed or unsupported.
     */
    public enum Status { OK, ATTENTION, UNAVAILABLE, MISMATCH }

    public record VersionEntry(String version, String description, boolean reviewed) {}

    public record Probe(String id, String label, String endpoint, String profile, Status status,
                        String code, String detail) {}

    public static ApiCompatibilityResult notChecked() {
        return new ApiCompatibilityResult(State.NOT_CHECKED, null, 0, null, null, List.of(), List.of(), List.of());
    }

    /**
     * The latest attempt itself failed (a checker defect). Never green and never carries the defect's
     * text; it replaces any earlier result so an old success cannot pass as the latest check.
     */
    public static ApiCompatibilityResult failedAttempt(Instant at) {
        var probe = new Probe("check", "API compatibility check", "-", "-", Status.UNAVAILABLE, "CHECK_FAILED",
                "The latest check could not be completed because of an internal error. No result is available.");
        return new ApiCompatibilityResult(State.COMPLETE, at, 0, Status.UNAVAILABLE, null, List.of(), List.of(), List.of(probe));
    }

    public ApiCompatibilityResult asRunning() {
        return new ApiCompatibilityResult(State.RUNNING, checkedAt, durationMs, overall, recipeProfile,
                versions, newerVersions, probes);
    }

    /** Severity-ordered aggregate: no probe's success can hide another probe's problem. */
    public static Status worst(List<Probe> probes) {
        Status worst = Status.OK;
        for (Probe p : probes) {
            if (rank(p.status()) > rank(worst)) worst = p.status();
        }
        return worst;
    }

    private static int rank(Status s) {
        return switch (s) { case OK -> 0; case ATTENTION -> 1; case UNAVAILABLE -> 2; case MISMATCH -> 3; };
    }
}
