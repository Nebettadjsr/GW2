package web.dto;

import application.CharacterInventoryLayoutService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import repo.CharacterInventoryLayoutRepository.ItemRef;

import java.time.Instant;
import java.util.List;

/**
 * Body of the selected-character bag-layout read (STORY-CHAR-006). {@code bags} and {@code contents}
 * are present only for AVAILABLE or STALE, so a legacy, pending or inconsistent layout can never read
 * as an empty one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterInventoryResponse(String state, String reason, String message, String character,
                                         Long generation, Instant completedAt, String contents,
                                         List<BagDto> bags) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDto(int itemId, boolean resolved, String name, String icon) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SlotDto(int slot, boolean empty, ItemDto item, Integer quantity, String binding, String boundTo) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BagDto(int bag, boolean installed, ItemDto container, Integer capacity, List<SlotDto> slots) {}

    public static ResponseEntity<CharacterInventoryResponse> respond(CharacterInventoryLayoutService.Result r) {
        var body = new CharacterInventoryResponse(r.state().name(), r.reason(), r.message(), r.character(),
                r.generation(), r.completedAt(), r.contents() == null ? null : r.contents().name(),
                r.bags() == null ? null : r.bags().stream().map(b -> new BagDto(b.bag(), b.installed(),
                        item(b.container()), b.capacity(),
                        b.slots().stream().map(s -> new SlotDto(s.slot(), s.empty(), item(s.item()),
                                s.quantity(), s.binding(), s.boundTo())).toList())).toList());
        HttpStatus status = switch (r.state()) {
            case AVAILABLE, STALE -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GRANT_REQUIRED, ENDPOINT_RESTRICTED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(body);
    }

    public static ResponseEntity<CharacterInventoryResponse> unavailable(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new CharacterInventoryResponse(
                "UNAVAILABLE", reason, "Character inventory cannot be read right now.", null, null, null, null, null));
    }

    private static ItemDto item(ItemRef i) {
        return i == null ? null : new ItemDto(i.itemId(), i.resolved(), i.name(), i.icon());
    }
}
