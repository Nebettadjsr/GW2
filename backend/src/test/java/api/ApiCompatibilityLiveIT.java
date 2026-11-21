package api;

import api.ApiCompatibilityResult.State;
import api.ApiCompatibilityResult.Status;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit live gate (STORY-OPS-008; docs/TEST_STRATEGY.md live checks): runs the production
 * {@link ApiCompatibilityChecker} against the real public ArenaNet endpoints and prints the findings. It is
 * outside the default Surefire selection; CI runs it as its own bounded step and developers can run
 * {@code ./mvnw test -Dtest=ApiCompatibilityLiveIT}. It never hardcodes changing values and fails unless the
 * completed overall result is OK with the full expected probe set: any mismatch, attention (for example an
 * unreviewed newer schema), unavailable or not-run probe fails and names the probe.
 */
class ApiCompatibilityLiveIT {

    private static final Set<String> EXPECTED_PROBES = Set.of(
            "directory", "item", "recipe", "prices", "currencies", "jade-default", "jade-pinned");

    @Test
    void realPublicEndpointsMatchTheConsumedContracts() {
        var result = ApiCompatibilityChecker.live().check();
        System.out.println("API compatibility overall=" + result.overall() + " checkedAt=" + result.checkedAt()
                + " durationMs=" + result.durationMs() + " newerVersions=" + result.newerVersions());
        result.probes().forEach(p -> System.out.println("  " + p.id() + " " + p.status() + " " + p.code() + " - " + p.detail()));
        assertEquals(State.COMPLETE, result.state());
        assertEquals(EXPECTED_PROBES, result.probes().stream().map(ApiCompatibilityResult.Probe::id).collect(Collectors.toSet()));
        assertEquals(7, result.probes().size());
        for (var p : result.probes()) {
            assertEquals(Status.OK, p.status(), p.id() + " " + p.code() + ": " + p.detail());
        }
        assertTrue(result.newerVersions().isEmpty(), "Unreviewed newer schema versions: " + result.newerVersions());
        assertEquals(Status.OK, result.overall());
    }
}
