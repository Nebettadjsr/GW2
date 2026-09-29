package application;

import api.tp.EctoLivePriceGateway;
import org.junit.jupiter.api.Test;
import repo.ItemRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemReadServiceTest {
    @Test
    void refreshesOnlyMetadataMissingAnIconThenReadsStoredResult() throws Exception {
        Map<Integer, ItemRepository.ItemInfo> stored = new HashMap<>();
        stored.put(19721, new ItemRepository.ItemInfo(19721, "Glob", null,
                "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png"));
        var repository = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> ids) {
                return Map.copyOf(stored);
            }
        };
        var requestedRefresh = new java.util.ArrayList<Set<Integer>>();
        var service = new ItemReadService(new EctoLivePriceGateway(), repository, ids -> {
            requestedRefresh.add(Set.copyOf(ids));
            stored.put(23041, new ItemRepository.ItemInfo(23041, "Fine Salvage Kit", null, "new-icon"));
        });

        var result = service.metadata(Set.of(19721, 23041));
        assertEquals(Set.of(23041), requestedRefresh.getFirst());
        assertEquals("new-icon", result.get(23041).iconUrl);
        assertEquals(1, requestedRefresh.size());
        assertTrue(result.containsKey(19721));
    }
}
