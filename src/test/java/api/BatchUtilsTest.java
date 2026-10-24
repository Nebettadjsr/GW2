package api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchUtilsTest {

    @Test
    void chunkPreservesOrderAndIncludesFinalShortBatch() {
        List<List<Integer>> batches = BatchUtils.chunk(List.of(10, 20, 30, 40, 50), 2);

        assertEquals(List.of(List.of(10, 20), List.of(30, 40), List.of(50)), batches);
    }

    @Test
    void chunkOfExactMultipleHasNoShortBatch() {
        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6)),
                BatchUtils.chunk(List.of(1, 2, 3, 4, 5, 6), 3));
    }

    @Test
    void chunkOfEmptyInputReturnsNoBatches() {
        assertTrue(BatchUtils.chunk(List.<Integer>of(), 200).isEmpty());
    }

    @Test
    void idsParamKeepsPositiveIdsInOrder() {
        assertEquals("30,5,17", BatchUtils.idsParam(List.of(30, 5, 17)));
    }

    @Test
    void idsParamExcludesNullZeroAndNegativeIds() {
        assertEquals("7,9", BatchUtils.idsParam(Arrays.asList(null, 7, 0, -3, 9)));
    }

    @Test
    void idsParamOfEmptyOrAllInvalidInputIsEmpty() {
        assertEquals("", BatchUtils.idsParam(List.of()));
        assertEquals("", BatchUtils.idsParam(Arrays.asList(null, 0, -1)));
    }
}
