package application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CalculationInputVersionsForgeIdentityTest {

    private static CalculationInputVersions versions() {
        return new CalculationInputVersions("acct", Map.of(), Map.of(),
                CalculationFreshness.PriceCoverage.none(), Duration.ofMinutes(15), Instant.EPOCH);
    }

    @Test
    void forgeOffKeepsIdentityAndCatalogContentChangesIt() {
        String off = versions().calculationId();
        assertEquals(off, versions().withForgeCatalog(null).calculationId(), "toggle-off identity is unchanged");
        String a = versions().withForgeCatalog("1:aaa").calculationId();
        assertNotEquals(off, a, "enabling Forge is a different calculation");
        assertNotEquals(a, versions().withForgeCatalog("1:bbb").calculationId(), "a changed catalog is a different generation");
        assertEquals(a, versions().withForgeCatalog("1:aaa").calculationId());
    }

    @Test
    void metadataOnlyGapKeepsProjectProvisionalUntilCompleted() {
        var fresh = new java.util.HashMap<String, Instant>();
        for (var src : new CalculationInputVersions(Map.of(), CalculationFreshness.PriceCoverage.none(),
                Duration.ofMinutes(15), Instant.EPOCH).accountSources()) fresh.put(src.source(), Instant.EPOCH);
        var v = new CalculationInputVersions("acct", fresh, Map.of(),
                CalculationFreshness.PriceCoverage.none(), Duration.ofMinutes(15), Instant.EPOCH);
        assertTrue(v.confirmedCurrent(), "fresh sources and covered quotes are current");
        v.withProjectMetadataIncomplete(true);
        assertFalse(v.confirmedCurrent(), "a missing name/icon gap is provisional although quotes are fresh");
        v.withProjectMetadataIncomplete(false);
        assertTrue(v.confirmedCurrent(), "completed metadata is current again");
    }
}
