package sync;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdListDiffTest {
    @Test
    void unchangedIdsDoNotRequestAnyDetailSynchronization() {
        var difference = IdListDiff.between(Set.of(1, 2, 3), Set.of(3, 2, 1));

        assertFalse(difference.changed());
        assertEquals(Set.of(), difference.added());
        assertEquals(Set.of(), difference.removed());
    }

    @Test
    void changedIdsCaptureBothAdditionsAndRemovals() {
        var difference = IdListDiff.between(Set.of(1, 2, 3), Set.of(2, 3, 4));

        assertTrue(difference.changed());
        assertEquals(Set.of(4), difference.added());
        assertEquals(Set.of(1), difference.removed());
    }
}
