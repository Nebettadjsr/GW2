package web.dto;

import java.util.List;

public record ItemMetadataResponse(List<ItemMetadataDto> items) {
    public record ItemMetadataDto(int itemId, String name, String iconUrl) {}
}
