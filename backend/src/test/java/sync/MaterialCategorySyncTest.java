package sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialCategorySyncTest {
    @Test void parsesOfficialOrderAndPreservesItemSequence() throws Exception {
        var json = new ObjectMapper().readTree("""
                [{"id":7,"name":"Second","order":9,"items":[33,11]},
                 {"id":2,"name":"First","order":1,"items":[45,46]}]
                """);
        var categories = MaterialCategorySync.parse(json);
        assertEquals(java.util.List.of(7, 2), categories.stream().map(MaterialCategorySync.Category::id).toList());
        assertEquals(java.util.List.of(33, 11), categories.getFirst().itemIds());
        assertEquals(9, categories.getFirst().displayOrder());
    }
}
