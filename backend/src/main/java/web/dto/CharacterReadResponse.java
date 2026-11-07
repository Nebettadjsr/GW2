package web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Body of the Characters roster and selected-character reads (STORY-CHAR-001).
 *
 * <p>{@code state} is always present. {@code reason}/{@code message} are present for every state
 * other than AVAILABLE. Exactly one of {@code characters} (roster read) or {@code character}
 * (detail read) carries data, and only for AVAILABLE or STALE; every other state omits both, so a
 * missing source can never read as an empty roster. An AVAILABLE roster with zero entries is a
 * genuinely published empty roster.
 *
 * @param characterCount size of {@code characters}; null when no roster is reported
 * @param sections       deferred-section availability on the detail read only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterReadResponse(String state,
                                    String reason,
                                    String message,
                                    Freshness freshness,
                                    Integer characterCount,
                                    List<CharacterDto> characters,
                                    CharacterDto character,
                                    Sections sections) {

    /** Source freshness already known to the shared sync infrastructure; null when not applicable. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Freshness(Instant completedAt, String refresh) {}

    public record DisciplineDto(String discipline, int rating, boolean active) {}

    public record CharacterDto(String name, String profession, String race, String gender, Integer level,
                               Instant createdAt, List<DisciplineDto> craftingDisciplines) {}

    /** Later sections are explicitly not implemented; they carry no payload and no empty collection. */
    public record Sections(String equipmentTabs, String inventory, String buildTabs) {
        public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

        public static Sections deferred() {
            return new Sections(NOT_IMPLEMENTED, NOT_IMPLEMENTED, NOT_IMPLEMENTED);
        }
    }
}
