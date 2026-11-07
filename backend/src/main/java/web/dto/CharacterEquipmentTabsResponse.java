package web.dto;

import application.CharacterEquipmentTabsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import repo.CharacterEquipmentTabsRepository.ItemRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Body of the selected-character saved-equipment-tabs read (STORY-CHAR-005). {@code tabs} is present
 * only for AVAILABLE or STALE, so a missing, pending or incompatible coverage can never read as an
 * empty tab list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterEquipmentTabsResponse(String state, String reason, String message, String character,
                                             Coverage parent, Coverage equipment, Long coveredParentGeneration,
                                             List<TabDto> tabs) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Coverage(Long generation, Instant completedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDto(int itemId, boolean resolved, String name, String type, String rarity, String icon) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryDto(String slot, ItemDto item, String location, List<ItemDto> upgrades,
                           List<ItemDto> infusions, Integer statId, Map<String, Integer> statAttributes,
                           String binding, String boundTo) {}

    public record TabDto(int tab, String name, boolean active, List<EntryDto> equipment) {}

    public static ResponseEntity<CharacterEquipmentTabsResponse> respond(CharacterEquipmentTabsService.Result r) {
        var body = new CharacterEquipmentTabsResponse(r.state().name(), r.reason(), r.message(), r.character(),
                r.parentGeneration() == null ? null : new Coverage(r.parentGeneration(), r.parentCompletedAt()),
                r.equipmentGeneration() == null ? null : new Coverage(r.equipmentGeneration(), r.equipmentCompletedAt()),
                r.coveredParentGeneration(),
                r.tabs() == null ? null : r.tabs().stream().map(t -> new TabDto(t.number(), t.name(), t.active(),
                        t.equipment().stream().map(CharacterEquipmentTabsResponse::entry).toList())).toList());
        HttpStatus status = switch (r.state()) {
            case AVAILABLE, STALE -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case GRANT_REQUIRED, ENDPOINT_RESTRICTED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(body);
    }

    public static ResponseEntity<CharacterEquipmentTabsResponse> unavailable(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new CharacterEquipmentTabsResponse(
                "UNAVAILABLE", reason, "Equipment tabs cannot be read right now.", null, null, null, null, null));
    }

    private static EntryDto entry(repo.CharacterEquipmentTabsRepository.Entry e) {
        return new EntryDto(e.slot(), item(e.item()), e.location(),
                e.upgrades().stream().map(CharacterEquipmentTabsResponse::item).toList(),
                e.infusions().stream().map(CharacterEquipmentTabsResponse::item).toList(),
                e.statId(), e.statAttributes(), e.binding(), e.boundTo());
    }

    private static ItemDto item(ItemRef i) {
        return new ItemDto(i.itemId(), i.resolved(), i.name(), i.type(), i.rarity(), i.icon());
    }
}
