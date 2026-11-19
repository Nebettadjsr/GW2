package api;

import api.ApiCompatibilityResult.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Optional, explicitly invoked (STORY-OPS-008; docs/TEST_STRATEGY.md live checks): runs the same
 * {@link ApiCompatibilityChecker} against the real public ArenaNet endpoints and prints the findings.
 * Run with {@code ./mvnw test -Dtest=ApiCompatibilityLiveIT}. It fails on a mismatch or unavailable
 * probe and never hardcodes changing values. ATTENTION (the known Jade coverage gap, a newer schema
 * version) is a reported finding, not a failure, and is printed rather than hidden.
 */
class ApiCompatibilityLiveIT {

    @Test
    void realPublicEndpointsMatchTheConsumedContracts() {
        var result = ApiCompatibilityChecker.live().check();
        System.out.println("API compatibility overall=" + result.overall() + " checkedAt=" + result.checkedAt()
                + " durationMs=" + result.durationMs() + " newerVersions=" + result.newerVersions());
        result.probes().forEach(p -> System.out.println("  " + p.id() + " " + p.status() + " " + p.code() + " - " + p.detail()));
        for (var p : result.probes()) {
            assertNotEquals(Status.MISMATCH, p.status(), p.id() + ": " + p.detail());
            assertNotEquals(Status.UNAVAILABLE, p.status(), p.id() + ": " + p.detail());
        }
        assertEquals(7, result.probes().size());
    }
}
