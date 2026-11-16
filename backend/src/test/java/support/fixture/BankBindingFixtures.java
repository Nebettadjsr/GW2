package support.fixture;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bank guards are addressed by component name, without prescribing record argument order. */
public final class BankBindingFixtures {
    private BankBindingFixtures() {}

    public static <T extends Record> T withBinding(T row, String binding, String boundTo)
            throws ReflectiveOperationException {
        RecordComponent[] components = row.getClass().getRecordComponents();
        for (String name : new String[]{"binding", "boundTo"}) {
            assertTrue(Arrays.stream(components).anyMatch(component -> component.getName().equals(name)),
                    () -> row.getClass().getSimpleName() + " must carry nullable " + name);
        }
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = switch (components[i].getName()) {
                case "binding" -> binding;
                case "boundTo" -> boundTo;
                default -> components[i].getAccessor().invoke(row);
            };
        }
        @SuppressWarnings("unchecked")
        T guarded = (T) row.getClass().getConstructor(types).newInstance(values);
        return guarded;
    }
}
