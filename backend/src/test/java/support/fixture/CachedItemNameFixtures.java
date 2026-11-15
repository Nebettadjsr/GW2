package support.fixture;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** STORY-MAT-003 fixtures address itemName by name, without prescribing record argument order. */
public final class CachedItemNameFixtures {
    private CachedItemNameFixtures() {}

    public static <T extends Record> T withItemName(T row, String name) throws ReflectiveOperationException {
        RecordComponent[] components = row.getClass().getRecordComponents();
        assertTrue(Arrays.stream(components).anyMatch(component -> component.getName().equals("itemName")),
                () -> row.getClass().getSimpleName() + " must carry nullable cached itemName");
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals("itemName") ? name : components[i].getAccessor().invoke(row);
        }
        @SuppressWarnings("unchecked")
        T named = (T) row.getClass().getConstructor(types).newInstance(values);
        return named;
    }

    public static String itemName(Record row) throws ReflectiveOperationException {
        assertTrue(Arrays.stream(row.getClass().getRecordComponents())
                        .anyMatch(component -> component.getName().equals("itemName")),
                () -> row.getClass().getSimpleName() + " must carry nullable cached itemName");
        return (String) row.getClass().getMethod("itemName").invoke(row);
    }
}
