import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackQuantityFormatterTest {
    @Test
    void formatsQuantityBelowAStack() {
        assertEquals("100", StackQuantityFormatter.format(100));
    }

    @Test
    void formatsExactlyOneStack() {
        assertEquals("1 × 250", StackQuantityFormatter.format(250));
    }

    @Test
    void formatsMultipleFullStacks() {
        assertEquals("4 × 250", StackQuantityFormatter.format(1000));
    }

    @Test
    void formatsStacksAndRemainders() {
        assertEquals("1 × 250 + 15", StackQuantityFormatter.format(265));
        assertEquals("3 × 250 + 15", StackQuantityFormatter.format(765));
    }
}
