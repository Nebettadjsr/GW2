/** Presentation formatting for material quantities in shopping lists. */
public final class StackQuantityFormatter {
    private static final int STACK_SIZE = 250;

    private StackQuantityFormatter() {}

    public static String format(int quantity) {
        int stacks = quantity / STACK_SIZE;
        int remainder = quantity % STACK_SIZE;
        if (stacks == 0) return Integer.toString(quantity);
        return remainder == 0
                ? stacks + " × " + STACK_SIZE
                : stacks + " × " + STACK_SIZE + " + " + remainder;
    }
}
