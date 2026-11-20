package application;

import model.AccountScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An unknown Forge recipe is structural: rejected before capture/refresh admission/planning, whatever the account data. */
class ForgeProjectUnknownRecipeTest {
    @Test
    void unknownRecipeForUnsynchronizedAccountDoesNoSourceCaptureOrAdmission() throws Exception {
        AtomicInteger captures = new AtomicInteger(), priceCoverage = new AtomicInteger(), admissions = new AtomicInteger();
        var service = new CachedCraftingProfitService(
                new ProfitInputSession.Factory() {
                    @Override public ProfitInputSession open(AccountScope scope) { return ProfitInputSession.detached(scope); }
                },
                session -> new CraftingProfitService(null, session),
                (con, scope, sources) -> { captures.incrementAndGet(); return java.util.Map.of(); },
                (con, scope) -> { priceCoverage.incrementAndGet(); throw new AssertionError("no coverage read"); },
                null, null, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), java.time.Duration.ofHours(1));
        var outcome = service.forgeProject(AccountScope.of(java.util.UUID.randomUUID()), -987654321, 1, null, null);
        assertTrue(outcome.unknownRecipe());
        assertNull(outcome.data());
        assertEquals(0, captures.get(), "no account source read");
        assertEquals(0, priceCoverage.get());
        assertEquals(0, admissions.get());
    }
}
