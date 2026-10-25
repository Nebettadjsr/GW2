package luck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MagicFindProgressionTest {
    private final MagicFindProgression progression = MagicFindProgression.canonical();

    @Test
    void wikiMilestonesAndAllLevelsArePresent() {
        assertEquals(0, progression.thresholdFor(0));
        assertEquals(100, progression.thresholdFor(1));
        assertEquals(14_550, progression.thresholdFor(51));
        assertEquals(4_295_450, progression.thresholdFor(300));
        for (int percent = 1; percent <= 300; percent++) {
            assertTrue(progression.thresholdFor(percent) > progression.thresholdFor(percent - 1));
        }
    }

    @Test
    void resolvesProgressAtStartBetweenThresholdsAndAtCap() {
        var start = progression.resolve(0);
        assertEquals(0, start.magicFindPercent());
        assertEquals(100L, start.nextThreshold().longValue());
        assertEquals(100, start.remainingToNext());

        var between = progression.resolve(14_549);
        assertEquals(50, between.magicFindPercent());
        assertEquals(14_550L, between.nextThreshold().longValue());
        assertEquals(1, between.remainingToNext());
        assertEquals(51, progression.resolve(14_550).magicFindPercent());

        var capped = progression.resolve(4_295_451);
        assertEquals(300, capped.magicFindPercent());
        assertNull(capped.nextThreshold());
        assertEquals(0, capped.remainingToNext());
        assertEquals(0, capped.remainingToCap());
    }

    @Test
    void laterTargetUsesExactCumulativeThreshold() {
        assertEquals(14_550, progression.thresholdFor(51));
        assertEquals(50, progression.remainingFor(14_500, 51));
        assertEquals(0, progression.remainingFor(14_600, 51));
        assertThrows(IllegalArgumentException.class, () -> progression.thresholdFor(301));
    }
}
