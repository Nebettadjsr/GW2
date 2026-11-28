package application;

import api.tp.EctoLivePriceGateway;
import domain.icons.ItemIconUrls;
import craft.PriceQuote;
import repo.DatabaseFailure;
import repo.ItemRepository;
import sync.IconSync;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Generic item reads over the established live TP and stored metadata adapters. */
public class ItemReadService {
    private final EctoLivePriceGateway priceGateway;
    private final ItemRepository itemRepository;
    private final MetadataRefresh metadataRefresh;

    public ItemReadService() { this(new EctoLivePriceGateway(), new ItemRepository(), IconSync::syncItemMetadataByIds); }

    public ItemReadService(EctoLivePriceGateway priceGateway, ItemRepository itemRepository,
                           MetadataRefresh metadataRefresh) {
        this.priceGateway = priceGateway;
        this.itemRepository = itemRepository;
        this.metadataRefresh = metadataRefresh;
    }

    public Map<Integer, PriceQuote> livePrices(int[] ids) throws PriceSourceUnavailableException {
        try {
            return priceGateway.fetchQuotes(ids);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new PriceSourceUnavailableException(e);
        }
    }

    public Map<Integer, ItemRepository.ItemInfo> metadata(Set<Integer> ids)
            throws DataStoreException, MetadataSourceUnavailableException {
        Map<Integer, ItemRepository.ItemInfo> rows = load(ids);
        Set<Integer> missing = new HashSet<>();
        for (Integer id : ids) {
            ItemRepository.ItemInfo item = rows.get(id);
            if (item == null || ItemIconUrls.iconUrlFor(id, item.iconUrl) == null) missing.add(id);
        }
        if (!missing.isEmpty()) {
            try {
                metadataRefresh.refresh(missing);
            } catch (Exception e) {
                if (DatabaseFailure.isDatabaseFailure(e)) throw new DataStoreException(e.getMessage(), e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new MetadataSourceUnavailableException(e);
            }
            rows = load(ids);
        }
        return rows;
    }

    private Map<Integer, ItemRepository.ItemInfo> load(Set<Integer> ids) throws DataStoreException {
        return DataStoreException.translate(() -> itemRepository.loadItems(ids));
    }

    @FunctionalInterface
    public interface MetadataRefresh { void refresh(Set<Integer> itemIds) throws Exception; }

    public static class PriceSourceUnavailableException extends Exception {
        public PriceSourceUnavailableException(Throwable cause) { super(cause); }
    }

    public static class MetadataSourceUnavailableException extends Exception {
        public MetadataSourceUnavailableException(Throwable cause) { super(cause); }
    }
}
