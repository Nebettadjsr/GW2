package web;

import application.ItemReadService;
import application.ItemReadService.PriceSourceUnavailableException;
import application.ItemReadService.MetadataSourceUnavailableException;
import application.icons.ItemIconUrls;
import craft.PriceQuote;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import repo.ItemRepository;
import web.dto.ItemMetadataResponse;
import web.dto.ItemPricesResponse;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Generic batch reads; neither route performs salvage economics. */
@RestController
@RequestMapping("/api/items")
public class ItemReadApiController {
    private final ItemReadService itemReadService;

    public ItemReadApiController(ItemReadService itemReadService) { this.itemReadService = itemReadService; }

    @GetMapping(path = "/prices", produces = MediaType.APPLICATION_JSON_VALUE)
    public ItemPricesResponse prices(@RequestParam("ids") String ids) throws PriceSourceUnavailableException {
        int[] itemIds = parseIds(ids);
        Map<Integer, PriceQuote> quotes = itemReadService.livePrices(itemIds);
        var prices = Arrays.stream(itemIds).mapToObj(id -> {
            PriceQuote quote = quotes.get(id);
            return new ItemPricesResponse.ItemPriceDto(id,
                    quote == null ? null : quote.buyUnit, quote == null ? null : quote.sellUnit);
        }).toList();
        return new ItemPricesResponse(prices);
    }

    @GetMapping(path = "/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public ItemMetadataResponse metadata(@RequestParam("ids") String ids) throws SQLException, MetadataSourceUnavailableException {
        int[] itemIds = parseIds(ids);
        Set<Integer> requested = new LinkedHashSet<>();
        for (int id : itemIds) requested.add(id);
        Map<Integer, ItemRepository.ItemInfo> rows = itemReadService.metadata(requested);
        var items = Arrays.stream(itemIds).mapToObj(id -> {
            ItemRepository.ItemInfo row = rows.get(id);
            return new ItemMetadataResponse.ItemMetadataDto(id, row == null ? null : row.name,
                    row == null ? null : ItemIconUrls.iconUrlFor(id, row.iconUrl));
        }).toList();
        return new ItemMetadataResponse(items);
    }

    private static int[] parseIds(String ids) {
        if (ids == null || ids.isBlank()) throw new ApiValidationException("ids is required");
        String[] values = ids.split(",", -1);
        if (values.length > 50) throw new ApiValidationException("At most 50 item IDs are accepted");
        Set<Integer> distinct = new LinkedHashSet<>();
        try {
            for (String value : values) {
                if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
                distinct.add(Integer.parseInt(value));
            }
        } catch (NumberFormatException invalid) {
            throw new ApiValidationException("ids must contain positive decimal item IDs");
        }
        if (distinct.size() != values.length) throw new ApiValidationException("ids must not repeat item IDs");
        return distinct.stream().mapToInt(Integer::intValue).toArray();
    }
}
