package web.dto;

import java.util.List;

public record ItemPricesResponse(List<ItemPriceDto> prices) {
    public record ItemPriceDto(int itemId, Integer buyUnitCopper, Integer sellUnitCopper) {}
}
