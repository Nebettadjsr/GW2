package application;

import api.tp.EctoLivePriceGateway;
import application.icons.ItemIconUrls;
import craft.PriceQuote;
import repo.ItemRepository;
import sync.IconSync;

import java.sql.SQLException;
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
            throws SQLException, MetadataSourceUnavailableException {
        Map<Integer, ItemRepository.ItemInfo> rows = itemRepository.loadItems(ids);
        Set<Integer> missing = new HashSet<>();
        for (Integer id : ids) {
            ItemRepository.ItemInfo item = rows.get(id);
            if (item == null || ItemIconUrls.iconUrlFor(id, item.iconUrl) == null) missing.add(id);
        }
        if (!missing.isEmpty()) {
            try {
                metadataRefresh.refresh(missing);
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new MetadataSourceUnavailableException(e);
            }
            rows = itemRepository.loadItems(ids);
        }
        return rows;
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
